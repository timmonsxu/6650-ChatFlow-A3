package com.chatflow.server.controller;

import com.chatflow.server.sqs.SqsPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${app.server-id}")
    private String serverId;

    private final SqsPublisher sqsPublisher;

    public HealthController(SqsPublisher sqsPublisher) {
        this.sqsPublisher = sqsPublisher;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "serverId", serverId,
                "timestamp", Instant.now().toString(),
                "dlqSize", sqsPublisher.getDlqSize(),
                "dlqRetried", sqsPublisher.getDlqRetried(),
                "dlqDropped", sqsPublisher.getDlqDropped()
        );
    }
}
