package com.rsscopilot.server.setting;

import jakarta.validation.constraints.NotBlank;

public record AiSettingsRequest(
    String apiKey,
    boolean clearApiKey,
    @NotBlank String filterPrompt,
    @NotBlank String summaryPrompt,
    @NotBlank String translationPrompt,
    boolean autoSummaryEnabled,
    boolean autoTranslationEnabled,
    @NotBlank String outputLanguage,
    @NotBlank String provider) {}
