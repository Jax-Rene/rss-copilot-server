package com.rsscopilot.server.setting;

import com.rsscopilot.server.auth.CurrentUser;
import com.rsscopilot.server.auth.UserAccount;
import com.rsscopilot.server.auth.UserAccountMapper;
import com.rsscopilot.server.auth.UserPreference;
import com.rsscopilot.server.auth.UserPreferenceMapper;
import com.rsscopilot.server.common.BadRequestException;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.NotFoundException;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SettingsService {

  private static final Set<String> SUPPORTED_THEME_MODES = Set.of("SYSTEM", "LIGHT", "DARK");
  private static final Set<String> SUPPORTED_AI_PROVIDERS = Set.of("DEEPSEEK");
  private static final String REFRESH_POLICY_DESCRIPTION = "固定每小时自动刷新一次";

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
        new FeedSettingsResponse(userPreference.getDefaultLanguage(), REFRESH_POLICY_DESCRIPTION),
        new AccountSettingsResponse(userAccount.getEmail(), userAccount.getDisplayName()));
  }

  @Transactional
  public AiSettingsResponse updateAiSettings(CurrentUser currentUser, AiSettingsRequest request) {
    AiPromptConfig aiPromptConfig = requireAiPrompt(currentUser.id());
    aiPromptConfig.setProvider(normalizeAiProvider(request.provider()));
    applyApiKeyUpdate(aiPromptConfig, request);
    aiPromptConfig.setFilterPrompt(request.filterPrompt());
    aiPromptConfig.setSummaryPrompt(request.summaryPrompt());
    aiPromptConfig.setTranslationPrompt(request.translationPrompt());
    aiPromptConfig.setAutoSummaryEnabled(request.autoSummaryEnabled());
    aiPromptConfig.setAutoTranslationEnabled(request.autoTranslationEnabled());
    aiPromptConfig.setOutputLanguage(
        normalizeLanguageTag(request.outputLanguage(), "outputLanguage"));
    aiPromptConfig.setUpdatedAt(InstantMapper.toText(Instant.now()));
    aiPromptConfigMapper.update(aiPromptConfig);
    return toAiResponse(aiPromptConfig);
  }

  @Transactional
  public AppearanceSettingsResponse updateAppearanceSettings(
      CurrentUser currentUser, AppearanceSettingsRequest request) {
    UserPreference userPreference = requirePreference(currentUser.id());
    String themeMode = normalizeThemeMode(request.themeMode());
    userPreference.setThemeMode(themeMode);
    userPreference.setUpdatedAt(InstantMapper.toText(Instant.now()));
    userPreferenceMapper.update(userPreference);
    return new AppearanceSettingsResponse(themeMode);
  }

  @Transactional
  public FeedSettingsResponse updateFeedSettings(
      CurrentUser currentUser, FeedSettingsRequest request) {
    String defaultLanguage = normalizeLanguageTag(request.defaultLanguage(), "defaultLanguage");
    UserPreference userPreference = requirePreference(currentUser.id());
    userPreference.setDefaultLanguage(defaultLanguage);
    userPreference.setUpdatedAt(InstantMapper.toText(Instant.now()));
    userPreferenceMapper.update(userPreference);

    AiPromptConfig aiPromptConfig = requireAiPrompt(currentUser.id());
    aiPromptConfig.setOutputLanguage(defaultLanguage);
    aiPromptConfig.setUpdatedAt(userPreference.getUpdatedAt());
    aiPromptConfigMapper.update(aiPromptConfig);

    return new FeedSettingsResponse(defaultLanguage, REFRESH_POLICY_DESCRIPTION);
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

  private String normalizeThemeMode(String themeMode) {
    String normalized = themeMode.trim().toUpperCase();
    if (!SUPPORTED_THEME_MODES.contains(normalized)) {
      throw new BadRequestException("themeMode must be SYSTEM, LIGHT, or DARK");
    }
    return normalized;
  }

  private String normalizeAiProvider(String provider) {
    String normalized = provider.trim().toUpperCase();
    if (!SUPPORTED_AI_PROVIDERS.contains(normalized)) {
      throw new BadRequestException("provider must be DEEPSEEK");
    }
    return normalized;
  }

  private String normalizeLanguageTag(String languageTag, String fieldName) {
    String normalized = languageTag.trim();
    if (!normalized.matches("(?i)^[a-z]{2,3}(-[a-z0-9]{2,8})*$")) {
      throw new BadRequestException(fieldName + " must be a BCP 47 language tag");
    }
    String[] parts = normalized.split("-");
    StringBuilder result = new StringBuilder(parts[0].toLowerCase());
    for (int index = 1; index < parts.length; index++) {
      result.append("-");
      result.append(parts[index].length() == 2 ? parts[index].toUpperCase() : parts[index]);
    }
    return result.toString();
  }

  private String normalizeApiKey(String apiKey) {
    return StringUtils.hasText(apiKey) ? apiKey.trim() : null;
  }

  private void applyApiKeyUpdate(AiPromptConfig aiPromptConfig, AiSettingsRequest request) {
    String nextApiKey = normalizeApiKey(request.apiKey());
    if (nextApiKey != null) {
      aiPromptConfig.setApiKey(nextApiKey);
      return;
    }
    if (request.clearApiKey()) {
      aiPromptConfig.setApiKey(null);
    }
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
