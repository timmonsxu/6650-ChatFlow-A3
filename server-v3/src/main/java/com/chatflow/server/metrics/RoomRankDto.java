package com.chatflow.server.metrics;

/** Analytics A3 result: a room ranked by total message count. */
public record RoomRankDto(int roomId, long messageCount) {}
