package com.chatflow.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calls POST /internal/broadcast/{roomId} on every known Server-v2 instance IN PARALLEL.
 *
 * All calls are fired simultaneously via CompletableFuture.sendAsync().
 * Total time = max(individual call times) instead of sum(individual call times).
 *
 * Delivery guarantee: broadcast() throws BroadcastException if ALL servers fail.
 * If at least one server succeeds, the message is considered delivered (best-effort
 * multi-server broadcast). The caller (SqsConsumerService) should only delete the
 * SQS message when broadcast() returns without throwing.
 */
@Component
public class BroadcastClient {

    private static final Logger log = LoggerFactory.getLogger(BroadcastClient.class);

    private final List<String> serverUrls;
    private final HttpClient httpClient;

    public BroadcastClient(@Value("${app.broadcast.server-urls}") String serverUrlsCsv) {
        this.serverUrls = Arrays.asList(serverUrlsCsv.split(","));
        // Short connect timeout so unavailable servers fail fast
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
        log.info("BroadcastClient initialised with servers: {}", serverUrls);
    }

    /**
     * Broadcasts to all servers IN PARALLEL. Throws BroadcastException if ALL
     * servers fail so the caller can skip SQS deleteMessage and allow retry.
     *
     * @param roomId      zero-padded room id, e.g. "05"
     * @param messageJson raw JSON string of the QueueMessage
     * @throws BroadcastException if every server call failed
     */
    public void broadcast(String roomId, String messageJson) throws BroadcastException {
        AtomicBoolean anySuccess = new AtomicBoolean(false);

        List<CompletableFuture<Void>> futures = serverUrls.stream()
                .map(baseUrl -> {
                    String url = baseUrl.trim() + "/internal/broadcast/" + roomId;
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(messageJson))
                            .build();

                    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenAccept(response -> {
                                if (response.statusCode() == 200) {
                                    anySuccess.set(true);
                                } else {
                                    log.warn("Broadcast to {} returned status {}: {}",
                                            url, response.statusCode(), response.body());
                                }
                            })
                            .exceptionally(e -> {
                                // Log but do not rethrow — we track success via anySuccess flag
                                log.error("Broadcast to {} failed: {}", url, e.getMessage());
                                return null;
                            });
                })
                .toList();

        // Wait for all parallel calls to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // If no server succeeded, throw so caller does NOT delete the SQS message
        if (!anySuccess.get()) {
            throw new BroadcastException(
                    "Broadcast to room " + roomId + " failed on all " + serverUrls.size() + " server(s)");
        }
    }

    /** Thrown when every server call in a broadcast attempt fails. */
    public static class BroadcastException extends Exception {
        public BroadcastException(String message) { super(message); }
    }
}
