package com.rsscopilot.server.auth;

import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

  private final UserSessionMapper userSessionMapper;
  private final UserAccountMapper userAccountMapper;
  private final long sessionTtlDays;
  private final SecureRandom secureRandom = new SecureRandom();

  public SessionService(
      UserSessionMapper userSessionMapper,
      UserAccountMapper userAccountMapper,
      com.rsscopilot.server.config.AppProperties appProperties) {
    this.userSessionMapper = userSessionMapper;
    this.userAccountMapper = userAccountMapper;
    this.sessionTtlDays = appProperties.getAuth().getSessionTtlDays();
  }

  @Transactional
  public String createSession(UserAccount userAccount) {
    String token = generateToken();
    Instant now = Instant.now();
    UserSession userSession = new UserSession();
    userSession.setUserId(userAccount.getId());
    userSession.setTokenHash(hashToken(token));
    userSession.setCreatedAt(InstantMapper.toText(now));
    userSession.setLastSeenAt(InstantMapper.toText(now));
    userSession.setExpiresAt(InstantMapper.toText(now.plus(sessionTtlDays, ChronoUnit.DAYS)));
    userSessionMapper.insert(userSession);
    return token;
  }

  @Transactional
  public CurrentUser authenticate(String token) {
    UserSession userSession = userSessionMapper.findByTokenHash(hashToken(token));
    if (userSession == null) {
      throw new UnauthorizedException("invalid session");
    }
    Instant expiresAt = InstantMapper.fromText(userSession.getExpiresAt());
    if (expiresAt == null || expiresAt.isBefore(Instant.now())) {
      userSessionMapper.deleteByTokenHash(userSession.getTokenHash());
      throw new UnauthorizedException("session expired");
    }
    UserAccount userAccount = userAccountMapper.findById(userSession.getUserId());
    if (userAccount == null) {
      throw new UnauthorizedException("user not found");
    }
    Instant now = Instant.now();
    userSession.setLastSeenAt(InstantMapper.toText(now));
    userSession.setExpiresAt(InstantMapper.toText(now.plus(sessionTtlDays, ChronoUnit.DAYS)));
    userSessionMapper.updateActivity(userSession);
    return new CurrentUser(
        userAccount.getId(), userAccount.getEmail(), userAccount.getDisplayName());
  }

  @Transactional
  public void invalidate(String token) {
    userSessionMapper.deleteByTokenHash(hashToken(token));
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
      byte[] digest = messageDigest.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("sha-256 algorithm unavailable", exception);
    }
  }
}
