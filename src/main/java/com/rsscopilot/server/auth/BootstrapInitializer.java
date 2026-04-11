package com.rsscopilot.server.auth;

import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.config.AppProperties;
import com.rsscopilot.server.setting.AiPromptConfig;
import com.rsscopilot.server.setting.AiPromptConfigMapper;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapInitializer implements ApplicationRunner {

  private final AppProperties appProperties;
  private final UserAccountMapper userAccountMapper;
  private final UserPreferenceMapper userPreferenceMapper;
  private final AiPromptConfigMapper aiPromptConfigMapper;
  private final PasswordService passwordService;

  public BootstrapInitializer(
      AppProperties appProperties,
      UserAccountMapper userAccountMapper,
      UserPreferenceMapper userPreferenceMapper,
      AiPromptConfigMapper aiPromptConfigMapper,
      PasswordService passwordService) {
    this.appProperties = appProperties;
    this.userAccountMapper = userAccountMapper;
    this.userPreferenceMapper = userPreferenceMapper;
    this.aiPromptConfigMapper = aiPromptConfigMapper;
    this.passwordService = passwordService;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    AppProperties.DefaultUser defaultUser = appProperties.getBootstrap().getDefaultUser();
    UserAccount existingUser =
        userAccountMapper.findByEmail(defaultUser.getEmail().trim().toLowerCase());
    if (existingUser == null) {
      existingUser = createDefaultUser(defaultUser);
    }
    ensurePreference(existingUser.getId());
    ensureAiPrompt(existingUser.getId(), defaultUser.getApiKey());
  }

  private UserAccount createDefaultUser(AppProperties.DefaultUser defaultUser) {
    Instant now = Instant.now();
    UserAccount userAccount = new UserAccount();
    userAccount.setEmail(defaultUser.getEmail().trim().toLowerCase());
    userAccount.setPasswordHash(passwordService.hash(defaultUser.getPassword()));
    userAccount.setDisplayName(defaultUser.getDisplayName());
    userAccount.setStatus("ACTIVE");
    userAccount.setCreatedAt(InstantMapper.toText(now));
    userAccount.setUpdatedAt(InstantMapper.toText(now));
    userAccountMapper.insert(userAccount);
    return userAccount;
  }

  private void ensurePreference(long userId) {
    if (userPreferenceMapper.findByUserId(userId) != null) {
      return;
    }
    Instant now = Instant.now();
    UserPreference userPreference = new UserPreference();
    userPreference.setUserId(userId);
    userPreference.setThemeMode("SYSTEM");
    userPreference.setDefaultLanguage("zh-CN");
    userPreference.setCreatedAt(InstantMapper.toText(now));
    userPreference.setUpdatedAt(InstantMapper.toText(now));
    userPreferenceMapper.insert(userPreference);
  }

  private void ensureAiPrompt(long userId, String apiKey) {
    if (aiPromptConfigMapper.findByUserId(userId) != null) {
      return;
    }
    Instant now = Instant.now();
    AiPromptConfig aiPromptConfig = new AiPromptConfig();
    aiPromptConfig.setUserId(userId);
    aiPromptConfig.setProvider("DEEPSEEK");
    aiPromptConfig.setApiKey(apiKey);
    aiPromptConfig.setFilterPrompt(appProperties.getAi().getDefaults().getFilterPrompt());
    aiPromptConfig.setSummaryPrompt(appProperties.getAi().getDefaults().getSummaryPrompt());
    aiPromptConfig.setTranslationPrompt(appProperties.getAi().getDefaults().getTranslationPrompt());
    aiPromptConfig.setAutoSummaryEnabled(true);
    aiPromptConfig.setAutoTranslationEnabled(true);
    aiPromptConfig.setOutputLanguage(appProperties.getAi().getDefaults().getOutputLanguage());
    aiPromptConfig.setCreatedAt(InstantMapper.toText(now));
    aiPromptConfig.setUpdatedAt(InstantMapper.toText(now));
    aiPromptConfigMapper.insert(aiPromptConfig);
  }
}
