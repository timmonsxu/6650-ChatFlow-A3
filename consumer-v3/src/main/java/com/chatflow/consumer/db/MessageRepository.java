package com.chatflow.consumer.db;

import com.chatflow.consumer.model.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes multi-row VALUES batch INSERT into the messages table.
 *
 * A single SQL statement is built for the entire batch:
 *   INSERT INTO messages (...) VALUES (?,?,...),(?,?,...),... ON CONFLICT (message_id) DO NOTHING
 *
 * Why multi-row VALUES instead of JDBC batchUpdate()?
 *   batchUpdate() sends N individual prepared-statement executions.
 *   Multi-row VALUES is parsed and executed as ONE statement — one transaction,
 *   one WAL write, one round-trip.  Typically 3–5× faster on PostgreSQL.
 *
 * ON CONFLICT (message_id) DO NOTHING handles SQS at-least-once redelivery:
 *   duplicate messages are silently skipped without error or extra latency.
 */
@Repository
public class MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    private static final String INSERT_PREFIX =
            "INSERT INTO messages " +
            "(message_id, room_id, user_id, username, message, message_type, " +
            " server_id, client_ip, sent_at) VALUES ";

    private static final String INSERT_SUFFIX =
            " ON CONFLICT (message_id) DO NOTHING";

    // Number of ? placeholders per row (must match INSERT_PREFIX column list)
    private static final int PARAMS_PER_ROW = 9;

    private final JdbcTemplate jdbc;

    // Exposed for HealthController
    private final AtomicLong totalInserted = new AtomicLong(0);
    private final AtomicLong totalBatches  = new AtomicLong(0);

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts all messages in {@code batch} with a single SQL statement.
     *
     * @param batch non-empty list of messages to persist
     * @return number of rows actually inserted (duplicates excluded)
     */
    public int batchInsert(List<QueueMessage> batch) {
        if (batch.isEmpty()) return 0;

        // Build  (?,?,?,?,?,?,?,?,?),(?,?,?,...), ...
        StringBuilder sql = new StringBuilder(
                INSERT_PREFIX.length() + batch.size() * 22 + INSERT_SUFFIX.length());
        sql.append(INSERT_PREFIX);

        Object[] params = new Object[batch.size() * PARAMS_PER_ROW];
        int idx = 0;

        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append("(?,?,?,?,?,?,?,?,?)");

            QueueMessage m = batch.get(i);
            params[idx++] = m.getMessageId();
            params[idx++] = parseRoomId(m.getRoomId());
            params[idx++] = parseUserId(m.getUserId());
            params[idx++] = m.getUsername();
            params[idx++] = m.getMessage();
            params[idx++] = m.getMessageType();
            params[idx++] = m.getServerId();
            params[idx++] = m.getClientIp();
            params[idx++] = parseTimestamp(m.getTimestamp());
        }
        sql.append(INSERT_SUFFIX);

        int inserted = jdbc.update(sql.toString(), params);
        totalInserted.addAndGet(inserted);
        totalBatches.incrementAndGet();

        log.debug("Batch INSERT: submitted={} inserted={}", batch.size(), inserted);
        return inserted;
    }

    public long getTotalInserted() { return totalInserted.get(); }
    public long getTotalBatches()  { return totalBatches.get(); }

    // ── Type conversion helpers ───────────────────────────────────────────────

    /**
     * Parses a zero-padded room string like "01" or "20" to a short integer.
     * Falls back to 0 on null / non-numeric input.
     */
    static int parseRoomId(String roomId) {
        if (roomId == null || roomId.isBlank()) return 0;
        try {
            return Integer.parseInt(roomId.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable roomId '{}', defaulting to 0", roomId);
            return 0;
        }
    }

    /**
     * Parses a numeric userId string like "42" to an integer.
     * Falls back to 0 on null / non-numeric input.
     */
    static int parseUserId(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        try {
            return Integer.parseInt(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable userId '{}', defaulting to 0", userId);
            return 0;
        }
    }

    /**
     * Converts an ISO-8601 timestamp string to epoch millis.
     * Falls back to current time on null / unparseable input.
     */
    static long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            log.warn("Unparseable timestamp '{}', using current time", timestamp);
            return System.currentTimeMillis();
        }
    }
}
