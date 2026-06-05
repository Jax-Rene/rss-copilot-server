package com.rsscopilot.server.common;

import java.time.Instant;

public record HealthResponse(String service, int apiVersion, String status, Instant serverTime) {}
