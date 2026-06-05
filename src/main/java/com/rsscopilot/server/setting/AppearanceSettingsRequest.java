package com.rsscopilot.server.setting;

import jakarta.validation.constraints.NotBlank;

public record AppearanceSettingsRequest(@NotBlank String themeMode) {}
