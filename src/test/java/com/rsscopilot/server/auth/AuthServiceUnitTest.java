package com.rsscopilot.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rsscopilot.server.common.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceUnitTest {

  @Mock private UserAccountMapper userAccountMapper;
  @Mock private PasswordService passwordService;
  @Mock private SessionService sessionService;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService = new AuthService(userAccountMapper, passwordService, sessionService);
  }

  @Test
  void shouldLoginSuccessfully() {
    UserAccount userAccount = new UserAccount();
    userAccount.setId(7L);
    userAccount.setEmail("demo@example.com");
    userAccount.setDisplayName("Demo");
    userAccount.setPasswordHash("encoded-password");

    when(userAccountMapper.findByEmail("demo@example.com")).thenReturn(userAccount);
    when(passwordService.matches("pass123456", "encoded-password")).thenReturn(true);
    when(sessionService.createSession(userAccount)).thenReturn("token-123");

    LoginResponse response = authService.login(new LoginRequest("Demo@Example.com", "pass123456"));

    assertThat(response.token()).isEqualTo("token-123");
    assertThat(response.user().email()).isEqualTo("demo@example.com");
    assertThat(response.user().displayName()).isEqualTo("Demo");
    verify(sessionService).createSession(userAccount);
  }

  @Test
  void shouldRejectWhenPasswordIsIncorrect() {
    UserAccount userAccount = new UserAccount();
    userAccount.setPasswordHash("encoded-password");

    when(userAccountMapper.findByEmail("demo@example.com")).thenReturn(userAccount);
    when(passwordService.matches("wrong-pass", "encoded-password")).thenReturn(false);

    assertThatThrownBy(() -> authService.login(new LoginRequest("demo@example.com", "wrong-pass")))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage("email or password is incorrect");
  }

  @Test
  void shouldRejectWhenUserDoesNotExist() {
    when(userAccountMapper.findByEmail(any())).thenReturn(null);

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("missing@example.com", "pass123456")))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage("email or password is incorrect");
  }
}
