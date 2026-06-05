package com.rsscopilot.server.setting;

import com.rsscopilot.server.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {

  private final SettingsService settingsService;

  public SettingsController(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping("/api/settings")
  public SettingsResponse getSettings(CurrentUser currentUser) {
    return settingsService.getSettings(currentUser);
  }

  @PutMapping("/api/settings/ai")
  public AiSettingsResponse updateAiSettings(
      CurrentUser currentUser, @Valid @RequestBody AiSettingsRequest request) {
    return settingsService.updateAiSettings(currentUser, request);
  }

  @PutMapping("/api/settings/appearance")
  public AppearanceSettingsResponse updateAppearanceSettings(
      CurrentUser currentUser, @Valid @RequestBody AppearanceSettingsRequest request) {
    return settingsService.updateAppearanceSettings(currentUser, request);
  }

  @PutMapping("/api/settings/feeds")
  public FeedSettingsResponse updateFeedSettings(
      CurrentUser currentUser, @Valid @RequestBody FeedSettingsRequest request) {
    return settingsService.updateFeedSettings(currentUser, request);
  }
}
