package com.rsscopilot.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  private final AuthService authService;
  private final SessionService sessionService;

  public AuthController(AuthService authService, SessionService sessionService) {
    this.authService = authService;
    this.sessionService = sessionService;
  }

  @PostMapping("/api/auth/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  @GetMapping("/api/auth/me")
  public AuthUserResponse me(CurrentUser currentUser) {
    return AuthUserResponse.from(currentUser);
  }

  @PostMapping("/api/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @RequestHeader("Authorization") String authorization, HttpServletRequest request) {
    String token = authorization.substring("Bearer ".length()).trim();
    sessionService.invalidate(token);
    request.removeAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE);
  }
}
