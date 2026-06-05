package com.rsscopilot.server.feed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsscopilot.server.support.TestJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EntryPaginationIntegrationTest {

  private static final Path DB_PATH = createDbPath();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
    registry.add("app.bootstrap.default-user.email", () -> "demo@example.com");
    registry.add("app.bootstrap.default-user.password", () -> "pass123456");
    registry.add("app.bootstrap.default-user.api-key", () -> "sk-bootstrap");
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(DB_PATH);
  }

  @Test
  void shouldPageEntriesWithIsoEquivalentCursorTimestamps() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String rssUrl = "https://pagination.example/" + suffix + ".xml";

    long sourceId = insertSource(userId, rssUrl, "Pagination Source " + suffix);
    long halfSecondId =
        insertEntry(
            userId,
            sourceId,
            "Half Second Newest " + suffix,
            "half-" + suffix,
            "2026-04-10T10:00:00.500Z");
    long wholeSecondLowId =
        insertEntry(
            userId,
            sourceId,
            "Whole Second Low " + suffix,
            "whole-low-" + suffix,
            "2026-04-10T10:00:00Z");
    long wholeSecondHighId =
        insertEntry(
            userId,
            sourceId,
            "Whole Second High " + suffix,
            "whole-high-" + suffix,
            "2026-04-10T10:00:00Z");

    try {
      MvcResult firstPage =
          mockMvc
              .perform(
                  get("/api/entries")
                      .header("Authorization", "Bearer " + token)
                      .param("view", "all")
                      .param("sourceId", Long.toString(sourceId))
                      .param("limit", "1"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.items[0].id").value(halfSecondId))
              .andExpect(jsonPath("$.hasMore").value(true))
              .andReturn();

      String firstCursorPublishedAt =
          TestJson.parse(firstPage.getResponse().getContentAsString())
              .path("nextCursor")
              .path("publishedAt")
              .asText();

      mockMvc
          .perform(
              get("/api/entries")
                  .header("Authorization", "Bearer " + token)
                  .param("view", "all")
                  .param("sourceId", Long.toString(sourceId))
                  .param("limit", "1")
                  .param("beforePublishedAt", firstCursorPublishedAt)
                  .param("beforeId", Long.toString(halfSecondId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].id").value(wholeSecondHighId))
          .andExpect(jsonPath("$.hasMore").value(true));

      mockMvc
          .perform(
              get("/api/entries")
                  .header("Authorization", "Bearer " + token)
                  .param("view", "all")
                  .param("sourceId", Long.toString(sourceId))
                  .param("limit", "1")
                  .param("beforePublishedAt", "2026-04-10T10:00:00.000Z")
                  .param("beforeId", Long.toString(wholeSecondHighId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].id").value(wholeSecondLowId))
          .andExpect(jsonPath("$.hasMore").value(false));
    } finally {
      jdbcTemplate.update("DELETE FROM feed_source WHERE id = ? AND user_id = ?", sourceId, userId);
    }
  }

  @Test
  void shouldRejectPartialOrInvalidEntryListCursors() throws Exception {
    String token = login();

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("beforePublishedAt", "2026-04-10T10:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid pagination cursor"));

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("beforeId", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid pagination cursor"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("beforePublishedAt", "not-a-date")
                .param("beforeId", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid pagination cursor"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("beforePublishedAt", "2026-04-10T10:00:00Z")
                .param("beforeId", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid pagination cursor"));
  }

  @Test
  void shouldRejectInvalidEntryListLimits() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    long sourceId =
        insertSource(
            userId,
            "https://pagination.example/limit-" + suffix + ".xml",
            "Pagination Limit " + suffix);

    try {
      mockMvc
          .perform(
              get("/api/entries").header("Authorization", "Bearer " + token).param("limit", "0"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("invalid limit"));

      mockMvc
          .perform(
              get("/api/feed-sources/{sourceId}/entries", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .param("limit", "-1"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("invalid limit"));
    } finally {
      jdbcTemplate.update("DELETE FROM feed_source WHERE id = ? AND user_id = ?", sourceId, userId);
    }
  }

  @Test
  void shouldRejectMalformedNumericRequestParameters() throws Exception {
    String token = login();

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("limit", "many"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("invalid request parameter: limit"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("beforeId", "last"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("invalid request parameter: beforeId"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("sourceId", "primary"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("invalid request parameter: sourceId"));
  }

  @Test
  void shouldRejectInvalidSourceFilters() throws Exception {
    String token = login();

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("sourceId", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid source id"));

    mockMvc
        .perform(
            get("/api/feed-sources/{sourceId}/entries", -1)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid source id"));

    mockMvc
        .perform(
            post("/api/entries/read-all")
                .header("Authorization", "Bearer " + token)
                .param("sourceId", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid source id"));
  }

  @Test
  void shouldRejectMissingSourceFilters() throws Exception {
    String token = login();

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("sourceId", "999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("feed source not found"));

    mockMvc
        .perform(
            post("/api/entries/read-all")
                .header("Authorization", "Bearer " + token)
                .param("sourceId", "999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("feed source not found"));
  }

  private String login() throws Exception {
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

    return TestJson.parse(loginResult.getResponse().getContentAsString()).path("token").asText();
  }

  private long insertSource(Long userId, String rssUrl, String name) {
    jdbcTemplate.update(
        """
            INSERT INTO feed_source(
                user_id, name, rss_url, site_url, icon_url, folder, enabled, status, etag,
                last_modified, last_fetched_at, last_error_at, last_error_message, created_at, updated_at
            )
            VALUES(?, ?, ?, NULL, NULL, 'Pagination', 1, 'IDLE', NULL, NULL, NULL, NULL, NULL,
                   '2026-04-10T09:00:00Z', '2026-04-10T09:00:00Z')
            """,
        userId,
        name,
        rssUrl);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM feed_source WHERE user_id = ? AND rss_url = ?", Long.class, userId, rssUrl);
  }

  private long insertEntry(
      Long userId, long sourceId, String title, String externalId, String publishedAt) {
    jdbcTemplate.update(
        """
            INSERT INTO feed_entry(
                user_id, source_id, external_id, title, author, link, published_at, language,
                foreign_language, cover_image_url, rss_summary, content_html, content_text,
                content_fetched, filter_status, filter_is_noise, filter_reason, summary_status,
                summary_text, translation_status, translation_language, translation_segments_json,
                created_at, updated_at
            )
            VALUES(
                ?, ?, ?, ?, NULL, ?, ?, 'zh', 0, NULL, 'Summary', '<p>Body</p>', 'Body',
                1, 'SUCCESS', 0, NULL, 'SUCCESS', 'Summary', 'SKIPPED', NULL, NULL,
                '2026-04-10T09:00:00Z', '2026-04-10T09:00:00Z'
            )
            """,
        userId,
        sourceId,
        externalId,
        title,
        "https://articles.example/" + externalId,
        publishedAt);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM feed_entry WHERE user_id = ? AND external_id = ?",
        Long.class,
        userId,
        externalId);
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-entry-pagination-", ".db");
    } catch (IOException exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }
}
