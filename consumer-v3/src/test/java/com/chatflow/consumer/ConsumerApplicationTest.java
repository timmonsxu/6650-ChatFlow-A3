package com.chatflow.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Verifies the Spring context loads cleanly with all A3 beans present.
 *
 * SqsConsumerService is @MockBean so @PostConstruct does not attempt to
 * connect to AWS SQS.  All other beans (DbWriterService, StatsAggregatorService,
 * MessageRepository, HealthController) are real and wired from the test
 * application.properties that points to H2 in-memory DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsumerApplicationTest {

    @MockBean
    private SqsConsumerService sqsConsumerService;

    @Test
    void contextLoads() {
        // If Spring context starts without errors, all A3 beans wired correctly
    }
}
