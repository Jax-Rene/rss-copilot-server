package com.rsscopilot.server.setting;

public record SettingsResponse(
    AiSettingsResponse ai,
    AppearanceSettingsResponse appearance,
    FeedSettingsResponse feeds,
    AccountSettingsResponse account) {}
