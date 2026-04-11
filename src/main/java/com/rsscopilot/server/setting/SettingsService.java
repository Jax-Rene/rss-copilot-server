package com.rsscopilot.server.setting;

import com.rsscopilot.server.auth.CurrentUser;
import com.rsscopilot.server.auth.UserAccount;
import com.rsscopilot.server.auth.UserAccountMapper;
import com.rsscopilot.server.auth.UserPreference;
import com.rsscopilot.server.auth.UserPreferenceMapper;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.NotFoundException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SettingsService {

  private final AiPromptConfigMapper aiPromptConfigMapper;
  private final UserPreferenceMapper userPreferenceMapper;
  private final UserAccountMapper userAccountMapper;

  public SettingsService(
      AiPromptConfigMapper aiPromptConfigMapper,
      UserPreferenceMapper userPreferenceMapper,
      UserAccountMapper userAccountMapper) {
    this.aiPromptConfigMapper = aiPromptConfigMapper;
    this.userPreferenceMapper = userPreferenceMapper;
    this.userAccountMapper = userAccountMapper;
  }

  @Transactional(readOnly = true)
  public SettingsResponse getSettings(CurrentUser currentUser) {
    UserAccount userAccount = requireUser(currentUser.id());
    AiPromptConfig aiPromptConfig = requireAiPrompt(currentUser.id());
    UserPreference userPreference = requirePreference(currentUser.id());
    return new SettingsResponse(
        toAiResponse(aiPromptConfig),
        new AppearanceSettingsResponse(userPreference.getThemeMode()),
        new FeedSettingsResponse(userPreference.getDefaultLanguage(), "固定每小时自动刷新一次"),
        new AccountSettingsResponse(userAccount.getEmail(), userAccount.getDisplayName()));
  }

  @Transactional
  public AiSettingsResponse updateAiSettings(CurrentUser currentUser, AiSettingsRequest request) {
    AiPromptConfig aiPromptConfig = requireAiPrompt(currentUser.id());
    aiPromptConfig.setProvider(request.provider().trim().toUpperCase());
    aiPromptConfig.setApiKey(normalizeApiKey(request.apiKey()));
    aiPromptConfig.setFilterPrompt(request.filterPrompt());
    aiPromptConfig.setSummaryPrompt(request.summaryPrompt());
    aiPromptConfig.setTranslationPrompt(request.translationPrompt());
    aiPromptConfig.setAutoSummaryEnabled(request.autoSummaryEnabled());
    aiPromptConfig.setAutoTranslationEnabled(request.autoTranslationEnabled());
    aiPromptConfig.setOutputLanguage(request.outputLanguage());
    aiPromptConfig.setUpdatedAt(InstantMapper.toText(Instant.now()));
    aiPromptConfigMapper.update(aiPromptConfig);
    return toAiResponse(aiPromptConfig);
  }

  @Transactional(readOnly = true)
  public AiPromptConfig getAiPromptConfig(long userId) {
    return requireAiPrompt(userId);
  }

  private UserAccount requireUser(long userId) {
    UserAccount userAccount = userAccountMapper.findById(userId);
    if (userAccount == null) {
      throw new NotFoundException("user not found");
    }
    return userAccount;
  }

  private UserPreference requirePreference(long userId) {
    UserPreference userPreference = userPreferenceMapper.findByUserId(userId);
    if (userPreference == null) {
      throw new NotFoundException("user preference not found");
    }
    return userPreference;
  }

  private AiPromptConfig requireAiPrompt(long userId) {
    AiPromptConfig aiPromptConfig = aiPromptConfigMapper.findByUserId(userId);
    if (aiPromptConfig == null) {
      throw new NotFoundException("ai prompt config not found");
    }
    return aiPromptConfig;
  }

  private AiSettingsResponse toAiResponse(AiPromptConfig aiPromptConfig) {
    return new AiSettingsResponse(
        aiPromptConfig.getProvider(),
        StringUtils.hasText(aiPromptConfig.getApiKey()),
        maskApiKey(aiPromptConfig.getApiKey()),
        aiPromptConfig.getFilterPrompt(),
        aiPromptConfig.getSummaryPrompt(),
        aiPromptConfig.getTranslationPrompt(),
        aiPromptConfig.isAutoSummaryEnabled(),
        aiPromptConfig.isAutoTranslationEnabled(),
        aiPromptConfig.getOutputLanguage());
  }

  private String normalizeApiKey(String apiKey) {
    return StringUtils.hasText(apiKey) ? apiKey.trim() : null;
  }

  private String maskApiKey(String apiKey) {
    if (!StringUtils.hasText(apiKey)) {
      return null;
    }
    String trimmed = apiKey.trim();
    if (trimmed.length() <= 5) {
      return "***";
    }
    return trimmed.substring(0, 3) + "***" + trimmed.substring(trimmed.length() - 3);
  }
}
