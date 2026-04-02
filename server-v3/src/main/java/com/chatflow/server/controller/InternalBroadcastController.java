package com.chatflow.server.controller;

import com.chatflow.server.handler.ChatWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Internal REST endpoint called by the Consumer to trigger WebSocket broadcast.
 * Not exposed through ALB — Consumer calls each Server directly via private IP.
 *
 * POST /internal/broadcast/{roomId}
 * Body: QueueMessage JSON
 * Returns: 200 OK immediately — broadcast is submitted to a per-room single-thread
 * executor and executed asynchronously.
 *
 * Per-room ordering guarantee:
 *   Each room has its own single-threaded executor (ExecutorService with 1 thread).
 *   Messages for the same room are always processed in submission order, preserving
 *   the FIFO ordering guaranteed by SQS. Different rooms execute concurrently.
 *
 * Bounded queue per room (capacity 500):
 *   If the queue for a room is full, new tasks are silently dropped (DiscardPolicy).
 *   This prevents unbounded memory growth under sustained load.
 */
@RestController
@RequestMapping("/internal")
public class InternalBroadcastController {

    private static final Logger log = LoggerFactory.getLogger(InternalBroadcastController.class);
    private static final int NUM_ROOMS = 20;
    private static final int QUEUE_PER_ROOM = 500;

    private final ChatWebSocketHandler wsHandler;

    /**
     * One single-threaded executor per room (index 0 = room "01", index 19 = room "20").
     * Single-threaded guarantees FIFO execution within each room.
     */
    private ExecutorService[] roomExecutors;

    public InternalBroadcastController(ChatWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @PostConstruct
    public void init() {
        roomExecutors = new ExecutorService[NUM_ROOMS];
        for (int i = 0; i < NUM_ROOMS; i++) {
            roomExecutors[i] = new ThreadPoolExecutor(
                    1, 1,
                    0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_PER_ROOM),
                    new ThreadPoolExecutor.DiscardPolicy());
        }
        log.info("InternalBroadcastController: {} per-room single-thread executors initialised", NUM_ROOMS);
    }

    @PostMapping("/broadcast/{roomId}")
    public ResponseEntity<Map<String, Object>> broadcast(
            @PathVariable String roomId,
            @RequestBody String messageJson) {

        int idx = roomIndex(roomId);
        ExecutorService executor = (idx >= 0 && idx < NUM_ROOMS)
                ? roomExecutors[idx]
                : roomExecutors[0]; // fallback for unexpected roomId

        executor.submit(() -> {
            try {
                wsHandler.broadcastToRoom(roomId, messageJson);
            } catch (Exception e) {
                log.warn("Async broadcast failed for room {}: {}", roomId, e.getMessage());
            }
        });

        return ResponseEntity.ok(Map.of("status", "OK", "roomId", roomId));
    }

    @PreDestroy
    public void shutdown() {
        if (roomExecutors == null) return;
        for (ExecutorService ex : roomExecutors) {
            ex.shutdown();
        }
        for (ExecutorService ex : roomExecutors) {
            try {
                ex.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Converts a zero-padded roomId string (e.g. "05") to a 0-based array index (4).
     * Returns -1 if the roomId cannot be parsed.
     */
    private int roomIndex(String roomId) {
        try {
            return Integer.parseInt(roomId) - 1;
        } catch (NumberFormatException e) {
            log.warn("Cannot parse roomId '{}', using fallback executor", roomId);
            return -1;
        }
    }
}
