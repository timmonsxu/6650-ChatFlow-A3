package com.chatflow.server.sqs;

import com.chatflow.server.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes QueueMessages to the correct FIFO SQS queue asynchronously.
 *
 * Circuit Breaker (Resilience4j):
 *   Wraps doPublish() — if SQS fails on >50% of the last 10 calls, the
 *   circuit opens and subsequent publish attempts are redirected to the
 *   fallback (publishFallback) immediately without calling SQS.
 *   After 30s the circuit transitions to Half-Open for 3 trial calls.
 *
 * Dead Letter Queue (in-memory):
 *   Messages that fail when the circuit is open are placed in a bounded
 *   in-memory dead letter queue (capacity 10,000). A background retry
 *   thread drains the DLQ every 5 seconds when the circuit is closed.
 *   This prevents message loss during transient SQS outages.
 *
 * Trade-off: ack is sent to the client before SQS confirmation. If the
 * server crashes while the DLQ still has messages, those messages are lost.
 * Acceptable for load-test purposes.
 */
@Component
public class SqsPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsPublisher.class);
    private static final int ASYNC_THREAD_POOL_SIZE = 20;
    private static final int DLQ_CAPACITY = 10_000;
    private static final String CB_NAME = "sqsPublisher";

    @Value("${app.sqs.region}")
    private String region;

    @Value("${app.sqs.account-id}")
    private String accountId;

    @Value("${app.sqs.queue-name-prefix}")
    private String queueNamePrefix;

    private SqsClient sqsClient;
    private ExecutorService publishExecutor;

    /** In-memory dead letter queue for messages rejected while circuit is open. */
    private final BlockingQueue<QueueMessage> deadLetterQueue =
            new ArrayBlockingQueue<>(DLQ_CAPACITY);

    private final AtomicLong dlqDropped = new AtomicLong(0);
    private final AtomicLong dlqRetried = new AtomicLong(0);

    private ExecutorService dlqRetryExecutor;
    private volatile boolean running = false;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .build();
        publishExecutor = Executors.newFixedThreadPool(ASYNC_THREAD_POOL_SIZE);

        running = true;
        dlqRetryExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dlq-retry");
            t.setDaemon(true);
            return t;
        });
        dlqRetryExecutor.submit(this::dlqRetryLoop);

        log.info("SqsPublisher initialised: region={}, asyncThreads={}, dlqCapacity={}",
                region, ASYNC_THREAD_POOL_SIZE, DLQ_CAPACITY);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        publishExecutor.shutdown();
        dlqRetryExecutor.shutdown();
        try {
            publishExecutor.awaitTermination(10, TimeUnit.SECONDS);
            dlqRetryExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("SqsPublisher shutdown. dlqRetried={}, dlqDropped={}",
                dlqRetried.get(), dlqDropped.get());
        sqsClient.close();
    }

    /**
     * Submits the SQS publish to a background thread pool and returns immediately.
     * The circuit breaker monitors doPublish() — if the circuit is open, the
     * fallback is invoked instead and the message goes to the dead letter queue.
     */
    public void publishAsync(QueueMessage msg) {
        publishExecutor.submit(() -> doPublishWithCircuitBreaker(msg));
    }

    /**
     * Circuit-breaker-protected publish.
     * On CallNotPermittedException (circuit open), Resilience4j calls publishFallback.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "publishFallback")
    public void doPublishWithCircuitBreaker(QueueMessage msg) {
        doPublish(msg);
    }

    /**
     * Fallback invoked when the circuit is open or doPublish throws.
     * Places the message in the dead letter queue for later retry.
     * The Exception parameter is required by Resilience4j fallback signature.
     */
    public void publishFallback(QueueMessage msg, Exception ex) {
        log.warn("Circuit open or publish failed for room {} — routing to DLQ: {}",
                msg.getRoomId(), ex.getClass().getSimpleName());
        boolean added = deadLetterQueue.offer(msg);
        if (!added) {
            dlqDropped.incrementAndGet();
            log.error("DLQ full — message dropped for room {}", msg.getRoomId());
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private void doPublish(QueueMessage msg) {
        String queueUrl = buildQueueUrl(msg.getRoomId());
        try {
            String body = mapper.writeValueAsString(msg);
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(msg.getRoomId())
                    .messageDeduplicationId(msg.getMessageId())
                    .build();
            sqsClient.sendMessage(request);
        } catch (Exception e) {
            log.error("SQS publish failed for room {}: {}", msg.getRoomId(), e.getMessage());
            // Wrap as RuntimeException so Circuit Breaker records the failure
            // and the method signature stays clean (no checked exception declaration)
            throw new RuntimeException("SQS publish failed for room " + msg.getRoomId(), e);
        }
    }

    /**
     * Background loop that drains the dead letter queue every 5 seconds.
     * Only retries when messages are present — no busy-waiting.
     */
    private void dlqRetryLoop() {
        while (running) {
            try {
                Thread.sleep(5_000);
                QueueMessage msg;
                int retried = 0;
                while ((msg = deadLetterQueue.poll()) != null) {
                    try {
                        doPublish(msg);
                        dlqRetried.incrementAndGet();
                        retried++;
                    } catch (Exception e) {
                        // Put back at end of DLQ for next retry cycle
                        boolean requeued = deadLetterQueue.offer(msg);
                        if (!requeued) {
                            dlqDropped.incrementAndGet();
                            log.error("DLQ full on requeue — message dropped for room {}", msg.getRoomId());
                        }
                        break; // SQS still unavailable, stop draining this cycle
                    }
                }
                if (retried > 0) {
                    log.info("DLQ retry cycle: {} messages re-published, {} remaining",
                            retried, deadLetterQueue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private String buildQueueUrl(String roomId) {
        return String.format("https://sqs.%s.amazonaws.com/%s/%s%s.fifo",
                region, accountId, queueNamePrefix, roomId);
    }

    public int getDlqSize()        { return deadLetterQueue.size(); }
    public long getDlqRetried()    { return dlqRetried.get(); }
    public long getDlqDropped()    { return dlqDropped.get(); }
}
