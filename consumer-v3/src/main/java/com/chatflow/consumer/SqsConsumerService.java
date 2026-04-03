package com.chatflow.consumer;

import com.chatflow.consumer.db.DbWriterService;
import com.chatflow.consumer.model.QueueMessage;
import com.chatflow.consumer.stats.StatsAggregatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls all 20 SQS rooms in parallel (one thread per room).
 *
 * Per-message pipeline (A3):
 *   1. Deserialise JSON body → QueueMessage
 *   2. broadcastClient.broadcast()          ← parallel HTTP to all servers
 *   3. dbWriterService.enqueue(msg)         ← O(1) put to write buffer  [NEW]
 *   4. statsAggregator.record(msg)          ← O(1) counter increment    [NEW]
 *   5. sqsClient.deleteMessage()            ← only after broadcast ok
 *
 * Steps 3 and 4 are O(1) and never block, so they add no measurable latency
 * to the existing broadcast → delete flow.
 * Actual DB writing happens asynchronously in DbWriterService's thread pool.
 */
@Service
public class SqsConsumerService {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumerService.class);
    private static final int NUM_ROOMS = 20;

    @Value("${app.sqs.region}")
    private String region;

    @Value("${app.sqs.account-id}")
    private String accountId;

    @Value("${app.sqs.queue-name-prefix}")
    private String queueNamePrefix;

    @Value("${app.consumer.threads}")
    private int numThreads;

    private final BroadcastClient         broadcastClient;
    private final DbWriterService         dbWriterService;
    private final StatsAggregatorService  statsAggregator;
    private final ObjectMapper            mapper = new ObjectMapper();

    private SqsClient       sqsClient;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Metrics ───────────────────────────────────────────────────────────────
    final AtomicLong messagesConsumed = new AtomicLong(0);
    final AtomicLong broadcastCalls   = new AtomicLong(0);

    public SqsConsumerService(BroadcastClient        broadcastClient,
                               DbWriterService        dbWriterService,
                               StatsAggregatorService statsAggregator) {
        this.broadcastClient = broadcastClient;
        this.dbWriterService = dbWriterService;
        this.statsAggregator = statsAggregator;
    }

    @PostConstruct
    public void start() {
        sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder()
                        .maxConnections(numThreads + 20))
                .build();

        running.set(true);
        executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int roomId = (i % NUM_ROOMS) + 1;
            executor.submit(() -> pollLoop(roomId));
        }

        log.info("SqsConsumerService started: {} threads across {} rooms", numThreads, NUM_ROOMS);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sqsClient.close();
        log.info("SqsConsumerService stopped. consumed={} broadcasts={}",
                messagesConsumed.get(), broadcastCalls.get());
    }

    public long getMessagesConsumed() { return messagesConsumed.get(); }
    public long getBroadcastCalls()   { return broadcastCalls.get(); }

    // ── Private ───────────────────────────────────────────────────────────────

    private void pollLoop(int roomId) {
        String paddedRoom = String.format("%02d", roomId);
        String queueUrl   = buildQueueUrl(paddedRoom);
        log.debug("Polling thread started for room {}", paddedRoom);

        while (running.get()) {
            try {
                ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(20)   // long polling
                        .build();

                List<Message> messages = sqsClient.receiveMessage(req).messages();
                for (Message msg : messages) {
                    processMessage(paddedRoom, msg, queueUrl);
                }

            } catch (Exception e) {
                if (running.get()) {
                    log.error("Poll error for room {}: {}", paddedRoom, e.getMessage());
                    sleepQuietly(1000);
                }
            }
        }
    }

    private void processMessage(String roomId, Message sqsMessage, String queueUrl) {
        String body = sqsMessage.body();
        try {
            // Deserialise once — reused for both broadcast body and DB/stats
            QueueMessage queueMessage = mapper.readValue(body, QueueMessage.class);
            String msgRoomId = queueMessage.getRoomId() != null
                    ? queueMessage.getRoomId() : roomId;

            // 1. Broadcast to all server instances (parallel HTTP, ~50–200 ms)
            //    Throws BroadcastException if ALL servers fail → skip delete.
            broadcastClient.broadcast(msgRoomId, body);
            broadcastCalls.incrementAndGet();

            // 2. Enqueue for async DB write (O(1) put — never blocks in practice)
            dbWriterService.enqueue(queueMessage);

            // 3. Record in-memory stats (O(1) — lock-free counter increments)
            statsAggregator.record(queueMessage);

            // 4. Acknowledge message — only reached after successful broadcast
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(sqsMessage.receiptHandle())
                    .build());

            messagesConsumed.incrementAndGet();

        } catch (BroadcastClient.BroadcastException e) {
            // All servers failed — do NOT delete.  SQS will redeliver after
            // the visibility timeout.  ON CONFLICT DO NOTHING in the DB layer
            // ensures idempotent handling of any duplicates.
            log.error("Broadcast failed for room {}, message will be retried: {}", roomId, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to process message in room {}: {}", roomId, e.getMessage());
        }
    }

    private String buildQueueUrl(String paddedRoomId) {
        return String.format("https://sqs.%s.amazonaws.com/%s/%s%s.fifo",
                region, accountId, queueNamePrefix, paddedRoomId);
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
