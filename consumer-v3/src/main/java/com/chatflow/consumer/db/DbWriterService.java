package com.chatflow.consumer.db;

import com.chatflow.consumer.model.QueueMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the in-memory write buffer and a dedicated DB writer thread pool.
 *
 * Pipeline:
 *   SqsConsumerService.enqueue(msg)          ← called from 20 SQS poll threads
 *       ↓  put() — blocks only when queue is full (capacity = 100 000, rarely hit)
 *   LinkedBlockingQueue<QueueMessage>         ← shared buffer
 *       ↓  drainTo(batch, BATCH_SIZE)         ← non-blocking drain
 *   WriterTask (×writerThreads)
 *       ↓  MessageRepository.batchInsert()    ← single multi-row VALUES INSERT
 *   PostgreSQL RDS
 *
 * Retry behaviour (no external lib needed):
 *   Each batch is retried up to maxRetries times with linear backoff.
 *   After all retries fail, messages are counted as lost and logged.
 *   This satisfies the assignment's "error recovery" requirement.
 */
@Service
public class DbWriterService {

    private static final Logger log = LoggerFactory.getLogger(DbWriterService.class);

    @Value("${app.db.batch-size:500}")
    private int batchSize;

    @Value("${app.db.flush-interval-ms:100}")
    private long flushIntervalMs;

    @Value("${app.db.writer-threads:5}")
    private int writerThreads;

    @Value("${app.db.queue-capacity:100000}")
    private int queueCapacity;

    @Value("${app.db.max-retries:3}")
    private int maxRetries;

    @Value("${app.db.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    private final MessageRepository repository;

    private LinkedBlockingQueue<QueueMessage> queue;
    private ExecutorService writerPool;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    // ── Metrics (exposed via HealthController) ────────────────────────────────
    private final AtomicLong totalEnqueued      = new AtomicLong(0);
    private final AtomicLong totalInserted      = new AtomicLong(0);
    private final AtomicLong totalBatches       = new AtomicLong(0);
    private final AtomicLong totalLatencyMs     = new AtomicLong(0);
    private final AtomicLong failedInserts      = new AtomicLong(0);
    private final AtomicLong actualBatchSizeSum = new AtomicLong(0);

    public DbWriterService(MessageRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void start() {
        queue      = new LinkedBlockingQueue<>(queueCapacity);
        writerPool = Executors.newFixedThreadPool(writerThreads,
                r -> { Thread t = new Thread(r, "db-writer"); t.setDaemon(true); return t; });

        for (int i = 0; i < writerThreads; i++) {
            writerPool.submit(new WriterTask());
        }
        log.info("DbWriterService started: writerThreads={} batchSize={} flushIntervalMs={} queueCapacity={}",
                writerThreads, batchSize, flushIntervalMs, queueCapacity);
    }

    @PreDestroy
    public void stop() {
        shutdown.set(true);
        writerPool.shutdown();
        try {
            // Give writers time to flush remaining queue entries
            if (!writerPool.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("DbWriterService did not flush within 15 s; {} messages may be lost",
                        queue.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("DbWriterService stopped. enqueued={} inserted={} failed={}",
                totalEnqueued.get(), totalInserted.get(), failedInserts.get());
    }

    /**
     * Adds a message to the write buffer.
     *
     * Uses put() (blocking) rather than offer() (drop-on-full) so that no
     * messages are silently discarded when the DB is temporarily slow.
     * In practice the drain rate (~10 000 msg/s) far exceeds the enqueue rate
     * (~200 msg/s), so put() almost never blocks.
     */
    public void enqueue(QueueMessage msg) {
        try {
            queue.put(msg);
            totalEnqueued.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DB enqueue interrupted for message {}", msg.getMessageId());
        }
    }

    // ── Metrics getters ───────────────────────────────────────────────────────

    public long getTotalEnqueued()   { return totalEnqueued.get(); }
    public long getTotalInserted()   { return totalInserted.get(); }
    public long getTotalBatches()    { return totalBatches.get(); }
    public long getFailedInserts()   { return failedInserts.get(); }
    public int  getQueueDepth()      { return queue == null ? 0 : queue.size(); }

    /** Average rows per INSERT statement (0 if no batches yet). */
    public double getAvgBatchSize() {
        long batches = totalBatches.get();
        return batches == 0 ? 0.0 : (double) actualBatchSizeSum.get() / batches;
    }

    /** Average INSERT latency in milliseconds (0 if no batches yet). */
    public double getAvgInsertLatencyMs() {
        long batches = totalBatches.get();
        return batches == 0 ? 0.0 : (double) totalLatencyMs.get() / batches;
    }

    // ── Writer task ───────────────────────────────────────────────────────────

    private class WriterTask implements Runnable {

        @Override
        public void run() {
            while (!shutdown.get() || !queue.isEmpty()) {
                try {
                    // Block up to flushIntervalMs waiting for the first message.
                    // This implements the "flush interval" — a partial batch is
                    // still written after the timeout rather than sitting idle.
                    QueueMessage first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                    if (first == null) continue; // timeout with empty queue

                    List<QueueMessage> batch = new ArrayList<>(batchSize);
                    batch.add(first);
                    // Non-blocking drain: takes whatever is already in the queue,
                    // up to batchSize - 1 additional messages.
                    queue.drainTo(batch, batchSize - 1);

                    flushBatch(batch);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.debug("WriterTask exiting (shutdown={}, queueSize={})",
                    shutdown.get(), queue.size());
        }

        private void flushBatch(List<QueueMessage> batch) {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    long t0 = System.currentTimeMillis();
                    int inserted = repository.batchInsert(batch);
                    long elapsedMs = System.currentTimeMillis() - t0;

                    totalInserted.addAndGet(inserted);
                    totalBatches.incrementAndGet();
                    totalLatencyMs.addAndGet(elapsedMs);
                    actualBatchSizeSum.addAndGet(batch.size());

                    log.debug("Flushed batch: size={} inserted={} latencyMs={}",
                            batch.size(), inserted, elapsedMs);
                    return; // success — exit retry loop

                } catch (Exception e) {
                    if (attempt < maxRetries) {
                        long backoff = retryBackoffMs * attempt;
                        log.warn("Batch INSERT attempt {}/{} failed, retrying in {}ms: {}",
                                attempt, maxRetries, backoff, e.getMessage());
                        sleepQuietly(backoff);
                    } else {
                        log.error("Batch INSERT failed after {} attempts — {} messages lost: {}",
                                maxRetries, batch.size(), e.getMessage());
                        failedInserts.addAndGet(batch.size());
                    }
                }
            }
        }

        private void sleepQuietly(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
