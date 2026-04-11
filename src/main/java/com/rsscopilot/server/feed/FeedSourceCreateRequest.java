package com.rsscopilot.server.feed;

import jakarta.validation.constraints.NotBlank;

public record FeedSourceCreateRequest(@NotBlank String rssUrl) {}
