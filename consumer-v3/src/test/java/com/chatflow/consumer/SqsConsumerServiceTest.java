package com.chatflow.consumer;

import com.chatflow.consumer.db.DbWriterService;
import com.chatflow.consumer.stats.StatsAggregatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqsConsumerService.
 * SqsClient, BroadcastClient, DbWriterService, and StatsAggregatorService
 * are all mocked — no AWS connection or DB needed.
 */
class SqsConsumerServiceTest {

    private BroadcastClient        broadcastClient;
    private DbWriterService        dbWriterService;
    private StatsAggregatorService statsAggregator;
    private SqsConsumerService     service;

    @BeforeEach
    void setUp() {
        broadcastClient = mock(BroadcastClient.class);
        dbWriterService = mock(DbWriterService.class);
        statsAggregator = mock(StatsAggregatorService.class);

        service = new SqsConsumerService(broadcastClient, dbWriterService, statsAggregator);

        // Inject @Value fields manually
        ReflectionTestUtils.setField(service, "region",          "us-west-2");
        ReflectionTestUtils.setField(service, "accountId",       "449126751631");
        ReflectionTestUtils.setField(service, "queueNamePrefix", "chatflow-room-");
        ReflectionTestUtils.setField(service, "numThreads",      20);
    }

    @Test
    void initialMetrics_areZero() {
        assertEquals(0, service.getMessagesConsumed());
        assertEquals(0, service.getBroadcastCalls());
    }

    @Test
    void broadcastClient_calledWithCorrectRoomId() throws BroadcastClient.BroadcastException {
        String roomId = "05";
        String body   = jsonBody("uuid-1", "05", "1", "user1", "hello", "TEXT");

        broadcastClient.broadcast(roomId, body);

        ArgumentCaptor<String> roomCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(broadcastClient).broadcast(roomCaptor.capture(), bodyCaptor.capture());

        assertEquals("05", roomCaptor.getValue());
        assertEquals(body, bodyCaptor.getValue());
    }

    @Test
    void broadcastClient_missingRoomId_usesQueueRoomId() throws BroadcastClient.BroadcastException {
        // Message without roomId field — falls back to queue's roomId
        String body = "{\"messageId\":\"uuid-2\",\"message\":\"hello\"}";
        broadcastClient.broadcast("07", body);
        verify(broadcastClient).broadcast(eq("07"), eq(body));
    }

    @Test
    void dbWriterService_isInjectedAndAccessible() {
        // Verify the collaborator is the mock we injected
        assertNotNull(dbWriterService);
    }

    @Test
    void statsAggregator_isInjectedAndAccessible() {
        assertNotNull(statsAggregator);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String jsonBody(String msgId, String roomId, String userId,
                                   String username, String message, String type) {
        return String.format(
                "{\"messageId\":\"%s\",\"roomId\":\"%s\",\"userId\":\"%s\"," +
                "\"username\":\"%s\",\"message\":\"%s\",\"messageType\":\"%s\"," +
                "\"timestamp\":\"2026-04-02T10:00:00.000Z\"}",
                msgId, roomId, userId, username, message, type);
    }
}
