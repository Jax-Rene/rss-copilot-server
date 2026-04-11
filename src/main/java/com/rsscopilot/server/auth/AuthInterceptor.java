package com.rsscopilot.server.auth;

import com.rsscopilot.server.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

public class AuthInterceptor implements HandlerInterceptor {

  private final SessionService sessionService;

  public AuthInterceptor(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String authorization = request.getHeader("Authorization");
    if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
      throw new UnauthorizedException("missing bearer token");
    }
    String token = authorization.substring("Bearer ".length()).trim();
    if (!StringUtils.hasText(token)) {
      throw new UnauthorizedException("missing bearer token");
    }
    CurrentUser currentUser = sessionService.authenticate(token);
    request.setAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE, currentUser);
    return true;
  }
}
