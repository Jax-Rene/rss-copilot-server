package com.rsscopilot.server.feed;

public class FeedSourceSummary {

  private long id;
  private String name;
  private String rssUrl;
  private String siteUrl;
  private String iconUrl;
  private String folder;
  private boolean enabled;
  private String lastFetchedAt;
  private boolean hasError;
  private String lastErrorAt;
  private String lastErrorMessage;
  private int unreadCount;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getRssUrl() {
    return rssUrl;
  }

  public void setRssUrl(String rssUrl) {
    this.rssUrl = rssUrl;
  }

  public String getSiteUrl() {
    return siteUrl;
  }

  public void setSiteUrl(String siteUrl) {
    this.siteUrl = siteUrl;
  }

  public String getIconUrl() {
    return iconUrl;
  }

  public void setIconUrl(String iconUrl) {
    this.iconUrl = iconUrl;
  }

  public String getFolder() {
    return folder;
  }

  public void setFolder(String folder) {
    this.folder = folder;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getLastFetchedAt() {
    return lastFetchedAt;
  }

  public void setLastFetchedAt(String lastFetchedAt) {
    this.lastFetchedAt = lastFetchedAt;
  }

  public boolean isHasError() {
    return hasError;
  }

  public void setHasError(boolean hasError) {
    this.hasError = hasError;
  }

  public String getLastErrorAt() {
    return lastErrorAt;
  }

  public void setLastErrorAt(String lastErrorAt) {
    this.lastErrorAt = lastErrorAt;
  }

  public String getLastErrorMessage() {
    return lastErrorMessage;
  }

  public void setLastErrorMessage(String lastErrorMessage) {
    this.lastErrorMessage = lastErrorMessage;
  }

  public int getUnreadCount() {
    return unreadCount;
  }

  public void setUnreadCount(int unreadCount) {
    this.unreadCount = unreadCount;
  }
}
