package com.rsscopilot.server.setting;

import jakarta.validation.constraints.NotBlank;

public record FeedSettingsRequest(@NotBlank String defaultLanguage) {}
