package com.rsscopilot.server.auth;

public record AuthUserResponse(long id, String email, String displayName) {

  public static AuthUserResponse from(CurrentUser currentUser) {
    return new AuthUserResponse(currentUser.id(), currentUser.email(), currentUser.displayName());
  }

  public static AuthUserResponse from(UserAccount userAccount) {
    return new AuthUserResponse(
        userAccount.getId(), userAccount.getEmail(), userAccount.getDisplayName());
  }
}
