package com.rsscopilot.server.feed;

import jakarta.validation.constraints.NotBlank;

public record FeedSourceUpdateRequest(
    @NotBlank String name, @NotBlank String rssUrl, String iconUrl, boolean enabled) {}
