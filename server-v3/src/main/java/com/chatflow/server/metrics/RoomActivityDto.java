package com.chatflow.server.metrics;

/** Q4 result: a room a user participated in, plus their most recent activity time. */
public record RoomActivityDto(int roomId, long lastActive) {}
