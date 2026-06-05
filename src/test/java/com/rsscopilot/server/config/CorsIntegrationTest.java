package com.rsscopilot.server.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsIntegrationTest {

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
  void shouldAllowLocalWebClientPreflightWithoutBearerToken() throws Exception {
    mockMvc
        .perform(
            options("/api/auth/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:53221")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:53221"));
  }

  @Test
  void shouldKeepCorsHeadersWhenProtectedRequestIsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, "http://localhost:53221"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:53221"));
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-cors-", ".db");
    } catch (Exception exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }
}
