package com.rsscopilot.server.auth;

public class UserSession {

  private Long id;
  private Long userId;
  private String tokenHash;
  private String expiresAt;
  private String lastSeenAt;
  private String createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public String getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(String expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(String lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }
}
