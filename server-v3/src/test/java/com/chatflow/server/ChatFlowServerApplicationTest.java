package com.chatflow.server;

import com.chatflow.server.sqs.SqsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Verifies that the Spring context starts cleanly with all A3 beans present.
 *
 * SqsPublisher is @MockBean so its @PostConstruct does not attempt to connect to AWS.
 * All other beans (MessageQueryService, MetricsController, DB pool) are real and
 * wired from the test application.properties that points to H2 in-memory DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatFlowServerApplicationTest {

    @MockBean
    private SqsPublisher sqsPublisher;

    @Test
    void contextLoads() {
        // If Spring context starts without errors, all A3 beans wired correctly
    }
}
