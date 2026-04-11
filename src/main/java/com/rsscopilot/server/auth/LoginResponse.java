package com.rsscopilot.server.auth;

public record LoginResponse(String token, AuthUserResponse user) {}
