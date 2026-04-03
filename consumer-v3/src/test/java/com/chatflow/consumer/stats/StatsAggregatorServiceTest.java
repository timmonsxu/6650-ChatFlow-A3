package com.chatflow.consumer.stats;

import com.chatflow.consumer.model.QueueMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StatsAggregatorService.
 * No Spring context — service is started/stopped manually.
 */
class StatsAggregatorServiceTest {

    private StatsAggregatorService service;

    @BeforeEach
    void setUp() {
        service = new StatsAggregatorService();
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialSnapshot_allCountersZero() {
        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        assertEquals(0, snap.totalMessages);
        assertEquals(0.0, snap.msgPerSec1s);
        assertEquals(0.0, snap.msgPerSec10s);
        assertEquals(0.0, snap.msgPerSec60s);
        assertTrue(snap.topRooms.isEmpty());
        assertTrue(snap.topUsers.isEmpty());
    }

    // ── record() ─────────────────────────────────────────────────────────────

    @Test
    void record_incrementsTotalMessages() {
        service.record(msg("id1", "05", "42"));
        service.record(msg("id2", "05", "43"));
        service.record(msg("id3", "10", "42"));

        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        assertEquals(3, snap.totalMessages);
    }

    @Test
    void record_tracksPerRoomCounts() {
        service.record(msg("a", "05", "1"));
        service.record(msg("b", "05", "2"));
        service.record(msg("c", "10", "3"));

        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        // Room 5 should be top room with count 2
        assertFalse(snap.topRooms.isEmpty());
        assertEquals(5, snap.topRooms.get(0).getKey());
        assertEquals(2L, snap.topRooms.get(0).getValue());
    }

    @Test
    void record_tracksPerUserCounts() {
        service.record(msg("a", "01", "99"));
        service.record(msg("b", "02", "99"));
        service.record(msg("c", "03", "55"));

        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        // User 99 appears twice — should be top user
        assertEquals(99, snap.topUsers.get(0).getKey());
        assertEquals(2L, snap.topUsers.get(0).getValue());
    }

    @Test
    void topRooms_sortedByCountDescending() {
        // Room 1 → 1 msg, Room 2 → 3 msgs, Room 3 → 2 msgs
        service.record(msg("a", "01", "1"));
        service.record(msg("b", "02", "2"));
        service.record(msg("c", "02", "3"));
        service.record(msg("d", "02", "4"));
        service.record(msg("e", "03", "5"));
        service.record(msg("f", "03", "6"));

        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        // Should be: room 2 (3), room 3 (2), room 1 (1)
        assertEquals(2, snap.topRooms.get(0).getKey());
        assertEquals(3L, snap.topRooms.get(0).getValue());
        assertEquals(3, snap.topRooms.get(1).getKey());
        assertEquals(1, snap.topRooms.get(2).getKey());
    }

    @Test
    void topRooms_limitedToTen() {
        // Record messages to 15 different rooms
        for (int i = 1; i <= 15; i++) {
            service.record(msg("id-" + i, String.valueOf(i), "1"));
        }
        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        assertTrue(snap.topRooms.size() <= 10,
                "topRooms should be limited to 10 entries");
    }

    @Test
    void topUsers_limitedToTen() {
        for (int i = 1; i <= 15; i++) {
            service.record(msg("id-" + i, "01", String.valueOf(i)));
        }
        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        assertTrue(snap.topUsers.size() <= 10,
                "topUsers should be limited to 10 entries");
    }

    // ── Concurrency safety ────────────────────────────────────────────────────

    @Test
    void record_concurrentCalls_noExceptions() throws InterruptedException {
        int threads = 10, msgsPerThread = 100;
        Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < msgsPerThread; i++) {
                    service.record(msg("t" + tid + "m" + i,
                            String.valueOf((tid % 20) + 1),
                            String.valueOf(tid + 1)));
                }
            });
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();

        StatsAggregatorService.StatsSnapshot snap = service.getSnapshot();
        assertEquals((long) threads * msgsPerThread, snap.totalMessages,
                "All concurrent messages must be counted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static QueueMessage msg(String id, String roomId, String userId) {
        QueueMessage m = new QueueMessage();
        m.setMessageId(id);
        m.setRoomId(roomId);
        m.setUserId(userId);
        m.setUsername("user" + userId);
        m.setMessage("hello");
        m.setMessageType("TEXT");
        m.setTimestamp("2026-04-02T10:00:00.000Z");
        return m;
    }
}
