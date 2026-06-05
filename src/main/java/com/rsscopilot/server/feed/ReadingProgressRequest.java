package com.rsscopilot.server.feed;

import jakarta.validation.constraints.NotNull;

public record ReadingProgressRequest(@NotNull Double progress) {}
