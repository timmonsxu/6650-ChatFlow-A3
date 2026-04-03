package com.chatflow.consumer.stats;

import com.chatflow.consumer.model.QueueMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Maintains real-time in-memory statistics using a dedicated thread pool,
 * as required by the assignment's "Statistics aggregators" thread pool.
 *
 * Thread model:
 *   record(msg)     — called from the 20 SQS poll threads.
 *                     Operations are lock-free (LongAdder / ConcurrentHashMap),
 *                     so this is essentially O(1) and never slows the poll loop.
 *
 *   aggregatorPool  — 1 scheduled thread that runs every second.
 *                     Computes msg/sec rates by pruning a sliding timestamp window.
 *                     This is the "separate thread pool for statistics aggregators".
 *
 * getSnapshot()     — called by HealthController on demand.  Reads pre-computed
 *                     values so the HTTP response is always fast.
 */
@Service
public class StatsAggregatorService {

    private static final Logger log = LoggerFactory.getLogger(StatsAggregatorService.class);

    // ── Per-entity counters (updated from SQS poll threads) ──────────────────
    private final ConcurrentHashMap<Integer, LongAdder> roomCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LongAdder> userCounts = new ConcurrentHashMap<>();
    private final LongAdder totalMessages = new LongAdder();

    // ── Sliding timestamp window for msg/sec computation ─────────────────────
    // Stores epoch-millis of each recorded message (last 60 s worth).
    // ConcurrentLinkedDeque allows lock-free add from many threads.
    private final ConcurrentLinkedDeque<Long> recentTimestamps = new ConcurrentLinkedDeque<>();

    // ── Computed snapshot fields (written only by aggregator thread) ──────────
    private volatile double msgPerSec1s  = 0;
    private volatile double msgPerSec10s = 0;
    private volatile double msgPerSec60s = 0;

    // ── Dedicated aggregator thread pool ─────────────────────────────────────
    private ScheduledExecutorService aggregatorPool;

    @PostConstruct
    public void start() {
        aggregatorPool = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "stats-aggregator"); t.setDaemon(true); return t; });
        // Run every second: prune old timestamps + recompute rates
        aggregatorPool.scheduleAtFixedRate(this::aggregate, 1, 1, TimeUnit.SECONDS);
        log.info("StatsAggregatorService started");
    }

    @PreDestroy
    public void stop() {
        aggregatorPool.shutdown();
        log.info("StatsAggregatorService stopped. totalMessages={}", totalMessages.sum());
    }

    /**
     * Records one message.  Called from SQS poll threads — must be non-blocking.
     */
    public void record(QueueMessage msg) {
        totalMessages.increment();

        // Increment per-room counter
        int roomId = parseRoomId(msg.getRoomId());
        roomCounts.computeIfAbsent(roomId, k -> new LongAdder()).increment();

        // Increment per-user counter
        int userId = parseUserId(msg.getUserId());
        userCounts.computeIfAbsent(userId, k -> new LongAdder()).increment();

        // Append timestamp for sliding-window rate calculation
        recentTimestamps.addLast(System.currentTimeMillis());
    }

    /**
     * Returns a point-in-time statistics snapshot.
     * Safe to call from any thread; all reads are from volatile / atomic fields.
     */
    public StatsSnapshot getSnapshot() {
        return new StatsSnapshot(
                totalMessages.sum(),
                msgPerSec1s,
                msgPerSec10s,
                msgPerSec60s,
                topN(roomCounts, 10),
                topN(userCounts, 10)
        );
    }

    // ── Aggregator task (runs every second on dedicated thread) ───────────────

    private void aggregate() {
        long now = System.currentTimeMillis();
        long cutoff60s = now - 60_000;
        long cutoff10s = now - 10_000;
        long cutoff1s  = now - 1_000;

        // Prune entries older than 60 s from the front of the deque
        while (!recentTimestamps.isEmpty()) {
            Long oldest = recentTimestamps.peekFirst();
            if (oldest != null && oldest < cutoff60s) {
                recentTimestamps.pollFirst();
            } else {
                break;
            }
        }

        // Count entries within each window
        long count1s = 0, count10s = 0, count60s = 0;
        for (Long ts : recentTimestamps) {
            if (ts >= cutoff60s) count60s++;
            if (ts >= cutoff10s) count10s++;
            if (ts >= cutoff1s)  count1s++;
        }

        msgPerSec60s = count60s / 60.0;
        msgPerSec10s = count10s / 10.0;
        msgPerSec1s  = count1s;  // count over last second ≈ msg/s
    }

    // ── Helper: top-N entries by count ────────────────────────────────────────

    private static List<Map.Entry<Integer, Long>> topN(
            ConcurrentHashMap<Integer, LongAdder> map, int n) {
        return map.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .limit(n)
                .toList();
    }

    // ── Type parsing ──────────────────────────────────────────────────────────

    private static int parseRoomId(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseUserId(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // ── Snapshot DTO ──────────────────────────────────────────────────────────

    public static class StatsSnapshot {
        public final long   totalMessages;
        public final double msgPerSec1s;
        public final double msgPerSec10s;
        public final double msgPerSec60s;
        /** Top-10 rooms by message count: [(roomId, count), ...] */
        public final List<Map.Entry<Integer, Long>> topRooms;
        /** Top-10 users by message count: [(userId, count), ...] */
        public final List<Map.Entry<Integer, Long>> topUsers;

        StatsSnapshot(long totalMessages,
                      double msgPerSec1s,
                      double msgPerSec10s,
                      double msgPerSec60s,
                      List<Map.Entry<Integer, Long>> topRooms,
                      List<Map.Entry<Integer, Long>> topUsers) {
            this.totalMessages = totalMessages;
            this.msgPerSec1s   = msgPerSec1s;
            this.msgPerSec10s  = msgPerSec10s;
            this.msgPerSec60s  = msgPerSec60s;
            this.topRooms      = topRooms;
            this.topUsers      = topUsers;
        }
    }
}
