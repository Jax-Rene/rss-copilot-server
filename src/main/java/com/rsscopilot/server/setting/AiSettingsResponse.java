package com.rsscopilot.server.setting;

public record AiSettingsResponse(
    String provider,
    boolean configured,
    String apiKeyMasked,
    String filterPrompt,
    String summaryPrompt,
    String translationPrompt,
    boolean autoSummaryEnabled,
    boolean autoTranslationEnabled,
    String outputLanguage) {}
