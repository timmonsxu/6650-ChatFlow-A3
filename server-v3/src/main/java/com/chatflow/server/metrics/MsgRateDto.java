package com.chatflow.server.metrics;

/** Analytics A1 result: message count within one minute bucket. */
public record MsgRateDto(long minuteBucket, long messageCount) {}
