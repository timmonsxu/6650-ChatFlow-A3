package com.chatflow.server.metrics;

/** Analytics A2 result: a user ranked by total message count. */
public record UserRankDto(int userId, String username, long messageCount) {}
