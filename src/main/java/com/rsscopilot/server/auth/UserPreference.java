package com.rsscopilot.server.auth;

public class UserPreference {

  private Long userId;
  private String themeMode;
  private String defaultLanguage;
  private String createdAt;
  private String updatedAt;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getThemeMode() {
    return themeMode;
  }

  public void setThemeMode(String themeMode) {
    this.themeMode = themeMode;
  }

  public String getDefaultLanguage() {
    return defaultLanguage;
  }

  public void setDefaultLanguage(String defaultLanguage) {
    this.defaultLanguage = defaultLanguage;
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
