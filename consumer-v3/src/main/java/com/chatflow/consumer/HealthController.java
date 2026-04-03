package com.chatflow.consumer;

import com.chatflow.consumer.db.DbWriterService;
import com.chatflow.consumer.stats.StatsAggregatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /health — operational snapshot for monitoring and batch-size experiments.
 *
 * Response fields:
 *   status            : "UP"
 *   timestamp         : ISO-8601 wall clock
 *
 *   sqs.consumed      : total SQS messages successfully processed
 *   sqs.broadcasts    : total broadcast calls made
 *
 *   db.enqueued       : messages put onto the write buffer
 *   db.inserted       : rows actually written to PostgreSQL (duplicates excluded)
 *   db.failed         : messages that could not be inserted after all retries
 *   db.queueDepth     : current write buffer depth (should stay near 0)
 *   db.batches        : total INSERT statements executed
 *   db.avgBatchSize   : actual average rows per INSERT
 *   db.avgInsertMs    : average INSERT statement latency in milliseconds
 *
 *   stats.totalMessages  : same as db.enqueued (from in-memory counter)
 *   stats.msgPerSec1s    : messages/sec over last 1 second
 *   stats.msgPerSec10s   : messages/sec over last 10 seconds
 *   stats.msgPerSec60s   : messages/sec over last 60 seconds
 *   stats.topRooms       : [{roomId, count}, ...] top-10 by volume
 *   stats.topUsers       : [{userId, count}, ...] top-10 by volume
 */
@RestController
public class HealthController {

    private final SqsConsumerService      consumerService;
    private final DbWriterService         dbWriterService;
    private final StatsAggregatorService  statsAggregator;

    public HealthController(SqsConsumerService     consumerService,
                            DbWriterService        dbWriterService,
                            StatsAggregatorService statsAggregator) {
        this.consumerService = consumerService;
        this.dbWriterService = dbWriterService;
        this.statsAggregator = statsAggregator;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        StatsAggregatorService.StatsSnapshot snap = statsAggregator.getSnapshot();

        // ── SQS metrics ───────────────────────────────────────────────────────
        Map<String, Object> sqs = new LinkedHashMap<>();
        sqs.put("consumed",   consumerService.getMessagesConsumed());
        sqs.put("broadcasts", consumerService.getBroadcastCalls());

        // ── DB write metrics ──────────────────────────────────────────────────
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("enqueued",     dbWriterService.getTotalEnqueued());
        db.put("inserted",     dbWriterService.getTotalInserted());
        db.put("failed",       dbWriterService.getFailedInserts());
        db.put("queueDepth",   dbWriterService.getQueueDepth());
        db.put("batches",      dbWriterService.getTotalBatches());
        db.put("avgBatchSize", String.format("%.1f", dbWriterService.getAvgBatchSize()));
        db.put("avgInsertMs",  String.format("%.1f", dbWriterService.getAvgInsertLatencyMs()));

        // ── Stats aggregator ──────────────────────────────────────────────────
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalMessages", snap.totalMessages);
        stats.put("msgPerSec1s",   String.format("%.1f", snap.msgPerSec1s));
        stats.put("msgPerSec10s",  String.format("%.1f", snap.msgPerSec10s));
        stats.put("msgPerSec60s",  String.format("%.1f", snap.msgPerSec60s));
        stats.put("topRooms",      toListOfMaps(snap.topRooms, "roomId"));
        stats.put("topUsers",      toListOfMaps(snap.topUsers, "userId"));

        // ── Assemble response ─────────────────────────────────────────────────
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status",    "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("sqs",       sqs);
        response.put("db",        db);
        response.put("stats",     stats);
        return response;
    }

    private static List<Map<String, Object>> toListOfMaps(
            List<Map.Entry<Integer, Long>> entries, String idKey) {
        return entries.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(idKey,   e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }
}
