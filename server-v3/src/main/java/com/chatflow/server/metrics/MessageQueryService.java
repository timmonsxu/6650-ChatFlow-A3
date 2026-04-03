package com.chatflow.server.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Executes all 8 metrics queries against PostgreSQL.
 *
 * Uses NamedParameterJdbcTemplate for readable SQL with named placeholders.
 * All queries use prepared statements (auto-handled by Spring JDBC).
 *
 * Index coverage (from database/indexes.sql):
 *   Q1 → idx_room_time      (room_id, sent_at)
 *   Q2 → idx_user_time      (user_id, sent_at)
 *   Q3 → idx_sent_at_uid    (sent_at, user_id)  — index-only scan
 *   Q4 → idx_user_room_time (user_id, room_id, sent_at) — index-only scan
 *   A1 → idx_sent_at_uid    (sent_at leading column)
 *   A2 → sequential scan / idx_user_time (small table after test)
 *   A3 → sequential scan / idx_room_time
 *   A4 → COUNT(*) — fast on small post-test table
 */
@Service
public class MessageQueryService {

    private static final Logger log = LoggerFactory.getLogger(MessageQueryService.class);
    private static final int MAX_ROWS = 1000; // cap result sets for API safety

    private final NamedParameterJdbcTemplate jdbc;

    public MessageQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Core Query 1 ─────────────────────────────────────────────────────────
    // Messages in a room within a time range, ordered by sent_at.
    // Performance target: < 100ms for 1000 messages.
    // Index: idx_room_time (room_id, sent_at) — range scan on time after room seek.

    public List<MessageDto> getMessagesInRoom(int roomId, long startTime, long endTime) {
        String sql =
            "SELECT message_id, room_id, user_id, username, message, message_type, sent_at " +
            "FROM messages " +
            "WHERE room_id = :roomId AND sent_at BETWEEN :start AND :end " +
            "ORDER BY sent_at " +
            "LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("roomId", roomId)
                .addValue("start",  startTime)
                .addValue("end",    endTime)
                .addValue("limit",  MAX_ROWS);

        return jdbc.query(sql, params, (rs, rowNum) -> new MessageDto(
                rs.getString("message_id"),
                rs.getInt("room_id"),
                rs.getInt("user_id"),
                rs.getString("username"),
                rs.getString("message"),
                rs.getString("message_type"),
                rs.getLong("sent_at")
        ));
    }

    // ── Core Query 2 ─────────────────────────────────────────────────────────
    // All messages from a user, optionally filtered by date range.
    // Performance target: < 200ms.
    // Index: idx_user_time (user_id, sent_at).

    public List<MessageDto> getUserHistory(int userId, long startTime, long endTime) {
        String sql =
            "SELECT message_id, room_id, user_id, username, message, message_type, sent_at " +
            "FROM messages " +
            "WHERE user_id = :userId AND sent_at BETWEEN :start AND :end " +
            "ORDER BY sent_at " +
            "LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("start",  startTime)
                .addValue("end",    endTime)
                .addValue("limit",  MAX_ROWS);

        return jdbc.query(sql, params, (rs, rowNum) -> new MessageDto(
                rs.getString("message_id"),
                rs.getInt("room_id"),
                rs.getInt("user_id"),
                rs.getString("username"),
                rs.getString("message"),
                rs.getString("message_type"),
                rs.getLong("sent_at")
        ));
    }

    // ── Core Query 3 ─────────────────────────────────────────────────────────
    // Distinct user count within a time window.
    // Performance target: < 500ms.
    // Index: idx_sent_at_uid (sent_at, user_id) — index-only scan, no heap access.

    public long countActiveUsers(long startTime, long endTime) {
        String sql =
            "SELECT COUNT(DISTINCT user_id) " +
            "FROM messages " +
            "WHERE sent_at BETWEEN :start AND :end";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", startTime)
                .addValue("end",   endTime);

        Long result = jdbc.queryForObject(sql, params, Long.class);
        return result != null ? result : 0L;
    }

    // ── Core Query 4 ─────────────────────────────────────────────────────────
    // Rooms a user participated in, sorted by most recent activity.
    // Performance target: < 50ms.
    // Index: idx_user_room_time (user_id, room_id, sent_at) — full index-only scan.

    public List<RoomActivityDto> getUserRooms(int userId) {
        String sql =
            "SELECT room_id, MAX(sent_at) AS last_active " +
            "FROM messages " +
            "WHERE user_id = :userId " +
            "GROUP BY room_id " +
            "ORDER BY last_active DESC";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId);

        return jdbc.query(sql, params, (rs, rowNum) -> new RoomActivityDto(
                rs.getInt("room_id"),
                rs.getLong("last_active")
        ));
    }

    // ── Analytics 1 ──────────────────────────────────────────────────────────
    // Message count bucketed by minute, for throughput graphs.
    // Bucket formula: integer division truncates to minute boundary in epoch-millis.

    public List<MsgRateDto> getMessagesPerMinute(long startTime, long endTime) {
        String sql =
            "SELECT (sent_at / 60000) * 60000 AS minute_bucket, COUNT(*) AS msg_count " +
            "FROM messages " +
            "WHERE sent_at BETWEEN :start AND :end " +
            "GROUP BY minute_bucket " +
            "ORDER BY minute_bucket";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", startTime)
                .addValue("end",   endTime);

        return jdbc.query(sql, params, (rs, rowNum) -> new MsgRateDto(
                rs.getLong("minute_bucket"),
                rs.getLong("msg_count")
        ));
    }

    // ── Analytics 2 ──────────────────────────────────────────────────────────
    // Top-N most active users by message count (all time).
    // MAX(username) picks one username per userId — consistent in load tests
    // where each userId always sends with the same username.

    public List<UserRankDto> getTopActiveUsers(int n) {
        String sql =
            "SELECT user_id, MAX(username) AS username, COUNT(*) AS msg_count " +
            "FROM messages " +
            "GROUP BY user_id " +
            "ORDER BY msg_count DESC " +
            "LIMIT :n";

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("n", n);

        return jdbc.query(sql, params, (rs, rowNum) -> new UserRankDto(
                rs.getInt("user_id"),
                rs.getString("username"),
                rs.getLong("msg_count")
        ));
    }

    // ── Analytics 3 ──────────────────────────────────────────────────────────
    // Top-N most active rooms by message count (all time).

    public List<RoomRankDto> getTopActiveRooms(int n) {
        String sql =
            "SELECT room_id, COUNT(*) AS msg_count " +
            "FROM messages " +
            "GROUP BY room_id " +
            "ORDER BY msg_count DESC " +
            "LIMIT :n";

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("n", n);

        return jdbc.query(sql, params, (rs, rowNum) -> new RoomRankDto(
                rs.getInt("room_id"),
                rs.getLong("msg_count")
        ));
    }

    // ── Analytics 4 ──────────────────────────────────────────────────────────
    // Total message count in the entire table.

    public long getTotalMessages() {
        String sql = "SELECT COUNT(*) FROM messages";
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return result != null ? result : 0L;
    }
}
