package com.chatflow.server.metrics;

import com.chatflow.server.sqs.SqsPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MessageQueryService using real H2 in-memory DB.
 *
 * Schema is created by test-schema.sql via spring.sql.init.mode=always.
 * Test data is inserted before each test and cleared after.
 *
 * Tests verify:
 *   - All 4 core queries return correct results
 *   - All 4 analytics queries return correct results
 *   - Edge cases: empty result sets, boundary conditions
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageQueryServiceTest {

    @MockBean
    private SqsPublisher sqsPublisher;  // prevent AWS connection attempt

    @Autowired
    private MessageQueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    // Fixed epoch-millis epoch for test data
    private static final long T0 = 1_743_638_400_000L; // 2026-04-03 00:00:00 UTC
    private static final long T1 = T0 + 60_000L;       // +1 minute
    private static final long T2 = T0 + 120_000L;      // +2 minutes
    private static final long T3 = T0 + 180_000L;      // +3 minutes

    @BeforeEach
    void insertTestData() {
        jdbc.execute("DELETE FROM messages");

        // Room 1, user 42 — 3 messages spread across T0..T2
        insert("msg-r1-u42-1", 1, 42, "alice", "hello",   "TEXT",  T0);
        insert("msg-r1-u42-2", 1, 42, "alice", "world",   "TEXT",  T1);
        insert("msg-r1-u42-3", 1, 42, "alice", "bye",     "LEAVE", T2);

        // Room 1, user 99 — 2 messages
        insert("msg-r1-u99-1", 1, 99, "bob",   "hi",      "JOIN",  T0);
        insert("msg-r1-u99-2", 1, 99, "bob",   "sup",     "TEXT",  T1);

        // Room 2, user 42 — 1 message (different room for Q4)
        insert("msg-r2-u42-1", 2, 42, "alice", "room2",   "TEXT",  T3);

        // Room 3, user 77 — 1 message
        insert("msg-r3-u77-1", 3, 77, "carol", "room3",   "TEXT",  T0);
    }

    // ── Core Query 1: messages in room + time range ───────────────────────────

    @Test
    void q1_allMessagesInRoom1_fullRange() {
        List<MessageDto> msgs = queryService.getMessagesInRoom(1, T0, T3);
        assertEquals(5, msgs.size(), "Room 1 has 5 messages");
    }

    @Test
    void q1_messagesInRoom1_narrowRange() {
        // Only messages exactly at T0 and T1
        List<MessageDto> msgs = queryService.getMessagesInRoom(1, T0, T1);
        assertEquals(4, msgs.size(), "T0..T1 in room 1: 2 from alice + 2 from bob");
    }

    @Test
    void q1_orderedBySentAt() {
        List<MessageDto> msgs = queryService.getMessagesInRoom(1, T0, T3);
        for (int i = 1; i < msgs.size(); i++) {
            assertTrue(msgs.get(i).sentAt() >= msgs.get(i - 1).sentAt(),
                    "Messages must be ordered by sent_at ascending");
        }
    }

    @Test
    void q1_emptyRoom_returnsEmptyList() {
        List<MessageDto> msgs = queryService.getMessagesInRoom(20, T0, T3);
        assertTrue(msgs.isEmpty());
    }

    @Test
    void q1_noMessagesInTimeRange_returnsEmptyList() {
        List<MessageDto> msgs = queryService.getMessagesInRoom(1, T3 + 1, T3 + 60_000L);
        assertTrue(msgs.isEmpty());
    }

    // ── Core Query 2: user message history ───────────────────────────────────

    @Test
    void q2_userHistory_acrossAllRooms() {
        // alice (user 42) has messages in rooms 1 and 2 — 4 total
        List<MessageDto> msgs = queryService.getUserHistory(42, T0, T3);
        assertEquals(4, msgs.size());
    }

    @Test
    void q2_userHistoryInDateRange() {
        // alice in T0..T1 only: 2 messages in room 1
        List<MessageDto> msgs = queryService.getUserHistory(42, T0, T1);
        assertEquals(2, msgs.size());
    }

    @Test
    void q2_unknownUser_returnsEmptyList() {
        List<MessageDto> msgs = queryService.getUserHistory(99999, T0, T3);
        assertTrue(msgs.isEmpty());
    }

    @Test
    void q2_resultContainsCorrectUserId() {
        List<MessageDto> msgs = queryService.getUserHistory(42, T0, T3);
        assertTrue(msgs.stream().allMatch(m -> m.userId() == 42));
    }

    // ── Core Query 3: count active users in time window ───────────────────────

    @Test
    void q3_allUsersInFullRange() {
        // users 42, 99, 77 → 3 distinct
        long count = queryService.countActiveUsers(T0, T3);
        assertEquals(3, count);
    }

    @Test
    void q3_narrowWindow_fewerUsers() {
        // Only messages at T3 → just user 42 (room 2 msg)
        long count = queryService.countActiveUsers(T3, T3);
        assertEquals(1, count);
    }

    @Test
    void q3_emptyTimeRange_returnsZero() {
        long count = queryService.countActiveUsers(T3 + 1_000L, T3 + 2_000L);
        assertEquals(0, count);
    }

    // ── Core Query 4: rooms user participated in ──────────────────────────────

    @Test
    void q4_aliceParticipatedInTwoRooms() {
        List<RoomActivityDto> rooms = queryService.getUserRooms(42);
        assertEquals(2, rooms.size(), "alice participated in rooms 1 and 2");
    }

    @Test
    void q4_sortedByLastActiveDescending() {
        List<RoomActivityDto> rooms = queryService.getUserRooms(42);
        // Room 2 has message at T3 (most recent), room 1 has T2 as latest
        assertEquals(2, rooms.get(0).roomId(), "Room 2 should be first (most recent activity)");
        assertEquals(T3, rooms.get(0).lastActive());
    }

    @Test
    void q4_unknownUser_returnsEmptyList() {
        List<RoomActivityDto> rooms = queryService.getUserRooms(99999);
        assertTrue(rooms.isEmpty());
    }

    // ── Analytics 1: messages per minute ─────────────────────────────────────

    @Test
    void a1_messagesPerMinute_correctBuckets() {
        List<MsgRateDto> rates = queryService.getMessagesPerMinute(T0, T3);
        // T0 bucket: 3 messages (r1u42, r1u99, r3u77)
        // T1 bucket: 2 messages (r1u42, r1u99)
        // T2 bucket: 1 message  (r1u42 LEAVE)
        // T3 bucket: 1 message  (r2u42)
        assertEquals(4, rates.size(), "Should have 4 minute buckets");
    }

    @Test
    void a1_bucketsOrderedByTime() {
        List<MsgRateDto> rates = queryService.getMessagesPerMinute(T0, T3);
        for (int i = 1; i < rates.size(); i++) {
            assertTrue(rates.get(i).minuteBucket() > rates.get(i - 1).minuteBucket());
        }
    }

    @Test
    void a1_t0Bucket_hasThreeMessages() {
        List<MsgRateDto> rates = queryService.getMessagesPerMinute(T0, T3);
        MsgRateDto t0Bucket = rates.stream()
                .filter(r -> r.minuteBucket() == (T0 / 60000) * 60000)
                .findFirst()
                .orElseThrow();
        assertEquals(3, t0Bucket.messageCount());
    }

    // ── Analytics 2: top active users ────────────────────────────────────────

    @Test
    void a2_topUsers_aliceIsFirst() {
        List<UserRankDto> top = queryService.getTopActiveUsers(10);
        // alice has 4 msgs, bob has 2, carol has 1
        assertEquals(42, top.get(0).userId());
        assertEquals("alice", top.get(0).username());
        assertEquals(4, top.get(0).messageCount());
    }

    @Test
    void a2_topN_limitsResults() {
        List<UserRankDto> top = queryService.getTopActiveUsers(2);
        assertEquals(2, top.size());
    }

    @Test
    void a2_sortedByCountDescending() {
        List<UserRankDto> top = queryService.getTopActiveUsers(10);
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i).messageCount() <= top.get(i - 1).messageCount());
        }
    }

    // ── Analytics 3: top active rooms ────────────────────────────────────────

    @Test
    void a3_topRooms_room1IsFirst() {
        List<RoomRankDto> top = queryService.getTopActiveRooms(10);
        // room 1: 5 msgs, room 2: 1 msg, room 3: 1 msg
        assertEquals(1, top.get(0).roomId());
        assertEquals(5, top.get(0).messageCount());
    }

    @Test
    void a3_topN_limitsResults() {
        List<RoomRankDto> top = queryService.getTopActiveRooms(2);
        assertEquals(2, top.size());
    }

    @Test
    void a3_sortedByCountDescending() {
        List<RoomRankDto> top = queryService.getTopActiveRooms(10);
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i).messageCount() <= top.get(i - 1).messageCount());
        }
    }

    // ── Analytics 4: total message count ─────────────────────────────────────

    @Test
    void a4_totalMessages_matchesInsertedCount() {
        long total = queryService.getTotalMessages();
        assertEquals(7, total, "7 messages were inserted in @BeforeEach");
    }

    @Test
    void a4_afterAdditionalInsert_countIncreases() {
        insert("extra-msg", 5, 1, "dave", "extra", "TEXT", T0);
        assertEquals(8, queryService.getTotalMessages());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void insert(String msgId, int roomId, int userId, String username,
                        String message, String type, long sentAt) {
        jdbc.update(
            "INSERT INTO messages (message_id, room_id, user_id, username, message, message_type, sent_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            msgId, (short) roomId, userId, username, message, type, sentAt);
    }
}
