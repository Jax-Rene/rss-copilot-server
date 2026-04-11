package com.rsscopilot.server.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

  @NotNull private final Auth auth = new Auth();

  @NotNull private final Refresh refresh = new Refresh();

  @NotNull private final Bootstrap bootstrap = new Bootstrap();

  @NotNull private final Ai ai = new Ai();

  public Auth getAuth() {
    return auth;
  }

  public Refresh getRefresh() {
    return refresh;
  }

  public Bootstrap getBootstrap() {
    return bootstrap;
  }

  public Ai getAi() {
    return ai;
  }

  public static class Auth {

    private long sessionTtlDays = 30;

    public long getSessionTtlDays() {
      return sessionTtlDays;
    }

    public void setSessionTtlDays(long sessionTtlDays) {
      this.sessionTtlDays = sessionTtlDays;
    }
  }

  public static class Refresh {

    @NotBlank private String cron = "0 0 * * * *";

    private int connectTimeoutSeconds = 10;

    private int readTimeoutSeconds = 20;

    public String getCron() {
      return cron;
    }

    public void setCron(String cron) {
      this.cron = cron;
    }

    public int getConnectTimeoutSeconds() {
      return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
      this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
      return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
      this.readTimeoutSeconds = readTimeoutSeconds;
    }
  }

  public static class Bootstrap {

    @NotNull private final DefaultUser defaultUser = new DefaultUser();

    public DefaultUser getDefaultUser() {
      return defaultUser;
    }
  }

  public static class DefaultUser {

    @NotBlank private String email = "demo@rsscopilot.local";

    @NotBlank private String password = "changeme123";

    @NotBlank private String displayName = "RSS Copilot Demo";

    private String apiKey;

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }
  }

  public static class Ai {

    @NotNull private final DeepSeek deepSeek = new DeepSeek();

    @NotNull private final Defaults defaults = new Defaults();

    public DeepSeek getDeepSeek() {
      return deepSeek;
    }

    public Defaults getDefaults() {
      return defaults;
    }
  }

  public static class DeepSeek {

    @NotBlank private String baseUrl = "https://api.deepseek.com";

    @NotBlank private String model = "deepseek-chat";

    private int connectTimeoutSeconds = 10;

    private int readTimeoutSeconds = 60;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getConnectTimeoutSeconds() {
      return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
      this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
      return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
      this.readTimeoutSeconds = readTimeoutSeconds;
    }
  }

  public static class Defaults {

    @NotBlank private String filterPrompt;

    @NotBlank private String summaryPrompt;

    @NotBlank private String translationPrompt;

    @NotBlank private String outputLanguage = "zh-CN";

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

    public String getOutputLanguage() {
      return outputLanguage;
    }

    public void setOutputLanguage(String outputLanguage) {
      this.outputLanguage = outputLanguage;
    }
  }
}
