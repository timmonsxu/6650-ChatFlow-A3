package com.chatflow.server.metrics;

/**
 * Represents a single message row returned by core queries Q1 and Q2.
 * Kept intentionally small — only fields the API consumer needs.
 */
public record MessageDto(
        String messageId,
        int    roomId,
        int    userId,
        String username,
        String message,
        String messageType,
        long   sentAt
) {}
