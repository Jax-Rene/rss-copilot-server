package com.rsscopilot.server.common;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthIntegrationTest {

  private static final Path DB_PATH = createDbPath();

  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(DB_PATH);
  }

  @Test
  void shouldReturnHealthWithoutBearerToken() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("rss-copilot-server"))
        .andExpect(jsonPath("$.apiVersion").value(1))
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.serverTime").isNotEmpty());
  }

  @Test
  void shouldStillProtectOtherApiEndpoints() throws Exception {
    mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturnNotFoundForUnknownApiEndpoints() throws Exception {
    String token = login();

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("api endpoint not found: /api/me"));
  }

  private String login() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "email": "demo@rsscopilot.local",
                          "password": "changeme123"
                        }
                        """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return TestJson.parse(response).path("token").asText();
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-health-", ".db");
    } catch (Exception exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }
}
