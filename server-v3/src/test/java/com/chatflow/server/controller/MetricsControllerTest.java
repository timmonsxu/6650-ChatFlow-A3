package com.chatflow.server.controller;

import com.chatflow.server.metrics.MessageQueryService;
import com.chatflow.server.metrics.*;
import com.chatflow.server.sqs.SqsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests for MetricsController.
 *
 * MessageQueryService and SqsPublisher are mocked — no real DB or AWS needed.
 * Tests focus on HTTP contract: status codes, JSON structure, query param handling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SqsPublisher sqsPublisher;

    @MockBean
    private MessageQueryService queryService;

    // ── Stub data ─────────────────────────────────────────────────────────────

    private static final MessageDto MSG = new MessageDto(
            "uuid-1", 1, 42, "alice", "hello", "TEXT", 1743638400000L);

    private void stubAllQueries() {
        when(queryService.getMessagesInRoom(anyInt(), anyLong(), anyLong()))
                .thenReturn(List.of(MSG));
        when(queryService.getUserHistory(anyInt(), anyLong(), anyLong()))
                .thenReturn(List.of(MSG));
        when(queryService.countActiveUsers(anyLong(), anyLong()))
                .thenReturn(42L);
        when(queryService.getUserRooms(anyInt()))
                .thenReturn(List.of(new RoomActivityDto(1, 1743638400000L)));
        when(queryService.getMessagesPerMinute(anyLong(), anyLong()))
                .thenReturn(List.of(new MsgRateDto(1743638400000L, 10L)));
        when(queryService.getTopActiveUsers(anyInt()))
                .thenReturn(List.of(new UserRankDto(42, "alice", 100L)));
        when(queryService.getTopActiveRooms(anyInt()))
                .thenReturn(List.of(new RoomRankDto(1, 200L)));
        when(queryService.getTotalMessages())
                .thenReturn(500L);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void metricsEndpoint_returnsOk() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    void metricsEndpoint_responseHasAllTopLevelKeys() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testSummary").exists())
                .andExpect(jsonPath("$.coreQueries").exists())
                .andExpect(jsonPath("$.analytics").exists());
    }

    @Test
    void metricsEndpoint_testSummaryHasRequiredFields() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.testSummary.totalMessages").value(500))
                .andExpect(jsonPath("$.testSummary.queryTimeMs").exists())
                .andExpect(jsonPath("$.testSummary.startTime").exists())
                .andExpect(jsonPath("$.testSummary.endTime").exists());
    }

    @Test
    void metricsEndpoint_coreQueriesHasAllFour() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.coreQueries.roomMessages").exists())
                .andExpect(jsonPath("$.coreQueries.userHistory").exists())
                .andExpect(jsonPath("$.coreQueries.activeUsers").exists())
                .andExpect(jsonPath("$.coreQueries.userRooms").exists());
    }

    @Test
    void metricsEndpoint_analyticsHasAllFour() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.analytics.messagesPerMinute").exists())
                .andExpect(jsonPath("$.analytics.topActiveUsers").exists())
                .andExpect(jsonPath("$.analytics.topActiveRooms").exists())
                .andExpect(jsonPath("$.analytics.totalMessages").exists());
    }

    @Test
    void metricsEndpoint_activeUsers_matchesMockValue() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.coreQueries.activeUsers.activeUsers").value(42));
    }

    @Test
    void metricsEndpoint_topActiveUsersReturned() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.analytics.topActiveUsers[0].userId").value(42))
                .andExpect(jsonPath("$.analytics.topActiveUsers[0].username").value("alice"))
                .andExpect(jsonPath("$.analytics.topActiveUsers[0].messageCount").value(100));
    }

    @Test
    void metricsEndpoint_topActiveRoomsReturned() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.analytics.topActiveRooms[0].roomId").value(1))
                .andExpect(jsonPath("$.analytics.topActiveRooms[0].messageCount").value(200));
    }

    @Test
    void metricsEndpoint_roomMessagesCountCorrect() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics"))
                .andExpect(jsonPath("$.coreQueries.roomMessages.count").value(1))
                .andExpect(jsonPath("$.coreQueries.roomMessages.messages[0].messageId").value("uuid-1"));
    }

    @Test
    void metricsEndpoint_acceptsQueryParams_roomAndUser() throws Exception {
        stubAllQueries();
        mockMvc.perform(get("/metrics")
                .param("roomId", "5")
                .param("userId", "100")
                .param("topN",   "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testSummary.queryRoomId").value(5))
                .andExpect(jsonPath("$.testSummary.queryUserId").value(100));
    }

    @Test
    void metricsEndpoint_acceptsExplicitTimeRange() throws Exception {
        stubAllQueries();
        long start = 1743638400000L;
        long end   = 1743638460000L;
        mockMvc.perform(get("/metrics")
                .param("startTime", String.valueOf(start))
                .param("endTime",   String.valueOf(end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testSummary.startTime").value(start))
                .andExpect(jsonPath("$.testSummary.endTime").value(end));
    }

    @Test
    void metricsEndpoint_dbDown_returns503() throws Exception {
        // Simulate DB failure
        when(queryService.getTotalMessages())
                .thenThrow(new RuntimeException("Connection refused"));
        // Other queries can be stubbed or also throw — controller catches any exception
        when(queryService.getMessagesInRoom(anyInt(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(queryService.getUserHistory(anyInt(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(queryService.countActiveUsers(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(queryService.getUserRooms(anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(queryService.getMessagesPerMinute(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(queryService.getTopActiveUsers(anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));
        when(queryService.getTopActiveRooms(anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }
}
