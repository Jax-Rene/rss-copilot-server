package com.rsscopilot.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsscopilot.server.support.TestJson;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

  private static final Path DB_PATH = createDbPath();

  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
    registry.add("app.bootstrap.default-user.email", () -> "demo@example.com");
    registry.add("app.bootstrap.default-user.password", () -> "pass123456");
    registry.add("app.bootstrap.default-user.display-name", () -> "Demo User");
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(DB_PATH);
  }

  @Test
  void shouldLoginAndGetCurrentUser() throws Exception {
    MvcResult loginResult =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "email": "demo@example.com",
                          "password": "pass123456"
                        }
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value("demo@example.com"))
            .andReturn();

    String token =
        TestJson.parse(loginResult.getResponse().getContentAsString()).path("token").asText();

    mockMvc
        .perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("demo@example.com"))
        .andExpect(jsonPath("$.displayName").value("Demo User"));
  }

  @Test
  void shouldRejectWrongPassword() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "email": "demo@example.com",
                          "password": "wrong-password"
                        }
                        """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void shouldDescribeRequestValidationErrors() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "not-an-email",
                      "password": "pass123456"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value(containsString("email")))
        .andExpect(jsonPath("$.message").value(containsString("well-formed email address")));
  }

  @Test
  void shouldRejectUnreadableJsonRequestBodies() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo@example.com\","))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("request body is invalid"));
  }

  @Test
  void shouldLogoutAndInvalidateSession() throws Exception {
    MvcResult loginResult =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "email": "demo@example.com",
                          "password": "pass123456"
                        }
                        """))
            .andExpect(status().isOk())
            .andReturn();

    String token =
        TestJson.parse(loginResult.getResponse().getContentAsString()).path("token").asText();

    mockMvc
        .perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    MvcResult meResult =
        mockMvc
            .perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andReturn();

    assertThat(TestJson.parse(meResult.getResponse().getContentAsString()).path("code").asText())
        .isEqualTo("UNAUTHORIZED");
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-auth-", ".db");
    } catch (Exception exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }
}
