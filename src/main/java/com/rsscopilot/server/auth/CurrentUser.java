package com.rsscopilot.server.auth;

public record CurrentUser(long id, String email, String displayName) {}
