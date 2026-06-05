package com.rsscopilot.server.sync;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsscopilot.server.support.TestJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
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
class SyncIntegrationTest {

  private static final Path DB_PATH = createDbPath();
  private static final MockWebServer MOCK_WEB_SERVER = createServer();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
    registry.add("app.bootstrap.default-user.email", () -> "demo@example.com");
    registry.add("app.bootstrap.default-user.password", () -> "pass123456");
    registry.add("app.bootstrap.default-user.api-key", () -> "sk-bootstrap");
    registry.add(
        "app.ai.deepseek.base-url", () -> MOCK_WEB_SERVER.url("/").toString().replaceAll("/$", ""));
  }

  @AfterAll
  static void cleanup() throws Exception {
    MOCK_WEB_SERVER.shutdown();
    Files.deleteIfExists(DB_PATH);
  }

  @Test
  void shouldReturnBootstrapAndIncrementalChanges() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "rssUrl": "%s/feed.xml"
                        }
                        """
                        .formatted(MOCK_WEB_SERVER.url("").toString().replaceAll("/$", ""))))
        .andExpect(status().isCreated());

    mockMvc
        .perform(post("/api/feed-sources/refresh").header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted());

    Awaitility.await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                mockMvc
                    .perform(
                        get("/api/entries")
                            .header("Authorization", "Bearer " + token)
                            .param("view", "feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1)));

    mockMvc
        .perform(get("/api/sync/bootstrap").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources.length()").value(1))
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].filterStatus").exists())
        .andExpect(jsonPath("$.entries[0].summaryStatus").exists())
        .andExpect(jsonPath("$.entries[0].translationStatus").exists())
        .andExpect(jsonPath("$.settings.ai.provider").value("DEEPSEEK"));

    mockMvc
        .perform(
            get("/api/sync/changes")
                .header("Authorization", "Bearer " + token)
                .param("since", "1970-01-01T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources.length()").value(1))
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].filterStatus").exists())
        .andExpect(jsonPath("$.entries[0].summaryStatus").exists())
        .andExpect(jsonPath("$.entries[0].translationStatus").exists())
        .andExpect(jsonPath("$.deletedSourceIds.length()").value(0))
        .andExpect(jsonPath("$.settings.ai.provider").value("DEEPSEEK"));
  }

  @Test
  void shouldBoundIncrementalChangesByReturnedServerTime() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String pastTimestamp = "2000-01-01T00:00:00Z";
    String futureTimestamp = "2999-01-01T00:00:00Z";
    String includedSourceName = "Included Sync Source " + suffix;
    String includedEntryTitle = "Included Sync Entry " + suffix;
    String futureSourceName = "Future Sync Source " + suffix;
    String futureEntryTitle = "Future Sync Entry " + suffix;
    String includedRssUrl = "https://included.example/" + suffix;
    String futureRssUrl = "https://future.example/" + suffix;

    Long futureSourceId = null;
    try {
      long includedSourceId =
          insertSource(userId, includedSourceName, includedRssUrl, pastTimestamp);
      insertEntry(
          userId, includedSourceId, includedEntryTitle, "included-" + suffix, pastTimestamp);
      futureSourceId = insertSource(userId, futureSourceName, futureRssUrl, futureTimestamp);
      insertEntry(userId, futureSourceId, futureEntryTitle, "future-" + suffix, futureTimestamp);
      jdbcTemplate.update(
          """
              INSERT INTO sync_tombstone(user_id, entity_type, entity_id, deleted_at)
              VALUES(?, 'feed_source', ?, ?)
              """,
          userId,
          futureSourceId,
          futureTimestamp);

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", "1970-01-01T00:00:00Z"))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      assertThat(arrayContainsText(body.path("sources"), "name", includedSourceName)).isTrue();
      assertThat(arrayContainsText(body.path("entries"), "title", includedEntryTitle)).isTrue();
      assertThat(arrayContainsText(body.path("sources"), "name", futureSourceName)).isFalse();
      assertThat(arrayContainsText(body.path("entries"), "title", futureEntryTitle)).isFalse();
      assertThat(arrayContainsLong(body.path("deletedSourceIds"), futureSourceId)).isFalse();
    } finally {
      if (futureSourceId != null) {
        jdbcTemplate.update(
            "DELETE FROM sync_tombstone WHERE user_id = ? AND entity_id = ?",
            userId,
            futureSourceId);
      }
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id IN (?, ?)",
          userId,
          "included-" + suffix,
          "future-" + suffix);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url IN (?, ?)",
          userId,
          includedRssUrl,
          futureRssUrl);
    }
  }

  @Test
  void shouldSyncSourceCanonicalUrlAfterRefreshRedirect() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    String oldTimestamp = "2026-04-10T00:00:00Z";
    String since = "2026-04-10T00:00:01Z";
    String originalUrl = baseUrl + "/sync-moving-feed.xml";
    String canonicalUrl = baseUrl + "/sync-moved/final-feed.xml";
    long sourceId = insertSource(userId, "Moving Sync Source", originalUrl, oldTimestamp);

    try {
      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () -> {
                MvcResult changesResult =
                    mockMvc
                        .perform(
                            get("/api/sync/changes")
                                .header("Authorization", "Bearer " + token)
                                .param("since", since))
                        .andExpect(status().isOk())
                        .andReturn();

                JsonNode body =
                    TestJson.parse(
                        changesResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
                JsonNode syncedSource =
                    objectWithText(body.path("sources"), "name", "Moved Sync Feed");
                assertThat(syncedSource).isNotNull();
                assertThat(syncedSource.path("rssUrl").asText()).isEqualTo(canonicalUrl);
                assertThat(syncedSource.path("hasError").asBoolean()).isFalse();
              });
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id = ?",
          userId,
          "sync-moved-entry");
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url IN (?, ?)",
          userId,
          originalUrl,
          canonicalUrl);
    }
  }

  @Test
  void shouldReturnDeletedSourceIdsForIncrementalSync() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-07T00:00:00Z";
    String since = "2026-04-07T00:00:01Z";
    String sourceName = "Deleted Sync Source " + suffix;
    String entryTitle = "Deleted Sync Entry " + suffix;
    String rssUrl = "https://deleted-sync.example/" + suffix;
    String externalId = "deleted-sync-" + suffix;

    Long sourceId = null;
    try {
      sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long entryId = insertEntry(userId, sourceId, entryTitle, externalId, oldTimestamp);
      jdbcTemplate.update(
          """
              INSERT INTO user_entry_state(user_id, entry_id, is_read, read_at, updated_at)
              VALUES(?, ?, 1, ?, ?)
              """,
          userId,
          entryId,
          oldTimestamp,
          oldTimestamp);
      jdbcTemplate.update(
          """
              INSERT INTO ai_result_summary(
                  user_id, entry_id, model, status, summary_text, raw_response, created_at, updated_at
              )
              VALUES(?, ?, 'test-model', 'SUCCESS', 'summary', '{}', ?, ?)
              """,
          userId,
          entryId,
          oldTimestamp,
          oldTimestamp);

      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      assertThat(arrayContainsLong(body.path("deletedSourceIds"), sourceId)).isTrue();
      assertThat(arrayContainsText(body.path("sources"), "name", sourceName)).isFalse();
      assertThat(arrayContainsText(body.path("entries"), "title", entryTitle)).isFalse();
      assertThat(countFeedEntryRows(userId, entryId)).isZero();
      assertThat(countUserEntryStateRows(userId, entryId)).isZero();
      assertThat(countSummaryRows(userId, entryId)).isZero();
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id = ?", userId, externalId);
      if (sourceId != null) {
        jdbcTemplate.update(
            "DELETE FROM sync_tombstone WHERE user_id = ? AND entity_id = ?", userId, sourceId);
      }
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldReturnSourceSummaryWhenEntryStateChangesUnreadCount() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-08T00:00:00Z";
    String since = "2026-04-08T00:00:01Z";
    String sourceName = "Unread Sync Source " + suffix;
    String entryTitle = "Unread Sync Entry " + suffix;
    String rssUrl = "https://unread-sync.example/" + suffix;
    String externalId = "unread-sync-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long entryId = insertEntry(userId, sourceId, entryTitle, externalId, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/" + entryId + "/read").header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      JsonNode syncedSource = objectWithText(body.path("sources"), "name", sourceName);
      JsonNode syncedEntry = objectWithText(body.path("entries"), "title", entryTitle);
      assertThat(syncedSource).isNotNull();
      assertThat(syncedSource.path("unreadCount").asInt()).isZero();
      assertThat(syncedEntry).isNotNull();
      assertThat(syncedEntry.path("isRead").asBoolean()).isTrue();
      assertThat(syncedEntry.path("readingProgress").asDouble()).isEqualTo(1.0);
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id = ?", userId, externalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldReturnSourceSummaryWhenCompletedProgressMarksEntryRead() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-08T00:00:00Z";
    String since = "2026-04-08T00:00:01Z";
    String sourceName = "Progress Read Sync Source " + suffix;
    String entryTitle = "Progress Read Sync Entry " + suffix;
    String rssUrl = "https://progress-read-sync.example/" + suffix;
    String externalId = "progress-read-sync-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long entryId = insertEntry(userId, sourceId, entryTitle, externalId, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/progress", entryId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"progress\":0.98}"))
          .andExpect(status().isNoContent());

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      JsonNode syncedSource = objectWithText(body.path("sources"), "name", sourceName);
      JsonNode syncedEntry = objectWithText(body.path("entries"), "title", entryTitle);
      assertThat(syncedSource).isNotNull();
      assertThat(syncedSource.path("unreadCount").asInt()).isZero();
      assertThat(syncedEntry).isNotNull();
      assertThat(syncedEntry.path("isRead").asBoolean()).isTrue();
      assertThat(syncedEntry.path("readingProgress").asDouble()).isEqualTo(1.0);
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id = ?", userId, externalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldReturnSourceSummaryWhenBatchEntryStateChangesUnreadCount() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-09T00:00:00Z";
    String since = "2026-04-09T00:00:01Z";
    String sourceName = "Batch Unread Sync Source " + suffix;
    String firstEntryTitle = "Batch Unread Sync Entry A " + suffix;
    String secondEntryTitle = "Batch Unread Sync Entry B " + suffix;
    String rssUrl = "https://batch-unread-sync.example/" + suffix;
    String firstExternalId = "batch-unread-sync-a-" + suffix;
    String secondExternalId = "batch-unread-sync-b-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long firstEntryId =
          insertEntry(userId, sourceId, firstEntryTitle, firstExternalId, oldTimestamp);
      long secondEntryId =
          insertEntry(userId, sourceId, secondEntryTitle, secondExternalId, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/read")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "entryIds": [%d, %d]
                      }
                      """
                          .formatted(firstEntryId, secondEntryId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.updatedCount").value(2));

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      JsonNode syncedSource = objectWithText(body.path("sources"), "name", sourceName);
      JsonNode firstSyncedEntry = objectWithText(body.path("entries"), "title", firstEntryTitle);
      JsonNode secondSyncedEntry = objectWithText(body.path("entries"), "title", secondEntryTitle);
      assertThat(syncedSource).isNotNull();
      assertThat(syncedSource.path("unreadCount").asInt()).isZero();
      assertThat(firstSyncedEntry).isNotNull();
      assertThat(firstSyncedEntry.path("isRead").asBoolean()).isTrue();
      assertThat(secondSyncedEntry).isNotNull();
      assertThat(secondSyncedEntry.path("isRead").asBoolean()).isTrue();
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id IN (?, ?)",
          userId,
          firstExternalId,
          secondExternalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldReturnSourceSummaryWhenReadAllChangesUnreadCount() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-10T00:00:00Z";
    String since = "2026-04-10T00:00:01Z";
    String sourceName = "Read All Sync Source " + suffix;
    String firstEntryTitle = "Read All Sync Entry A " + suffix;
    String secondEntryTitle = "Read All Sync Entry B " + suffix;
    String rssUrl = "https://read-all-sync.example/" + suffix;
    String firstExternalId = "read-all-sync-a-" + suffix;
    String secondExternalId = "read-all-sync-b-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      insertEntry(userId, sourceId, firstEntryTitle, firstExternalId, oldTimestamp);
      insertEntry(userId, sourceId, secondEntryTitle, secondExternalId, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/read-all")
                  .header("Authorization", "Bearer " + token)
                  .param("view", "all")
                  .param("sourceId", Long.toString(sourceId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.updatedCount").value(2));

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      JsonNode syncedSource = objectWithText(body.path("sources"), "name", sourceName);
      JsonNode firstSyncedEntry = objectWithText(body.path("entries"), "title", firstEntryTitle);
      JsonNode secondSyncedEntry = objectWithText(body.path("entries"), "title", secondEntryTitle);
      assertThat(syncedSource).isNotNull();
      assertThat(syncedSource.path("unreadCount").asInt()).isZero();
      assertThat(firstSyncedEntry).isNotNull();
      assertThat(firstSyncedEntry.path("isRead").asBoolean()).isTrue();
      assertThat(secondSyncedEntry).isNotNull();
      assertThat(secondSyncedEntry.path("isRead").asBoolean()).isTrue();
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id IN (?, ?)",
          userId,
          firstExternalId,
          secondExternalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldRepairReadProgressForAlreadyReadEntriesWhenBatchOrReadAll() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-12T00:00:00Z";
    String since = "2026-04-12T00:00:01Z";
    String sourceName = "Progress Repair Sync Source " + suffix;
    String batchEntryTitle = "Progress Repair Batch Entry " + suffix;
    String readAllEntryTitle = "Progress Repair Read All Entry " + suffix;
    String rssUrl = "https://progress-repair-sync.example/" + suffix;
    String batchExternalId = "progress-repair-batch-" + suffix;
    String readAllExternalId = "progress-repair-read-all-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long batchEntryId =
          insertEntry(userId, sourceId, batchEntryTitle, batchExternalId, oldTimestamp);
      long readAllEntryId =
          insertEntry(userId, sourceId, readAllEntryTitle, readAllExternalId, oldTimestamp);
      insertReadStateWithProgress(userId, batchEntryId, 0, oldTimestamp);
      insertReadStateWithProgress(userId, readAllEntryId, 0, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/read")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "entryIds": [%d]
                      }
                      """
                          .formatted(batchEntryId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.updatedCount").value(1));

      mockMvc
          .perform(
              post("/api/entries/read-all")
                  .header("Authorization", "Bearer " + token)
                  .param("view", "all")
                  .param("sourceId", Long.toString(sourceId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.updatedCount").value(1));

      MvcResult changesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode body = TestJson.parse(changesResult.getResponse().getContentAsString());
      JsonNode syncedSource = objectWithText(body.path("sources"), "name", sourceName);
      JsonNode batchSyncedEntry = objectWithText(body.path("entries"), "title", batchEntryTitle);
      JsonNode readAllSyncedEntry =
          objectWithText(body.path("entries"), "title", readAllEntryTitle);
      assertThat(syncedSource).isNotNull();
      assertThat(syncedSource.path("unreadCount").asInt()).isZero();
      assertThat(batchSyncedEntry).isNotNull();
      assertThat(batchSyncedEntry.path("isRead").asBoolean()).isTrue();
      assertThat(batchSyncedEntry.path("readingProgress").asDouble()).isEqualTo(1.0);
      assertThat(readAllSyncedEntry).isNotNull();
      assertThat(readAllSyncedEntry.path("isRead").asBoolean()).isTrue();
      assertThat(readAllSyncedEntry.path("readingProgress").asDouble()).isEqualTo(1.0);
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id IN (?, ?)",
          userId,
          batchExternalId,
          readAllExternalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldReturnSavedProgressAndNoiseChangesForIncrementalSync() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-11T00:00:00Z";
    String since = "2026-04-11T00:00:01Z";
    String sourceName = "State Sync Source " + suffix;
    String entryTitle = "State Sync Entry " + suffix;
    String rssUrl = "https://state-sync.example/" + suffix;
    String externalId = "state-sync-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long entryId = insertEntry(userId, sourceId, entryTitle, externalId, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/saved", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      mockMvc
          .perform(
              post("/api/entries/{entryId}/progress", entryId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"progress\":0.42}"))
          .andExpect(status().isNoContent());
      mockMvc
          .perform(
              post("/api/entries/{entryId}/noise", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());

      MvcResult noiseChangesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", since))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode noiseBody =
          TestJson.parse(
              noiseChangesResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
      JsonNode noiseSource = objectWithText(noiseBody.path("sources"), "name", sourceName);
      JsonNode noiseEntry = objectWithText(noiseBody.path("entries"), "title", entryTitle);
      assertThat(noiseSource).isNotNull();
      assertThat(noiseSource.path("unreadCount").asInt()).isZero();
      assertThat(noiseEntry).isNotNull();
      assertThat(noiseEntry.path("isSaved").asBoolean()).isTrue();
      assertThat(noiseEntry.path("readingProgress").asDouble()).isEqualTo(0.42);
      assertThat(noiseEntry.path("isNoise").asBoolean()).isTrue();
      assertThat(noiseEntry.path("filterReason").asText()).isEqualTo("手动移入噪音箱");

      String afterNoiseServerTime = noiseBody.path("serverTime").asText();
      mockMvc
          .perform(
              post("/api/entries/{entryId}/feed", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());

      MvcResult feedChangesResult =
          mockMvc
              .perform(
                  get("/api/sync/changes")
                      .header("Authorization", "Bearer " + token)
                      .param("since", afterNoiseServerTime))
              .andExpect(status().isOk())
              .andReturn();

      JsonNode feedBody =
          TestJson.parse(
              feedChangesResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
      JsonNode feedSource = objectWithText(feedBody.path("sources"), "name", sourceName);
      JsonNode feedEntry = objectWithText(feedBody.path("entries"), "title", entryTitle);
      assertThat(feedSource).isNotNull();
      assertThat(feedSource.path("unreadCount").asInt()).isEqualTo(1);
      assertThat(feedEntry).isNotNull();
      assertThat(feedEntry.path("isSaved").asBoolean()).isTrue();
      assertThat(feedEntry.path("readingProgress").asDouble()).isEqualTo(0.42);
      assertThat(feedEntry.path("isNoise").asBoolean()).isFalse();
      assertThat(feedEntry.hasNonNull("filterReason")).isFalse();
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id = ?", userId, externalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
  }

  @Test
  void shouldNotEmitSyncChangesForRepeatedEntryStateActions() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String suffix = UUID.randomUUID().toString();
    String oldTimestamp = "2026-04-13T00:00:00Z";
    String since = "2026-04-13T00:00:01Z";
    String sourceName = "Idempotent State Source " + suffix;
    String entryTitle = "Idempotent State Entry " + suffix;
    String rssUrl = "https://idempotent-state.example/" + suffix;
    String externalId = "idempotent-state-" + suffix;

    try {
      long sourceId = insertSource(userId, sourceName, rssUrl, oldTimestamp);
      long entryId = insertEntry(userId, sourceId, entryTitle, externalId, oldTimestamp);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/saved", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      String afterSaved = assertSingleEntryChange(token, since, entryTitle, "isSaved", true);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/saved", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      assertNoEntryStateChanges(token, afterSaved);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/unsaved", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      String afterUnsaved =
          assertSingleEntryChange(token, afterSaved, entryTitle, "isSaved", false);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/unsaved", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      assertNoEntryStateChanges(token, afterUnsaved);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/read", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      String afterRead = assertSingleEntryChange(token, afterUnsaved, entryTitle, "isRead", true);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/read", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      assertNoEntryStateChanges(token, afterRead);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/unread", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      String afterUnread = assertSingleEntryChange(token, afterRead, entryTitle, "isRead", false);

      mockMvc
          .perform(
              post("/api/entries/{entryId}/unread", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      assertNoEntryStateChanges(token, afterUnread);
    } finally {
      jdbcTemplate.update("DELETE FROM user_entry_state WHERE user_id = ?", userId);
      jdbcTemplate.update(
          "DELETE FROM feed_entry WHERE user_id = ? AND external_id = ?", userId, externalId);
      jdbcTemplate.update(
          "DELETE FROM feed_source WHERE user_id = ? AND rss_url = ?", userId, rssUrl);
    }
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

  private String assertSingleEntryChange(
      String token, String since, String entryTitle, String booleanField, boolean expectedValue)
      throws Exception {
    MvcResult changesResult =
        mockMvc
            .perform(
                get("/api/sync/changes")
                    .header("Authorization", "Bearer " + token)
                    .param("since", since))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body =
        TestJson.parse(changesResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
    JsonNode syncedEntry = objectWithText(body.path("entries"), "title", entryTitle);
    assertThat(syncedEntry).isNotNull();
    assertThat(syncedEntry.path(booleanField).asBoolean()).isEqualTo(expectedValue);
    return body.path("serverTime").asText();
  }

  private void assertNoEntryStateChanges(String token, String since) throws Exception {
    MvcResult changesResult =
        mockMvc
            .perform(
                get("/api/sync/changes")
                    .header("Authorization", "Bearer " + token)
                    .param("since", since))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body =
        TestJson.parse(changesResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(body.path("entries").size()).isZero();
    assertThat(body.path("sources").size()).isZero();
  }

  private long insertSource(long userId, String name, String rssUrl, String updatedAt) {
    jdbcTemplate.update(
        """
            INSERT INTO feed_source(
                user_id, name, rss_url, site_url, icon_url, folder, enabled, status, etag,
                last_modified, last_fetched_at, last_error_at, last_error_message, created_at, updated_at
            )
            VALUES(?, ?, ?, NULL, NULL, 'Sync Test', 1, 'IDLE', NULL, NULL, NULL, NULL, NULL, ?, ?)
            """,
        userId,
        name,
        rssUrl,
        updatedAt,
        updatedAt);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM feed_source WHERE user_id = ? AND rss_url = ?", Long.class, userId, rssUrl);
  }

  private long insertEntry(
      long userId, long sourceId, String title, String externalId, String updatedAt) {
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
                1, 'SUCCESS', 0, NULL, 'SUCCESS', 'Summary', 'SKIPPED', NULL, NULL, ?, ?
            )
            """,
        userId,
        sourceId,
        externalId,
        title,
        "https://articles.example/" + externalId,
        updatedAt,
        updatedAt,
        updatedAt);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM feed_entry WHERE user_id = ? AND external_id = ?",
        Long.class,
        userId,
        externalId);
  }

  private void insertReadStateWithProgress(
      long userId, long entryId, double readingProgress, String updatedAt) {
    jdbcTemplate.update(
        """
            INSERT INTO user_entry_state(
                user_id, entry_id, is_read, read_at, reading_progress,
                reading_progress_updated_at, updated_at
            )
            VALUES(?, ?, 1, ?, ?, ?, ?)
            """,
        userId,
        entryId,
        updatedAt,
        readingProgress,
        updatedAt,
        updatedAt);
  }

  private long countFeedEntryRows(long userId, long entryId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM feed_entry WHERE user_id = ? AND id = ?",
        Long.class,
        userId,
        entryId);
  }

  private long countUserEntryStateRows(long userId, long entryId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM user_entry_state WHERE user_id = ? AND entry_id = ?",
        Long.class,
        userId,
        entryId);
  }

  private long countSummaryRows(long userId, long entryId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ai_result_summary WHERE user_id = ? AND entry_id = ?",
        Long.class,
        userId,
        entryId);
  }

  private boolean arrayContainsText(JsonNode array, String field, String expected) {
    for (JsonNode item : array) {
      if (expected.equals(item.path(field).asText())) {
        return true;
      }
    }
    return false;
  }

  private JsonNode objectWithText(JsonNode array, String field, String expected) {
    for (JsonNode item : array) {
      if (expected.equals(item.path(field).asText())) {
        return item;
      }
    }
    return null;
  }

  private boolean arrayContainsLong(JsonNode array, long expected) {
    for (JsonNode item : array) {
      if (item.asLong() == expected) {
        return true;
      }
    }
    return false;
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-sync-", ".db");
    } catch (IOException exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }

  private static MockWebServer createServer() {
    try {
      MockWebServer server = new MockWebServer();
      server.setDispatcher(new SyncDispatcher());
      server.start();
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to start mock web server", exception);
    }
  }

  private static final class SyncDispatcher extends Dispatcher {

    @Override
    public MockResponse dispatch(RecordedRequest request) {
      String path = request.getPath();
      if (path == null) {
        return notFound();
      }
      if (path.equals("/feed.xml")) {
        return xml(sampleFeed());
      }
      if (path.equals("/sync-moving-feed.xml")) {
        return new MockResponse()
            .setResponseCode(301)
            .setHeader("Location", MOCK_WEB_SERVER.url("/sync-moved/final-feed.xml").toString());
      }
      if (path.equals("/sync-moved/final-feed.xml")) {
        return xml(movedSyncFeed());
      }
      if (path.equals("/sync-moved/entry")) {
        return html(
            """
                        <html><body><article><p>Moved feed content.</p></article></body></html>
                        """);
      }
      if (path.equals("/articles/1")) {
        return html(
            """
                        <html><body><article><p>First paragraph.</p><p>Second paragraph.</p></article></body></html>
                        """);
      }
      if (path.equals("/articles/2")) {
        return html(
            """
                        <html><body><article><p>Short update only.</p></article></body></html>
                        """);
      }
      if (path.equals("/chat/completions")) {
        String body = extractPromptText(request);
        boolean isFilterRequest = body.contains("rss 内容筛选助手") || body.contains("isnoise");
        boolean isSummaryRequest = body.contains("生成一段中文摘要") || body.contains("120");
        if (isFilterRequest && body.contains("quick news")) {
          return json(chatContent("{\"isNoise\":true,\"reason\":\"内容过短\"}"));
        }
        if (isFilterRequest && body.contains("long analysis")) {
          return json(chatContent("{\"isNoise\":false,\"reason\":\"有分析\"}"));
        }
        if (isSummaryRequest && body.contains("long analysis")) {
          return json(chatContent("同步测试摘要"));
        }
        if (isSummaryRequest && body.contains("quick news")) {
          return json(chatContent("同步测试短摘要"));
        }
        if (body.contains("quick news")) {
          return json(
              chatContent(
                  """
                        [
                          {"source":"Short update only.","translation":"简短更新。"}
                        ]
                        """));
        }
        return json(
            chatContent(
                """
                        [
                          {"source":"First paragraph.","translation":"第一段。"},
                          {"source":"Second paragraph.","translation":"第二段。"}
                        ]
                        """));
      }
      return notFound();
    }

    private MockResponse xml(String body) {
      return new MockResponse().setHeader("Content-Type", "application/rss+xml").setBody(body);
    }

    private MockResponse html(String body) {
      return new MockResponse().setHeader("Content-Type", "text/html").setBody(body);
    }

    private MockResponse json(String body) {
      return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private MockResponse notFound() {
      return new MockResponse().setResponseCode(404);
    }

    private String sampleFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Sample Feed</title>
                        <link>%s</link>
                        <description>Sample feed description</description>
                        <item>
                          <title>Long Analysis</title>
                          <link>%s/articles/1</link>
                          <guid>entry-1</guid>
                          <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                          <description><![CDATA[Long article teaser]]></description>
                        </item>
                        <item>
                          <title>Quick News</title>
                          <link>%s/articles/2</link>
                          <guid>entry-2</guid>
                          <pubDate>Tue, 08 Apr 2026 11:00:00 GMT</pubDate>
                          <description><![CDATA[Short article teaser]]></description>
                        </item>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl, baseUrl, baseUrl);
    }

    private String movedSyncFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Moved Sync Feed</title>
                        <link>%s/sync-moved/</link>
                        <description>Feed URL canonicalized by refresh.</description>
                        <item>
                          <title>Moved Sync Entry</title>
                          <link>%s/sync-moved/entry</link>
                          <guid>sync-moved-entry</guid>
                          <pubDate>Tue, 08 Apr 2026 12:00:00 GMT</pubDate>
                          <description><![CDATA[Moved feed article teaser]]></description>
                        </item>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl, baseUrl);
    }

    private String chatContent(String content) {
      return """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 1710000000,
                      "model": "deepseek-chat",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "content": %s
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 8,
                        "total_tokens": 20
                      }
                    }
                    """
          .formatted(quote(content));
    }

    private String quote(String content) {
      return "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String extractPromptText(RecordedRequest request) {
      String rawBody = request.getBody().readUtf8();
      try {
        StringBuilder promptText = new StringBuilder();
        for (var messageNode : TestJson.parse(rawBody).path("messages")) {
          appendContent(promptText, messageNode.path("content"));
        }
        return promptText.toString().toLowerCase(Locale.ROOT);
      } catch (Exception exception) {
        return rawBody.toLowerCase(Locale.ROOT);
      }
    }

    private void appendContent(
        StringBuilder promptText, com.fasterxml.jackson.databind.JsonNode node) {
      if (node == null || node.isMissingNode() || node.isNull()) {
        return;
      }
      if (node.isTextual()) {
        promptText.append(node.asText()).append('\n');
        return;
      }
      if (node.isArray()) {
        for (var item : node) {
          appendContent(promptText, item);
        }
        return;
      }
      appendContent(promptText, node.path("text"));
      appendContent(promptText, node.path("content"));
    }
  }
}
