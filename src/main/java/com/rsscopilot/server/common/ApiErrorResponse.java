package com.rsscopilot.server.common;

import java.time.Instant;

public record ApiErrorResponse(String code, String message, Instant timestamp) {}
