package com.chatflow.consumer.db;

import com.chatflow.consumer.model.QueueMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessageRepository.
 * JdbcTemplate is mocked — no real DB or Spring context needed.
 */
class MessageRepositoryTest {

    private JdbcTemplate     mockJdbc;
    private MessageRepository repo;

    @BeforeEach
    void setUp() {
        mockJdbc = mock(JdbcTemplate.class);
        repo     = new MessageRepository(mockJdbc);
    }

    // ── batchInsert() ─────────────────────────────────────────────────────────

    @Test
    void batchInsert_emptyList_returnsZeroAndSkipsJdbc() {
        int result = repo.batchInsert(List.of());
        assertEquals(0, result);
        verifyNoInteractions(mockJdbc);
    }

    @Test
    void batchInsert_singleMessage_callsJdbcWithCorrectSql() {
        when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        int result = repo.batchInsert(List.of(makeMessage("id-001")));

        assertEquals(1, result);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockJdbc).update(sqlCaptor.capture(), any(Object[].class));

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("INSERT INTO messages"), "SQL must start with INSERT INTO messages");
        assertTrue(sql.contains("ON CONFLICT (message_id) DO NOTHING"), "SQL must contain idempotency clause");
        // Single row → exactly one value tuple
        assertEquals(1, countOccurrences(sql, "(?,?,?,?,?,?,?,?,?)"));
    }

    @Test
    void batchInsert_threeMsgs_producesSingleStatementWithThreeTuples() {
        when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(3);

        repo.batchInsert(List.of(
                makeMessage("id-1"),
                makeMessage("id-2"),
                makeMessage("id-3")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockJdbc, times(1)).update(sqlCaptor.capture(), any(Object[].class));

        // Three value tuples in one statement — not three separate statements
        assertEquals(3, countOccurrences(sqlCaptor.getValue(), "(?,?,?,?,?,?,?,?,?)"));
    }

    @Test
    void batchInsert_paramCountMatchesBatchSize() {
        int size = 5;
        when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(size);

        List<QueueMessage> batch = List.of(
                makeMessage("a"), makeMessage("b"), makeMessage("c"),
                makeMessage("d"), makeMessage("e"));
        repo.batchInsert(batch);

        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(mockJdbc).update(anyString(), paramsCaptor.capture());
        assertEquals(size * 9, paramsCaptor.getValue().length,
                "Should be 9 params per row");
    }

    @Test
    void batchInsert_updatesMetricsCounters() {
        when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(2);

        repo.batchInsert(List.of(makeMessage("x1"), makeMessage("x2")));

        assertEquals(2, repo.getTotalInserted());
        assertEquals(1, repo.getTotalBatches());
    }

    @Test
    void batchInsert_duplicateReturnedAsZero_doesNotInflateInsertedCount() {
        // DB returns 1 row affected (1 new, 1 conflict skipped)
        when(mockJdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        repo.batchInsert(List.of(makeMessage("dup"), makeMessage("dup")));

        assertEquals(1, repo.getTotalInserted(), "Only rows actually inserted should be counted");
    }

    // ── parseRoomId() ─────────────────────────────────────────────────────────

    @Test
    void parseRoomId_zeroPadded_parsesCorrectly() {
        assertEquals(1,  MessageRepository.parseRoomId("01"));
        assertEquals(20, MessageRepository.parseRoomId("20"));
        assertEquals(5,  MessageRepository.parseRoomId("05"));
    }

    @Test
    void parseRoomId_null_returnsZero() {
        assertEquals(0, MessageRepository.parseRoomId(null));
    }

    @Test
    void parseRoomId_nonNumeric_returnsZero() {
        assertEquals(0, MessageRepository.parseRoomId("abc"));
    }

    // ── parseUserId() ─────────────────────────────────────────────────────────

    @Test
    void parseUserId_numericString_parsesCorrectly() {
        assertEquals(42,     MessageRepository.parseUserId("42"));
        assertEquals(100000, MessageRepository.parseUserId("100000"));
    }

    @Test
    void parseUserId_null_returnsZero() {
        assertEquals(0, MessageRepository.parseUserId(null));
    }

    // ── parseTimestamp() ──────────────────────────────────────────────────────

    @Test
    void parseTimestamp_iso8601_convertsToEpochMillis() {
        String iso = "2026-04-02T10:30:00.000Z";
        long expected = Instant.parse(iso).toEpochMilli();
        assertEquals(expected, MessageRepository.parseTimestamp(iso));
    }

    @Test
    void parseTimestamp_null_returnsFallback() {
        long before = System.currentTimeMillis();
        long result = MessageRepository.parseTimestamp(null);
        long after  = System.currentTimeMillis();
        assertTrue(result >= before && result <= after,
                "Null timestamp should fall back to current time");
    }

    @Test
    void parseTimestamp_invalid_returnsFallback() {
        long before = System.currentTimeMillis();
        long result = MessageRepository.parseTimestamp("not-a-timestamp");
        long after  = System.currentTimeMillis();
        assertTrue(result >= before && result <= after);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static QueueMessage makeMessage(String id) {
        QueueMessage m = new QueueMessage();
        m.setMessageId(id);
        m.setRoomId("05");
        m.setUserId("42");
        m.setUsername("alice");
        m.setMessage("hello");
        m.setMessageType("TEXT");
        m.setServerId("server-8080");
        m.setClientIp("10.0.0.1");
        m.setTimestamp("2026-04-02T10:30:00.000Z");
        return m;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx++; }
        return count;
    }
}
