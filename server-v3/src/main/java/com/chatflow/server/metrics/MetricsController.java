package com.chatflow.server.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GET /metrics — returns all 8 analytics queries in a single JSON response.
 *
 * All 8 queries are fired in PARALLEL via CompletableFuture so total response
 * time ≈ max(individual query times) rather than sum.
 *
 * Query parameters (all optional):
 *   roomId    — used for Q1 (default: 1)
 *   userId    — used for Q2, Q4 (default: 1)
 *   startTime — epoch millis, inclusive (default: 1 hour ago)
 *   endTime   — epoch millis, inclusive (default: now)
 *   topN      — how many results to return for top-N analytics (default: 10)
 *
 * Called by the load test client after the test completes.
 * The client passes the actual test startTime and endTime so the analytics
 * reflect exactly the messages generated during the test run.
 */
@RestController
@RequestMapping("/metrics")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    // Dedicated thread pool: 8 threads, one per parallel query.
    // Fixed pool avoids context-switch overhead of ForkJoinPool for I/O-bound work.
    private static final ExecutorService QUERY_POOL =
            Executors.newFixedThreadPool(8,
                    r -> { Thread t = new Thread(r, "metrics-query"); t.setDaemon(true); return t; });

    private final MessageQueryService queryService;

    public MetricsController(MessageQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetrics(
            @RequestParam(defaultValue = "1")  int  roomId,
            @RequestParam(defaultValue = "1")  int  userId,
            @RequestParam(required = false)    Long startTime,
            @RequestParam(required = false)    Long endTime,
            @RequestParam(defaultValue = "10") int  topN) {

        long now    = System.currentTimeMillis();
        long tEnd   = (endTime   != null) ? endTime   : now;
        long tStart = (startTime != null) ? startTime : tEnd - 3_600_000L; // default: last 1 hour

        long queryStartMs = System.currentTimeMillis();

        try {
            // ── Fire all 8 queries in parallel ────────────────────────────────

            CompletableFuture<List<MessageDto>>     q1 = async(() -> queryService.getMessagesInRoom(roomId, tStart, tEnd));
            CompletableFuture<List<MessageDto>>     q2 = async(() -> queryService.getUserHistory(userId, tStart, tEnd));
            CompletableFuture<Long>                 q3 = async(() -> queryService.countActiveUsers(tStart, tEnd));
            CompletableFuture<List<RoomActivityDto>>q4 = async(() -> queryService.getUserRooms(userId));
            CompletableFuture<List<MsgRateDto>>     a1 = async(() -> queryService.getMessagesPerMinute(tStart, tEnd));
            CompletableFuture<List<UserRankDto>>    a2 = async(() -> queryService.getTopActiveUsers(topN));
            CompletableFuture<List<RoomRankDto>>    a3 = async(() -> queryService.getTopActiveRooms(topN));
            CompletableFuture<Long>                 a4 = async(() -> queryService.getTotalMessages());

            CompletableFuture.allOf(q1, q2, q3, q4, a1, a2, a3, a4).join();

            long queryElapsedMs = System.currentTimeMillis() - queryStartMs;
            log.info("Metrics query completed in {}ms (roomId={} userId={} startTime={} endTime={})",
                    queryElapsedMs, roomId, userId, tStart, tEnd);

            // ── Assemble response ─────────────────────────────────────────────

            // Core queries section
            Map<String, Object> q1Result = new LinkedHashMap<>();
            q1Result.put("roomId",    roomId);
            q1Result.put("startTime", tStart);
            q1Result.put("endTime",   tEnd);
            q1Result.put("count",     q1.get().size());
            q1Result.put("messages",  q1.get());

            Map<String, Object> q2Result = new LinkedHashMap<>();
            q2Result.put("userId",    userId);
            q2Result.put("startTime", tStart);
            q2Result.put("endTime",   tEnd);
            q2Result.put("count",     q2.get().size());
            q2Result.put("messages",  q2.get());

            Map<String, Object> q3Result = new LinkedHashMap<>();
            q3Result.put("startTime",   tStart);
            q3Result.put("endTime",     tEnd);
            q3Result.put("activeUsers", q3.get());

            Map<String, Object> q4Result = new LinkedHashMap<>();
            q4Result.put("userId", userId);
            q4Result.put("rooms",  q4.get());

            Map<String, Object> coreQueries = new LinkedHashMap<>();
            coreQueries.put("roomMessages", q1Result);
            coreQueries.put("userHistory",  q2Result);
            coreQueries.put("activeUsers",  q3Result);
            coreQueries.put("userRooms",    q4Result);

            // Analytics section
            Map<String, Object> analytics = new LinkedHashMap<>();
            analytics.put("messagesPerMinute", a1.get());
            analytics.put("topActiveUsers",    a2.get());
            analytics.put("topActiveRooms",    a3.get());
            analytics.put("totalMessages",     a4.get());

            // Test summary
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalMessages",  a4.get());
            summary.put("queryTimeMs",    queryElapsedMs);
            summary.put("queryRoomId",    roomId);
            summary.put("queryUserId",    userId);
            summary.put("startTime",      tStart);
            summary.put("endTime",        tEnd);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("testSummary",  summary);
            response.put("coreQueries",  coreQueries);
            response.put("analytics",    analytics);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Metrics query failed: {}", e.getMessage(), e);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Metrics unavailable: " + e.getMessage()));
        }
    }

    /** Submits a Callable to the dedicated query pool and returns a CompletableFuture. */
    private <T> CompletableFuture<T> async(java.util.concurrent.Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, QUERY_POOL);
    }
}
