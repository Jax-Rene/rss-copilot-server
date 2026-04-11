package com.rsscopilot.server.auth;

import com.rsscopilot.server.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserAccountMapper userAccountMapper;
  private final PasswordService passwordService;
  private final SessionService sessionService;

  public AuthService(
      UserAccountMapper userAccountMapper,
      PasswordService passwordService,
      SessionService sessionService) {
    this.userAccountMapper = userAccountMapper;
    this.passwordService = passwordService;
    this.sessionService = sessionService;
  }

  @Transactional
  public LoginResponse login(LoginRequest request) {
    UserAccount userAccount = userAccountMapper.findByEmail(request.email().trim().toLowerCase());
    if (userAccount == null
        || !passwordService.matches(request.password(), userAccount.getPasswordHash())) {
      throw new UnauthorizedException("email or password is incorrect");
    }
    String token = sessionService.createSession(userAccount);
    return new LoginResponse(token, AuthUserResponse.from(userAccount));
  }
}
