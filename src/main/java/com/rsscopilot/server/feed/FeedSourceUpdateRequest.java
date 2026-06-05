package com.rsscopilot.server.feed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FeedSourceUpdateRequest(
    @NotBlank String name,
    @NotBlank String rssUrl,
    String iconUrl,
    String folder,
    @NotNull Boolean enabled) {}
