package com.rsscopilot.server.setting;

public class AiPromptConfig {

  private Long userId;
  private String provider;
  private String apiKey;
  private String filterPrompt;
  private String summaryPrompt;
  private String translationPrompt;
  private boolean autoSummaryEnabled;
  private boolean autoTranslationEnabled;
  private String outputLanguage;
  private String createdAt;
  private String updatedAt;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getFilterPrompt() {
    return filterPrompt;
  }

  public void setFilterPrompt(String filterPrompt) {
    this.filterPrompt = filterPrompt;
  }

  public String getSummaryPrompt() {
    return summaryPrompt;
  }

  public void setSummaryPrompt(String summaryPrompt) {
    this.summaryPrompt = summaryPrompt;
  }

  public String getTranslationPrompt() {
    return translationPrompt;
  }

  public void setTranslationPrompt(String translationPrompt) {
    this.translationPrompt = translationPrompt;
  }

  public boolean isAutoSummaryEnabled() {
    return autoSummaryEnabled;
  }

  public void setAutoSummaryEnabled(boolean autoSummaryEnabled) {
    this.autoSummaryEnabled = autoSummaryEnabled;
  }

  public boolean isAutoTranslationEnabled() {
    return autoTranslationEnabled;
  }

  public void setAutoTranslationEnabled(boolean autoTranslationEnabled) {
    this.autoTranslationEnabled = autoTranslationEnabled;
  }

  public String getOutputLanguage() {
    return outputLanguage;
  }

  public void setOutputLanguage(String outputLanguage) {
    this.outputLanguage = outputLanguage;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
