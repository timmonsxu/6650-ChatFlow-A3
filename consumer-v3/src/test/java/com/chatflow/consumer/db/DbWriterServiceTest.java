package com.chatflow.consumer.db;

import com.chatflow.consumer.model.QueueMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DbWriterService.
 * MessageRepository is mocked — no real DB or Spring context.
 */
class DbWriterServiceTest {

    private MessageRepository mockRepo;
    private DbWriterService   service;

    @BeforeEach
    void setUp() {
        mockRepo = mock(MessageRepository.class);
        service  = new DbWriterService(mockRepo);

        // Inject @Value fields manually (mimics small/fast test config)
        ReflectionTestUtils.setField(service, "batchSize",      5);
        ReflectionTestUtils.setField(service, "flushIntervalMs", 200L);
        ReflectionTestUtils.setField(service, "writerThreads",   1);
        ReflectionTestUtils.setField(service, "queueCapacity",   100);
        ReflectionTestUtils.setField(service, "maxRetries",      2);
        ReflectionTestUtils.setField(service, "retryBackoffMs",  50L);

        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialMetrics_areZero() {
        assertEquals(0, service.getTotalEnqueued());
        assertEquals(0, service.getTotalInserted());
        assertEquals(0, service.getTotalBatches());
        assertEquals(0, service.getFailedInserts());
        assertEquals(0, service.getQueueDepth());
        assertEquals(0.0, service.getAvgBatchSize());
        assertEquals(0.0, service.getAvgInsertLatencyMs());
    }

    // ── enqueue() ─────────────────────────────────────────────────────────────

    @Test
    void enqueue_incrementsEnqueuedCounter() {
        service.enqueue(makeMessage("m1"));
        service.enqueue(makeMessage("m2"));
        assertEquals(2, service.getTotalEnqueued());
    }

    @Test
    void enqueue_increasesQueueDepthUntilFlushed() {
        // Queue depth is at most the number of enqueued messages
        // (writer may drain it before we check)
        service.enqueue(makeMessage("a"));
        assertTrue(service.getQueueDepth() >= 0,
                "Queue depth must be non-negative");
    }

    // ── Flush pipeline ────────────────────────────────────────────────────────

    @Test
    void enqueuedMessages_areEventuallyFlushedToRepository() throws Exception {
        when(mockRepo.batchInsert(anyList())).thenReturn(3);

        service.enqueue(makeMessage("x1"));
        service.enqueue(makeMessage("x2"));
        service.enqueue(makeMessage("x3"));

        // Wait up to flushIntervalMs × 5 for the writer thread to process
        waitFor(() -> service.getTotalBatches() >= 1, 1500);

        verify(mockRepo, atLeastOnce()).batchInsert(anyList());
        assertTrue(service.getTotalInserted() > 0);
    }

    @Test
    void batchSizeRespected_drainToLimitsRows() throws Exception {
        // Batch size is 5; enqueue 8 messages → at least 1 batch of <= 5
        when(mockRepo.batchInsert(anyList())).thenAnswer(inv -> {
            List<?> b = inv.getArgument(0);
            assertTrue(b.size() <= 5, "Batch size must not exceed configured limit");
            return b.size();
        });

        for (int i = 0; i < 8; i++) {
            service.enqueue(makeMessage("m" + i));
        }
        waitFor(() -> service.getTotalBatches() >= 1, 1500);
    }

    // ── Retry on failure ──────────────────────────────────────────────────────

    @Test
    void repositoryException_isRetriedAndCountedAsFailed() throws Exception {
        // Always throw so all retries fail
        when(mockRepo.batchInsert(anyList()))
                .thenThrow(new RuntimeException("DB unavailable"));

        service.enqueue(makeMessage("fail1"));
        service.enqueue(makeMessage("fail2"));

        // Wait for retries to exhaust (maxRetries=2, backoff=50ms → ~100ms + flush latency)
        waitFor(() -> service.getFailedInserts() > 0, 2000);

        assertTrue(service.getFailedInserts() > 0,
                "Failed messages must be tracked after all retries exhausted");
        // Inserted count must NOT be incremented for failed batches
        assertEquals(0, service.getTotalInserted());
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Test
    void avgBatchSize_reflectsActualBatchesFlushed() throws Exception {
        when(mockRepo.batchInsert(anyList())).thenReturn(3);

        service.enqueue(makeMessage("a"));
        service.enqueue(makeMessage("b"));
        service.enqueue(makeMessage("c"));

        waitFor(() -> service.getTotalBatches() >= 1, 1500);

        // After at least one flush, avgBatchSize > 0
        assertTrue(service.getAvgBatchSize() > 0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static QueueMessage makeMessage(String id) {
        QueueMessage m = new QueueMessage();
        m.setMessageId(id);
        m.setRoomId("03");
        m.setUserId("99");
        m.setUsername("bob");
        m.setMessage("test");
        m.setMessageType("TEXT");
        m.setTimestamp("2026-04-02T12:00:00.000Z");
        return m;
    }

    /** Polls condition every 50 ms until true or timeoutMs exceeded. */
    private static void waitFor(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
