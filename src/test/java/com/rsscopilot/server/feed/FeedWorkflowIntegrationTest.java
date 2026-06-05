package com.rsscopilot.server.feed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsscopilot.server.support.TestJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedWorkflowIntegrationTest {

  private static final Path DB_PATH = createDbPath();
  private static final MockWebServer MOCK_WEB_SERVER = createServer();

  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
    registry.add("app.bootstrap.default-user.email", () -> "demo@example.com");
    registry.add("app.bootstrap.default-user.password", () -> "pass123456");
    registry.add("app.bootstrap.default-user.api-key", () -> "sk-bootstrap");
    registry.add(
        "app.ai.deepseek.base-url", () -> MOCK_WEB_SERVER.url("/").toString().replaceAll("/$", ""));
    registry.add("app.ai.deepseek.model", () -> "deepseek-chat");
  }

  @AfterAll
  static void cleanup() throws Exception {
    MOCK_WEB_SERVER.shutdown();
    Files.deleteIfExists(DB_PATH);
  }

  @Test
  void shouldCompleteMainFeedWorkflow() throws Exception {
    String token = login();
    String sourceIconUrl = MOCK_WEB_SERVER.url("/favicon.ico").toString();

    MvcResult createdSourceResult =
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
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Sample Feed"))
            .andExpect(jsonPath("$.folder").value("未分组"))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    mockMvc
        .perform(
            post("/api/feed-sources/{sourceId}/refresh", sourceId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.acceptedCount").value(1))
        .andExpect(jsonPath("$.requestedCount").value(1))
        .andExpect(jsonPath("$.skippedCount").value(0));

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
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].title").value("Long Analysis"))
                    .andExpect(jsonPath("$.items[0].sourceIconUrl").value(sourceIconUrl))
                    .andExpect(jsonPath("$.items[0].author").value("Jane Analyst"))
                    .andExpect(jsonPath("$.items[0].summary").value("这是一篇关于技术趋势的长文摘要。"))
                    .andExpect(jsonPath("$.items[0].filterStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.items[0].summaryStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.items[0].translationStatus").value("SUCCESS"))
                    .andExpect(
                        jsonPath("$.items[0].coverImageUrl").value("https://example.com/image.png"))
                    .andExpect(jsonPath("$.items[0].foreign").value(true)));

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", " FEED "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "noise"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Quick News"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "技术趋势"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "Jane Analyst"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "Jane 技术趋势"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "JANE jane Analyst Long Analysis 技术 趋势 摘要 第一段 ignored-missing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "Jane missing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("folder", "未分组"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("sourceId", Long.toString(sourceId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("sourceId", Long.toString(sourceId + 999)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("feed source not found"));

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "archive"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid view"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("folder", "Research"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "Quick"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "noise")
                .param("q", "内容过短"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Quick News"));

    mockMvc
        .perform(
            get("/api/entries")
                .header("Authorization", "Bearer " + token)
                .param("view", "feed")
                .param("q", "第一段讲述"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(sourceId))
        .andExpect(jsonPath("$[0].unreadCount").value(1))
        .andExpect(jsonPath("$[0].hasError").value(false));

    mockMvc
        .perform(
            put("/api/feed-sources/{sourceId}", sourceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Sample Feed",
                      "rssUrl": "%s/feed.xml",
                      "iconUrl": "%s",
                      "folder": "未分组",
                      "enabled": true
                    }
                    """
                        .formatted(
                            MOCK_WEB_SERVER.url("").toString().replaceAll("/$", ""),
                            sourceIconUrl)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(sourceId))
        .andExpect(jsonPath("$.unreadCount").value(1))
        .andExpect(jsonPath("$.hasError").value(false));

    MvcResult listResult =
        mockMvc
            .perform(
                get("/api/entries")
                    .header("Authorization", "Bearer " + token)
                    .param("view", "feed")
                    .param("unreadOnly", "true"))
            .andExpect(status().isOk())
            .andReturn();

    long entryId =
        TestJson.parse(listResult.getResponse().getContentAsString())
            .path("items")
            .get(0)
            .path("id")
            .asLong();

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(entryId))
        .andExpect(jsonPath("$.sourceIconUrl").value(sourceIconUrl))
        .andExpect(jsonPath("$.author").value("Jane Analyst"))
        .andExpect(jsonPath("$.isRead").value(false))
        .andExpect(jsonPath("$.isSaved").value(false))
        .andExpect(jsonPath("$.isNoise").value(false))
        .andExpect(jsonPath("$.summary").value("这是一篇关于技术趋势的长文摘要。"))
        .andExpect(jsonPath("$.filterStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.summaryStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.translationStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.coverImageUrl").value("https://example.com/image.png"))
        .andExpect(jsonPath("$.translationSegments.length()").value(2))
        .andExpect(
            jsonPath("$.contentHtml")
                .value(org.hamcrest.Matchers.containsString("<pre><code>System.out.println")));

    mockMvc
        .perform(
            put("/api/feed-sources/{sourceId}", sourceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Sample Feed",
                      "rssUrl": "%s/feed.xml",
                      "iconUrl": null,
                      "folder": "未分组",
                      "enabled": true
                    }
                    """
                        .formatted(MOCK_WEB_SERVER.url("").toString().replaceAll("/$", ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(sourceId))
        .andExpect(jsonPath("$.iconUrl").value(nullValue()));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(sourceId))
        .andExpect(jsonPath("$[0].iconUrl").value(nullValue()));

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceIconUrl").value(nullValue()));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/ai/reprocess", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted());

    Awaitility.await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                mockMvc
                    .perform(
                        get("/api/entries/{entryId}", entryId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.filterStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.summaryStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.translationStatus").value("SUCCESS")));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/noise", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "feed"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "noise"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].unreadCount").value(0));

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isNoise").value(true))
        .andExpect(jsonPath("$.filterReason").value("手动移入噪音箱"));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/feed", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "feed"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(entryId))
        .andExpect(jsonPath("$.items[0].isNoise").value(false));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].unreadCount").value(1));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/saved", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSaved").value(true));

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "saved"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(entryId))
        .andExpect(jsonPath("$.items[0].isSaved").value(true));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/unsaved", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/entries").header("Authorization", "Bearer " + token).param("view", "saved"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/progress", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"progress\":0.42}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.readingProgress").value(0.42));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/progress", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("progress must not be null"));

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.readingProgress").value(0.42));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/progress", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"progress\":-0.5}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.readingProgress").value(0.0));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/progress", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"progress\":1.5}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isRead").value(true))
        .andExpect(jsonPath("$.readingProgress").value(1.0));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/read", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isRead").value(true))
        .andExpect(jsonPath("$.readingProgress").value(1.0));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/progress", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"progress\":0.42}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isRead").value(true))
        .andExpect(jsonPath("$.readingProgress").value(1.0));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/unread", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isRead").value(false))
        .andExpect(jsonPath("$.readingProgress").value(0.0));

    mockMvc
        .perform(
            post("/api/entries/read")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryIds": [%d, %d, 999999, 0, -2, null]
                    }
                    """
                        .formatted(entryId, entryId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1));

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isRead").value(true))
        .andExpect(jsonPath("$.readingProgress").value(1.0));

    mockMvc
        .perform(
            post("/api/entries/read")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("entryIds must not be null"));

    mockMvc
        .perform(get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isRead").value(true))
        .andExpect(jsonPath("$.readingProgress").value(1.0));

    mockMvc
        .perform(
            post("/api/entries/read")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryIds\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(0));

    String tooManyEntryIdsPayload =
        "{\"entryIds\":["
            + LongStream.rangeClosed(1, 101)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(","))
            + "]}";
    mockMvc
        .perform(
            post("/api/entries/read")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tooManyEntryIdsPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("too many entry ids"));

    String tooManyDuplicateEntryIdsPayload =
        "{\"entryIds\":["
            + LongStream.rangeClosed(1, 101)
                .mapToObj(ignored -> Long.toString(entryId))
                .collect(Collectors.joining(","))
            + "]}";
    mockMvc
        .perform(
            post("/api/entries/read")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tooManyDuplicateEntryIdsPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("too many entry ids"));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/unread", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post("/api/entries/read-all").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/unread", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/entries/read-all")
                .header("Authorization", "Bearer " + token)
                .param("view", " ALL ")
                .param("sourceId", Long.toString(sourceId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(2));

    mockMvc
        .perform(
            post("/api/entries/{entryId}/unread", entryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/entries/read-all")
                .header("Authorization", "Bearer " + token)
                .param("view", "all")
                .param("folder", "未分组"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1));

    mockMvc
        .perform(
            post("/api/entries/read-all")
                .header("Authorization", "Bearer " + token)
                .param("view", "archive"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid view"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].unreadCount").value(0));

    mockMvc
        .perform(
            get("/api/feed-sources/{sourceId}/entries", sourceId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.hasMore").value(false));

    mockMvc
        .perform(
            get("/api/feed-sources/{sourceId}/entries", sourceId)
                .header("Authorization", "Bearer " + token)
                .param("q", "Short update"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Quick News"));

    MvcResult firstSourcePage =
        mockMvc
            .perform(
                get("/api/feed-sources/{sourceId}/entries", sourceId)
                    .header("Authorization", "Bearer " + token)
                    .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Quick News"))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextCursor.publishedAt").exists())
            .andExpect(jsonPath("$.nextCursor.id").exists())
            .andReturn();

    String beforePublishedAt =
        TestJson.parse(firstSourcePage.getResponse().getContentAsString())
            .path("nextCursor")
            .path("publishedAt")
            .asText();
    long beforeId =
        TestJson.parse(firstSourcePage.getResponse().getContentAsString())
            .path("nextCursor")
            .path("id")
            .asLong();

    mockMvc
        .perform(
            get("/api/feed-sources/{sourceId}/entries", sourceId)
                .header("Authorization", "Bearer " + token)
                .param("limit", "1")
                .param("beforePublishedAt", beforePublishedAt)
                .param("beforeId", Long.toString(beforeId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Long Analysis"))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());

    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    MvcResult discoveredSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s/discover"
                        }
                        """
                            .formatted(baseUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Discovered Feed"))
            .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover/feed.xml"))
            .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover"))
            .andReturn();

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "%s/discover-link"
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Linked Feed"))
        .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover-link/rss.xml"))
        .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover-link"));

    MvcResult jsonDiscoveredSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s/discover-json"
                        }
                        """
                            .formatted(baseUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("JSON Discovery Feed"))
            .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover-json/feed.json"))
            .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover-json/"))
            .andReturn();

    long jsonDiscoveredSourceId =
        TestJson.parse(jsonDiscoveredSourceResult.getResponse().getContentAsString())
            .path("id")
            .asLong();
    mockMvc
        .perform(
            post("/api/feed-sources/{sourceId}/refresh", jsonDiscoveredSourceId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.acceptedCount").value(1))
        .andExpect(jsonPath("$.requestedCount").value(1))
        .andExpect(jsonPath("$.skippedCount").value(0));

    Awaitility.await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                mockMvc
                    .perform(
                        get("/api/feed-sources/{sourceId}/entries", jsonDiscoveredSourceId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].title").value("JSON Discovery Entry"))
                    .andExpect(jsonPath("$.items[0].summary").value("这是一条 JSON Feed 条目。"))
                    .andExpect(jsonPath("$.items[0].filterStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.items[0].summaryStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.items[0].translationStatus").value("SUCCESS")));

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "%s/discover-json-common"
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("JSON Discovery Feed"))
        .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover-json-common/feed.json"))
        .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover-json-common/"));

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "%s/discover-common"
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Common Path Feed"))
        .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover-common/feed.xml"))
        .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover-common"));

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "%s/discover-comments-first"
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Main Posts Feed"))
        .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover-comments-first/posts.xml"))
        .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover-comments-first"));

    long discoveredSourceId =
        TestJson.parse(discoveredSourceResult.getResponse().getContentAsString())
            .path("id")
            .asLong();

    mockMvc
        .perform(
            put("/api/feed-sources/{sourceId}", discoveredSourceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Moved Feed",
                      "rssUrl": "%s/discover-update-link",
                      "folder": "未分组",
                      "enabled": true
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(discoveredSourceId))
        .andExpect(jsonPath("$.name").value("Moved Feed"))
        .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover-update-link/rss.xml"))
        .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover-update-link"));

    mockMvc
        .perform(
            put("/api/feed-sources/{sourceId}", discoveredSourceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Discovered Feed",
                      "rssUrl": "%s/discover/feed.xml",
                      "folder": "未分组",
                      "enabled": true
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(discoveredSourceId))
        .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/discover/feed.xml"))
        .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/discover"));

    mockMvc
        .perform(
            put("/api/feed-sources/{sourceId}", discoveredSourceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Duplicate Feed",
                      "rssUrl": "%s/feed.xml",
                      "folder": "未分组",
                      "enabled": true
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.message").value("rss source already exists"));

    mockMvc
        .perform(
            put("/api/settings/ai")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "apiKey": "sk-demo-test",
                          "filterPrompt": "保留高质量分析内容",
                          "summaryPrompt": "用 80 字总结",
                          "translationPrompt": "翻译成中文",
                          "autoSummaryEnabled": true,
                          "autoTranslationEnabled": false,
                          "outputLanguage": "zh-CN",
                          "provider": "DEEPSEEK"
                        }
                        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.apiKeyMasked").value("sk-***est"));

    mockMvc
        .perform(get("/api/settings").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ai.provider").value("DEEPSEEK"))
        .andExpect(jsonPath("$.ai.apiKeyMasked").value("sk-***est"))
        .andExpect(jsonPath("$.appearance.themeMode").value("SYSTEM"))
        .andExpect(jsonPath("$.feeds.defaultLanguage").value("zh-CN"));

    mockMvc
        .perform(
            put("/api/settings/appearance")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"themeMode\":\"dark\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.themeMode").value("DARK"));

    mockMvc
        .perform(get("/api/settings").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appearance.themeMode").value("DARK"));

    mockMvc
        .perform(
            put("/api/settings/feeds")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultLanguage\":\"en-us\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultLanguage").value("en-US"))
        .andExpect(jsonPath("$.refreshPolicyDescription").value("固定每小时自动刷新一次"));

    mockMvc
        .perform(get("/api/settings").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feeds.defaultLanguage").value("en-US"))
        .andExpect(jsonPath("$.ai.outputLanguage").value("en-US"));

    mockMvc
        .perform(
            put("/api/settings/feeds")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultLanguage\":\"not a language\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("defaultLanguage must be a BCP 47 language tag"));

    mockMvc
        .perform(
            put("/api/settings/appearance")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"themeMode\":\"sepia\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("themeMode must be SYSTEM, LIGHT, or DARK"));

    mockMvc
        .perform(
            put("/api/settings/ai")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "apiKey": null,
                          "filterPrompt": "保留高质量分析内容并忽略营销软文",
                          "summaryPrompt": "用 100 字总结",
                          "translationPrompt": "翻译成中文",
                          "autoSummaryEnabled": true,
                          "autoTranslationEnabled": true,
                          "outputLanguage": "zh-CN",
                          "provider": "deepseek"
                        }
                        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("DEEPSEEK"))
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.apiKeyMasked").value("sk-***est"))
        .andExpect(jsonPath("$.filterPrompt").value("保留高质量分析内容并忽略营销软文"));

    MvcResult clearedAiSettings =
        mockMvc
            .perform(
                put("/api/settings/ai")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                            {
                              "clearApiKey": true,
                              "filterPrompt": "保留高质量分析内容",
                              "summaryPrompt": "用 80 字总结",
                              "translationPrompt": "翻译成中文",
                              "autoSummaryEnabled": true,
                              "autoTranslationEnabled": false,
                              "outputLanguage": "zh-CN",
                              "provider": "DEEPSEEK"
                            }
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false))
            .andReturn();
    org.assertj.core.api.Assertions.assertThat(
            TestJson.parse(clearedAiSettings.getResponse().getContentAsString())
                .path("apiKeyMasked")
                .asText(null))
        .isNull();

    mockMvc
        .perform(
            put("/api/feed-sources/{sourceId}", sourceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Broken Feed",
                      "rssUrl": "%s/broken.xml",
                      "folder": "未分组",
                      "enabled": true
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/feed-sources/{sourceId}/entries", sourceId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.hasMore").value(false));

    mockMvc
        .perform(
            post("/api/feed-sources/{sourceId}/refresh", sourceId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted());

    Awaitility.await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () ->
                mockMvc
                    .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].hasError").value(true))
                    .andExpect(jsonPath("$[0].lastErrorAt").exists())
                    .andExpect(
                        jsonPath("$[0].lastErrorMessage").value("rss refresh failed: HTTP 404")));
  }

  @Test
  void shouldRefreshSelectedSourcesInOneBatchRequest() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long firstSourceId = createSource(token, baseUrl + "/validation-feed.xml");
    long secondSourceId = createSource(token, baseUrl + "/headers-required-feed.xml");
    long excludedSourceId = createSource(token, baseUrl + "/no-id-feed.xml");

    try {
      mockMvc
          .perform(
              post("/api/feed-sources/refresh")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "sourceIds": [%d, %d, %d]
                      }
                      """
                          .formatted(firstSourceId, secondSourceId, firstSourceId)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(true))
          .andExpect(jsonPath("$.acceptedCount").value(2))
          .andExpect(jsonPath("$.requestedCount").value(2))
          .andExpect(jsonPath("$.skippedCount").value(0));

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () -> {
                org.assertj.core.api.Assertions.assertThat(
                        sourceField(token, firstSourceId, "lastFetchedAt"))
                    .isNotNull();
                org.assertj.core.api.Assertions.assertThat(
                        sourceField(token, secondSourceId, "lastFetchedAt"))
                    .isNotNull();
                org.assertj.core.api.Assertions.assertThat(
                        sourceField(token, excludedSourceId, "lastFetchedAt"))
                    .isNull();
              });
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", firstSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", secondSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", excludedSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldSkipDisabledSourcesInBatchRefreshRequest() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long enabledSourceId = createSource(token, baseUrl + "/validation-feed.xml");
    long disabledSourceId = createSource(token, baseUrl + "/headers-required-feed.xml");

    try {
      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", disabledSourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Paused Feed",
                        "rssUrl": "%s/headers-required-feed.xml",
                        "folder": "未分组",
                        "enabled": false
                      }
                      """
                          .formatted(baseUrl)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(false));

      mockMvc
          .perform(
              post("/api/feed-sources/refresh")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "sourceIds": [%d, %d]
                      }
                      """
                          .formatted(enabledSourceId, disabledSourceId)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(true))
          .andExpect(jsonPath("$.acceptedCount").value(1))
          .andExpect(jsonPath("$.requestedCount").value(2))
          .andExpect(jsonPath("$.skippedCount").value(1));

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () -> {
                org.assertj.core.api.Assertions.assertThat(
                        sourceField(token, enabledSourceId, "lastFetchedAt"))
                    .isNotNull();
                org.assertj.core.api.Assertions.assertThat(
                        sourceField(token, disabledSourceId, "lastFetchedAt"))
                    .isNull();
              });
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", enabledSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", disabledSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldSkipMissingSourcesInBatchRefreshRequest() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long enabledSourceId = createSource(token, baseUrl + "/validation-feed.xml");
    long deletedSourceId = createSource(token, baseUrl + "/headers-required-feed.xml");

    try {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", deletedSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());

      mockMvc
          .perform(
              post("/api/feed-sources/refresh")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "sourceIds": [%d, %d, 999999]
                      }
                      """
                          .formatted(enabledSourceId, deletedSourceId)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(true))
          .andExpect(jsonPath("$.acceptedCount").value(1))
          .andExpect(jsonPath("$.requestedCount").value(3))
          .andExpect(jsonPath("$.skippedCount").value(2));

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  org.assertj.core.api.Assertions.assertThat(
                          sourceField(token, enabledSourceId, "lastFetchedAt"))
                      .isNotNull());
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", enabledSourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldSkipDisabledSourceInSingleRefreshRequest() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long sourceId = createSource(token, baseUrl + "/headers-required-feed.xml");

    try {
      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Paused Feed",
                        "rssUrl": "%s/headers-required-feed.xml",
                        "folder": "未分组",
                        "enabled": false
                      }
                      """
                          .formatted(baseUrl)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(false));

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.accepted").value(true))
          .andExpect(jsonPath("$.acceptedCount").value(0))
          .andExpect(jsonPath("$.requestedCount").value(1))
          .andExpect(jsonPath("$.skippedCount").value(1));

      org.assertj.core.api.Assertions.assertThat(sourceField(token, sourceId, "lastFetchedAt"))
          .isNull();
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldRejectMissingSourceEntriesRequest() throws Exception {
    String token = login();

    mockMvc
        .perform(
            get("/api/feed-sources/{sourceId}/entries", 999999)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("feed source not found"));
  }

  @Test
  void shouldRejectTooManySourcesInBatchRefreshRequest() throws Exception {
    String token = login();
    String sourceIds =
        LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(", "));

    mockMvc
        .perform(
            post("/api/feed-sources/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceIds": [%s]
                    }
                    """
                        .formatted(sourceIds)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("too many feed sources to refresh"));
  }

  @Test
  void shouldRejectTooManyRawSourceIdsInBatchRefreshRequest() throws Exception {
    String token = login();
    String sourceIds =
        LongStream.rangeClosed(1, 101).mapToObj(ignored -> "1").collect(Collectors.joining(", "));

    mockMvc
        .perform(
            post("/api/feed-sources/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceIds": [%s]
                    }
                    """
                        .formatted(sourceIds)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("too many feed sources to refresh"));
  }

  @Test
  void shouldRejectInvalidSourceIdInBatchRefreshRequest() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceIds": [1, 0, -2]
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid feed source id"));
  }

  @Test
  void shouldReportZeroAcceptedSourcesForEmptyBatchRefreshRequest() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceIds": []
                    }
                    """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.acceptedCount").value(0))
        .andExpect(jsonPath("$.requestedCount").value(0))
        .andExpect(jsonPath("$.skippedCount").value(0));
  }

  @Test
  void shouldNotDuplicateEntriesWithoutGuidOrLinkAcrossRefreshes() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");

    MvcResult createdSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s/no-id-feed.xml"
                        }
                        """
                            .formatted(baseUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("No ID Feed"))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    try {
      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(
                          get("/api/entries")
                              .header("Authorization", "Bearer " + token)
                              .param("view", "all")
                              .param("sourceId", Long.toString(sourceId)))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.items.length()").value(1))
                      .andExpect(jsonPath("$.items[0].title").value("Title Only Entry"))
                      .andExpect(jsonPath("$.items[0].link").value(baseUrl + "/no-id")));

      String firstFetchedAt = sourceField(token, sourceId, "lastFetchedAt");

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () -> {
                String nextFetchedAt = sourceField(token, sourceId, "lastFetchedAt");
                org.assertj.core.api.Assertions.assertThat(nextFetchedAt)
                    .isNotNull()
                    .isNotEqualTo(firstFetchedAt);
                mockMvc
                    .perform(
                        get("/api/entries")
                            .header("Authorization", "Bearer " + token)
                            .param("view", "all")
                            .param("sourceId", Long.toString(sourceId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1));
              });
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldRefreshExistingEntryMetadataAndUpgradeFallbackContent() throws Exception {
    String token = login();
    ensureAiConfigured(token);
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");

    MvcResult createdSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s/mutable-feed.xml"
                        }
                        """
                            .formatted(baseUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Mutable Feed"))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    try {
      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      long[] entryIdHolder = new long[1];
      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () -> {
                MvcResult listResult =
                    mockMvc
                        .perform(
                            get("/api/entries")
                                .header("Authorization", "Bearer " + token)
                                .param("view", "all")
                                .param("sourceId", Long.toString(sourceId)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].title").value("Original Mutable Title"))
                        .andReturn();
                entryIdHolder[0] =
                    TestJson.parse(listResult.getResponse().getContentAsString())
                        .path("items")
                        .get(0)
                        .path("id")
                        .asLong();
              });

      long entryId = entryIdHolder[0];

      mockMvc
          .perform(
              get("/api/entries/{entryId}", entryId).header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.contentHtml")
                  .value(org.hamcrest.Matchers.containsString("Original RSS summary")));

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(
                          get("/api/entries/{entryId}", entryId)
                              .header("Authorization", "Bearer " + token))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.summaryStatus").value("SUCCESS"))
                      .andExpect(jsonPath("$.summary").value("这是刷新前只有 RSS 摘要的测试条目。")));

      mockMvc
          .perform(
              post("/api/entries/{entryId}/saved", entryId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(
                          get("/api/entries/{entryId}", entryId)
                              .header("Authorization", "Bearer " + token))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.title").value("Revised Mutable Title"))
                      .andExpect(jsonPath("$.isSaved").value(true))
                      .andExpect(
                          jsonPath("$.coverImageUrl").value(baseUrl + "/images/mutable-cover.png"))
                      .andExpect(jsonPath("$.summaryStatus").value("SUCCESS"))
                      .andExpect(jsonPath("$.summary").value("这是刷新后恢复全文后的摘要。"))
                      .andExpect(
                          jsonPath("$.contentHtml")
                              .value(
                                  org.hamcrest.Matchers.containsString(
                                      "Recovered full article body"))));
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldSendReaderFriendlyHeadersWhenFetchingFeeds() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");

    MvcResult createdSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s/headers-required-feed.xml"
                        }
                        """
                            .formatted(baseUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Headers Required Feed"))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    try {
      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(
                          get("/api/entries")
                              .header("Authorization", "Bearer " + token)
                              .param("view", "all")
                              .param("sourceId", Long.toString(sourceId)))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.items.length()").value(1))
                      .andExpect(jsonPath("$.items[0].title").value("Header Guard Entry")));

      org.assertj.core.api.Assertions.assertThat(sourceField(token, sourceId, "hasError"))
          .isEqualTo("false");
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldReportHttpStatusWhenFeedSourceIsUnreachable() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "%s/missing-feed.xml"
                    }
                    """
                        .formatted(baseUrl)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("rss source is unreachable: HTTP 404"));
  }

  @Test
  void shouldResolveRelativeFeedLinksFromRedirectedFeedUrl() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long sourceId = -1;
    try {
      MvcResult sourceResult =
          mockMvc
              .perform(
                  post("/api/feed-sources")
                      .header("Authorization", "Bearer " + token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {
                            "rssUrl": "%s/redirect-feed.xml"
                          }
                          """
                              .formatted(baseUrl)))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/redirected/final-feed.xml"))
              .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/redirected/"))
              .andReturn();

      sourceId =
          TestJson.parse(sourceResult.getResponse().getContentAsString()).path("id").asLong();

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      long createdSourceId = sourceId;
      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(
                          get("/api/feed-sources/{sourceId}/entries", createdSourceId)
                              .header("Authorization", "Bearer " + token))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.items.length()").value(1))
                      .andExpect(jsonPath("$.items[0].title").value("Redirect Relative Entry"))
                      .andExpect(jsonPath("$.items[0].link").value(baseUrl + "/redirected/entry")));
    } finally {
      if (sourceId > 0) {
        mockMvc
            .perform(
                delete("/api/feed-sources/{sourceId}", sourceId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
      }
    }
  }

  @Test
  void shouldPersistFinalFeedUrlWhenExistingSourceRedirectsOnRefresh() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long sourceId = -1;
    try {
      sourceId = createSource(token, baseUrl + "/moving-feed.xml");

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      long createdSourceId = sourceId;
      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () -> {
                mockMvc
                    .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(createdSourceId))
                    .andExpect(jsonPath("$[0].rssUrl").value(baseUrl + "/moved/final-moving.xml"))
                    .andExpect(jsonPath("$[0].hasError").value(false));

                mockMvc
                    .perform(
                        get("/api/feed-sources/{sourceId}/entries", createdSourceId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].title").value("Moved Feed Entry"))
                    .andExpect(jsonPath("$.items[0].link").value(baseUrl + "/moved/entry"));
              });
    } finally {
      if (sourceId > 0) {
        mockMvc
            .perform(
                delete("/api/feed-sources/{sourceId}", sourceId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
      }
    }
  }

  @Test
  void shouldKeepOriginalFeedUrlWhenRefreshRedirectsToErrorPage() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    String originalUrl = baseUrl + "/moving-error-feed.xml";
    long sourceId = -1;
    try {
      sourceId = createSource(token, originalUrl);

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      long createdSourceId = sourceId;
      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$[0].id").value(createdSourceId))
                      .andExpect(jsonPath("$[0].rssUrl").value(originalUrl))
                      .andExpect(jsonPath("$[0].hasError").value(true))
                      .andExpect(
                          jsonPath("$[0].lastErrorMessage").value("rss refresh failed: HTTP 404")));
    } finally {
      if (sourceId > 0) {
        mockMvc
            .perform(
                delete("/api/feed-sources/{sourceId}", sourceId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
      }
    }
  }

  @Test
  void shouldDiscoverRelativeFeedLinksFromRedirectedPageUrl() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long sourceId = -1;
    try {
      MvcResult sourceResult =
          mockMvc
              .perform(
                  post("/api/feed-sources")
                      .header("Authorization", "Bearer " + token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {
                            "rssUrl": "%s/redirect-discover"
                          }
                          """
                              .formatted(baseUrl)))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.name").value("Redirect Discovered Feed"))
              .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/moved/discover/feed.xml"))
              .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/moved/discover/"))
              .andReturn();

      sourceId =
          TestJson.parse(sourceResult.getResponse().getContentAsString()).path("id").asLong();
    } finally {
      if (sourceId > 0) {
        mockMvc
            .perform(
                delete("/api/feed-sources/{sourceId}", sourceId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());
      }
    }
  }

  @Test
  void shouldRejectInvalidFeedSourceUrls() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "not a url"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("invalid url"));

    mockMvc
        .perform(
            post("/api/feed-sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "rssUrl": "ftp://example.com/feed.xml"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("invalid url"));

    MvcResult createdSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s/validation-feed.xml"
                        }
                        """
                            .formatted(baseUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Validation Feed"))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    try {
      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Validation Feed",
                        "rssUrl": "%s/validation-feed.xml",
                        "folder": "未分组"
                      }
                      """
                          .formatted(baseUrl)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
          .andExpect(jsonPath("$.message").value("enabled must not be null"));

      mockMvc
          .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(sourceId))
          .andExpect(jsonPath("$[0].enabled").value(true));

      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Validation Feed",
                        "rssUrl": "not a url",
                        "folder": "未分组",
                        "enabled": true
                      }
                      """))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
          .andExpect(jsonPath("$.message").value("invalid url"));

      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Validation Feed",
                        "rssUrl": "%s/validation-feed.xml",
                        "iconUrl": "favicon.ico",
                        "folder": "未分组",
                        "enabled": true
                      }
                      """
                          .formatted(baseUrl)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
          .andExpect(jsonPath("$.message").value("invalid url"));

      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "  Validation Feed Renamed  ",
                        "rssUrl": "%s/validation-feed.xml",
                        "iconUrl": null,
                        "folder": "  Research  ",
                        "enabled": true
                      }
                      """
                          .formatted(baseUrl)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Validation Feed Renamed"))
          .andExpect(jsonPath("$.folder").value("Research"));

      mockMvc
          .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(sourceId))
          .andExpect(jsonPath("$[0].name").value("Validation Feed Renamed"))
          .andExpect(jsonPath("$[0].folder").value("Research"));
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldRejectUnsupportedAiProvider() throws Exception {
    String token = login();

    mockMvc
        .perform(
            put("/api/settings/ai")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "apiKey": "sk-demo-test",
                      "filterPrompt": "保留高质量分析内容",
                      "summaryPrompt": "用 80 字总结",
                      "translationPrompt": "翻译成中文",
                      "autoSummaryEnabled": true,
                      "autoTranslationEnabled": false,
                      "outputLanguage": "zh-CN",
                      "provider": "openai"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("provider must be DEEPSEEK"));
  }

  @Test
  void shouldRejectInvalidAiOutputLanguage() throws Exception {
    String token = login();

    mockMvc
        .perform(
            put("/api/settings/ai")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "apiKey": "sk-demo-test",
                      "filterPrompt": "保留高质量分析内容",
                      "summaryPrompt": "用 80 字总结",
                      "translationPrompt": "翻译成中文",
                      "autoSummaryEnabled": true,
                      "autoTranslationEnabled": false,
                      "outputLanguage": "not a language",
                      "provider": "DEEPSEEK"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("outputLanguage must be a BCP 47 language tag"));
  }

  @Test
  void shouldAcceptSchemeLessFeedSourceUrls() throws Exception {
    String token = login();
    String feedUrl = MOCK_WEB_SERVER.url("/feed.xml").toString();
    String schemeLessFeedUrl = feedUrl.replace("http://", "");

    MvcResult createdSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s"
                        }
                        """
                            .formatted(schemeLessFeedUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Sample Feed"))
            .andExpect(jsonPath("$.rssUrl").value(feedUrl))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    try {
      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Sample Feed",
                        "rssUrl": "%s",
                        "folder": "未分组",
                        "enabled": true
                      }
                      """
                          .formatted(schemeLessFeedUrl)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.rssUrl").value(feedUrl));
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldCanonicalizeFeedSourceUrlsBeforeDuplicateChecks() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    String canonicalUrl = baseUrl + "/canonical-feed.xml";
    String noisyUrl =
        (baseUrl + "/folder/../canonical-feed.xml#reader-copy")
            .replace("http://", "HTTP://")
            .replace("localhost", "LOCALHOST");

    MvcResult createdSourceResult =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s"
                        }
                        """
                            .formatted(noisyUrl)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Canonical Feed"))
            .andExpect(jsonPath("$.rssUrl").value(canonicalUrl))
            .andReturn();

    long sourceId =
        TestJson.parse(createdSourceResult.getResponse().getContentAsString()).path("id").asLong();

    try {
      mockMvc
          .perform(
              post("/api/feed-sources")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "rssUrl": "%s#another-copy"
                      }
                      """
                          .formatted(canonicalUrl)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CONFLICT"))
          .andExpect(jsonPath("$.message").value("rss source already exists"));

      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Canonical Feed",
                        "rssUrl": "%s#updated-copy",
                        "folder": "未分组",
                        "enabled": true
                      }
                      """
                          .formatted(canonicalUrl)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.rssUrl").value(canonicalUrl));
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void shouldClearConditionalHeadersAfterFeedSourceUrlChanges() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    long sourceId = createSource(token, baseUrl + "/etag-source.xml");

    try {
      mockMvc
          .perform(
              put("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "name": "Validator Reset Feed",
                        "rssUrl": "%s/validator-reset-target.xml",
                        "folder": "未分组",
                        "enabled": true
                      }
                      """
                          .formatted(baseUrl)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.rssUrl").value(baseUrl + "/validator-reset-target.xml"))
          .andExpect(jsonPath("$.siteUrl").value(baseUrl + "/validator-reset"))
          .andExpect(jsonPath("$.lastFetchedAt").doesNotExist());

      mockMvc
          .perform(
              post("/api/feed-sources/{sourceId}/refresh", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isAccepted());

      Awaitility.await()
          .atMost(10, SECONDS)
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$[0].name").value("Validator Reset Feed"))
                      .andExpect(jsonPath("$[0].hasError").value(false))
                      .andExpect(jsonPath("$[0].lastErrorMessage").doesNotExist()));
    } finally {
      mockMvc
          .perform(
              delete("/api/feed-sources/{sourceId}", sourceId)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isNoContent());
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

  private void ensureAiConfigured(String token) throws Exception {
    mockMvc
        .perform(
            put("/api/settings/ai")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "apiKey": "sk-demo-test",
                      "filterPrompt": "你是一个 RSS 内容筛选助手。判断文章是否属于低价值噪音内容。",
                      "summaryPrompt": "请基于正文生成一段中文摘要，长度控制在 120 字以内。",
                      "translationPrompt": "请把文章按段落翻译成中文，输出严格 JSON 数组。",
                      "autoSummaryEnabled": true,
                      "autoTranslationEnabled": true,
                      "outputLanguage": "zh-CN",
                      "provider": "DEEPSEEK"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true));
  }

  private String sourceField(String token, long sourceId, String field) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode sources = TestJson.parse(result.getResponse().getContentAsString());
    for (JsonNode source : sources) {
      if (source.path("id").asLong() == sourceId) {
        JsonNode value = source.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
      }
    }
    return null;
  }

  private long createSource(String token, String rssUrl) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/feed-sources")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "rssUrl": "%s"
                        }
                        """
                            .formatted(rssUrl)))
            .andExpect(status().isCreated())
            .andReturn();
    return TestJson.parse(result.getResponse().getContentAsString()).path("id").asLong();
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-feed-", ".db");
    } catch (IOException exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }

  private static MockWebServer createServer() {
    try {
      MockWebServer server = new MockWebServer();
      server.setDispatcher(new RssAndAiDispatcher());
      server.start();
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to start mock web server", exception);
    }
  }

  private static final class RssAndAiDispatcher extends Dispatcher {

    private final AtomicInteger mutableFeedRequests = new AtomicInteger();
    private final AtomicInteger mutableArticleRequests = new AtomicInteger();
    private final AtomicInteger movingFeedRequests = new AtomicInteger();
    private final AtomicInteger movingErrorFeedRequests = new AtomicInteger();

    @Override
    public MockResponse dispatch(RecordedRequest request) {
      String path = request.getPath();
      if (path == null) {
        return notFound();
      }
      if (path.equals("/feed.xml")) {
        return xml(sampleFeed());
      }
      if (path.equals("/redirect-feed.xml")) {
        return new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", MOCK_WEB_SERVER.url("/redirected/final-feed.xml").toString());
      }
      if (path.equals("/redirected/final-feed.xml")) {
        return xml(redirectedRelativeFeed());
      }
      if (path.equals("/moving-feed.xml")) {
        if (movingFeedRequests.incrementAndGet() == 1) {
          return xml(initialMovingFeed());
        }
        return new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", MOCK_WEB_SERVER.url("/moved/final-moving.xml").toString());
      }
      if (path.equals("/moved/final-moving.xml")) {
        return xml(finalMovingFeed());
      }
      if (path.equals("/moving-error-feed.xml")) {
        if (movingErrorFeedRequests.incrementAndGet() == 1) {
          return xml(initialMovingErrorFeed());
        }
        return new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", MOCK_WEB_SERVER.url("/moved/missing-moving.xml").toString());
      }
      if (path.equals("/moved/missing-moving.xml")) {
        return notFound();
      }
      if (path.equals("/redirect-discover")) {
        return new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", MOCK_WEB_SERVER.url("/moved/discover/").toString());
      }
      if (path.equals("/moved/discover/")) {
        return html(
            """
            <html>
              <head>
                <title>Redirected Discovery Page</title>
                <link rel="alternate" type="application/rss+xml" title="RSS" href="feed.xml" />
              </head>
              <body>Redirected Discovery Page</body>
            </html>
            """);
      }
      if (path.equals("/moved/discover/feed.xml")) {
        return xml(redirectDiscoveredFeed());
      }
      if (path.equals("/discover")) {
        return html(
            """
            <html>
              <head>
                <title>Discovery Page</title>
                <link rel="alternate" type="application/rss+xml" title="RSS" href="/discover/feed.xml" />
              </head>
              <body>Discovery Page</body>
            </html>
            """);
      }
      if (path.equals("/discover/feed.xml")) {
        return xml(discoveredFeed());
      }
      if (path.equals("/discover-link")) {
        return html(
            """
            <html>
              <head>
                <title>Linked Discovery Page</title>
              </head>
              <body>
                <a href="/discover-link/rss.xml">RSS</a>
              </body>
            </html>
            """);
      }
      if (path.equals("/discover-link/rss.xml")) {
        return xml(linkedFeed());
      }
      if (path.equals("/discover-json")) {
        return html(
            """
            <html>
              <head>
                <title>JSON Feed Discovery Page</title>
                <link rel="alternate" type="application/feed+json" title="JSON Feed" href="/discover-json/feed.json" />
              </head>
              <body>JSON Feed Discovery Page</body>
            </html>
            """);
      }
      if (path.equals("/discover-json/feed.json")) {
        return json(jsonDiscoveryFeed());
      }
      if (path.equals("/discover-json-common")) {
        return html(
            """
            <html>
              <head>
                <title>JSON Common Path Discovery Page</title>
              </head>
              <body>No feed link here.</body>
            </html>
            """);
      }
      if (path.equals("/discover-json-common/feed.json")) {
        return json(jsonDiscoveryFeed("/discover-json-common/"));
      }
      if (path.equals("/discover-update-link")) {
        return html(
            """
            <html>
              <head>
                <title>Linked Update Discovery Page</title>
              </head>
              <body>
                <a href="/discover-update-link/rss.xml">RSS</a>
              </body>
            </html>
            """);
      }
      if (path.equals("/discover-update-link/rss.xml")) {
        return xml(updateLinkedFeed());
      }
      if (path.equals("/discover-common")) {
        return html(
            """
            <html>
              <head>
                <title>Common Path Discovery Page</title>
              </head>
              <body>No alternate link here.</body>
            </html>
            """);
      }
      if (path.equals("/discover-common/feed.xml")) {
        return xml(commonPathFeed());
      }
      if (path.equals("/discover-comments-first")) {
        return html(
            """
            <html>
              <head>
                <title>Comments First Discovery Page</title>
                <link rel="alternate" type="application/rss+xml" title="Comments RSS" href="/discover-comments-first/comments.xml" />
                <link rel="alternate" type="application/rss+xml" title="Posts RSS" href="/discover-comments-first/posts.xml" />
              </head>
              <body>Comments First Discovery Page</body>
            </html>
            """);
      }
      if (path.equals("/discover-comments-first/comments.xml")) {
        return xml(commentsFeed());
      }
      if (path.equals("/discover-comments-first/posts.xml")) {
        return xml(mainPostsFeed());
      }
      if (path.equals("/no-id-feed.xml")) {
        return xml(noIdFeed());
      }
      if (path.equals("/mutable-feed.xml")) {
        return xml(mutableFeed(mutableFeedRequests.incrementAndGet() >= 3));
      }
      if (path.equals("/validation-feed.xml")) {
        return xml(validationFeed());
      }
      if (path.equals("/canonical-feed.xml")) {
        return xml(canonicalFeed());
      }
      if (path.equals("/etag-source.xml")) {
        return xml(validatorSourceFeed())
            .setHeader("ETag", "\"old-validator\"")
            .setHeader("Last-Modified", "Tue, 08 Apr 2026 10:00:00 GMT");
      }
      if (path.equals("/validator-reset-target.xml")) {
        if (request.getHeader("If-None-Match") != null
            || request.getHeader("If-Modified-Since") != null) {
          return new MockResponse().setResponseCode(412);
        }
        return xml(validatorResetFeed());
      }
      if (path.equals("/headers-required-feed.xml")) {
        if (!hasReaderFriendlyHeaders(request)) {
          return new MockResponse().setResponseCode(406);
        }
        return xml(headersRequiredFeed());
      }
      if (path.equals("/articles/1")) {
        return html(
            """
                        <html>
                          <body>
                            <article>
                              <h1>Long Analysis</h1>
                              <p>First paragraph about a long analysis article.</p>
                              <p>Second paragraph with more context.</p>
                              <img src="https://example.com/image.png" />
                              <pre><code>System.out.println("hello");</code></pre>
                            </article>
                          </body>
                        </html>
                        """);
      }
      if (path.equals("/articles/2")) {
        return html(
            """
                        <html>
                          <body>
                            <article>
                              <p>Short update only.</p>
                            </article>
                          </body>
                        </html>
                        """);
      }
      if (path.equals("/discover-json/articles/1")) {
        return html(
            """
                        <html>
                          <body>
                            <article>
                              <h1>JSON Discovery Entry</h1>
                              <p>JSON feed body from the discovered article.</p>
                            </article>
                          </body>
                        </html>
                        """);
      }
      if (path.equals("/mutable-article")) {
        if (mutableArticleRequests.incrementAndGet() == 1) {
          return new MockResponse().setResponseCode(503);
        }
        return html(mutableArticleHtml());
      }
      if (path.equals("/chat/completions")) {
        String body = extractPromptText(request);
        boolean isFilterRequest = body.contains("rss 内容筛选助手") || body.contains("isnoise");
        boolean isSummaryRequest = body.contains("生成一段中文摘要") || body.contains("120");
        boolean isTranslationRequest = body.contains("按段落翻译成中文") || body.contains("translation");

        if (isFilterRequest && body.contains("quick news")) {
          return json(chatContent("{\"isNoise\":true,\"reason\":\"内容过短\"}"));
        }
        if (isFilterRequest && body.contains("long analysis")) {
          return json(chatContent("{\"isNoise\":false,\"reason\":\"有分析\"}"));
        }
        if (isFilterRequest && body.contains("title only entry")) {
          return json(chatContent("{\"isNoise\":false,\"reason\":\"稳定回退条目\"}"));
        }
        if (isFilterRequest && body.contains("header guard entry")) {
          return json(chatContent("{\"isNoise\":false,\"reason\":\"请求头兼容性测试条目\"}"));
        }
        if (isFilterRequest && body.contains("mutable title")) {
          return json(chatContent("{\"isNoise\":false,\"reason\":\"可更新条目\"}"));
        }
        if (isFilterRequest && body.contains("json discovery entry")) {
          return json(chatContent("{\"isNoise\":false,\"reason\":\"JSON Feed 条目\"}"));
        }
        if (isSummaryRequest && body.contains("long analysis")) {
          return json(chatContent("这是一篇关于技术趋势的长文摘要。"));
        }
        if (isSummaryRequest && body.contains("quick news")) {
          return json(chatContent("这是一则简短快讯摘要。"));
        }
        if (isSummaryRequest && body.contains("title only entry")) {
          return json(chatContent("这是一条缺少 guid 和 link 的 RSS 条目。"));
        }
        if (isSummaryRequest && body.contains("header guard entry")) {
          return json(chatContent("这是一条验证 RSS 请求头兼容性的测试条目。"));
        }
        if (isSummaryRequest && body.contains("recovered full article body")) {
          return json(chatContent("这是刷新后恢复全文后的摘要。"));
        }
        if (isSummaryRequest && body.contains("original rss summary")) {
          return json(chatContent("这是刷新前只有 RSS 摘要的测试条目。"));
        }
        if (isSummaryRequest && body.contains("json discovery entry")) {
          return json(chatContent("这是一条 JSON Feed 条目。"));
        }
        if (isTranslationRequest && body.contains("long analysis")) {
          return json(
              chatContent(
                  """
                            [
                              {"source":"First paragraph about a long analysis article.","translation":"第一段讲述了一篇长篇分析文章。"},
                              {"source":"Second paragraph with more context.","translation":"第二段补充了更多上下文。"}
                            ]
                            """));
        }
        if (isTranslationRequest && body.contains("quick news")) {
          return json(
              chatContent(
                  """
                            [
                              {"source":"Short update only.","translation":"仅有一段简短更新。"}
                            ]
                            """));
        }
        if (isTranslationRequest && body.contains("title only entry")) {
          return json(
              chatContent(
                  """
                            [
                              {"source":"Stable fallback summary","translation":"稳定回退摘要。"}
                            ]
                            """));
        }
        if (isTranslationRequest && body.contains("header guard entry")) {
          return json(
              chatContent(
                  """
                            [
                              {"source":"Header guarded summary","translation":"带请求头保护的摘要。"}
                            ]
                            """));
        }
        if (isTranslationRequest && body.contains("mutable title")) {
          return json(
              chatContent(
                  """
                            [
                              {"source":"Original RSS summary","translation":"原始 RSS 摘要。"}
                            ]
                            """));
        }
        if (isTranslationRequest && body.contains("json discovery entry")) {
          return json(
              chatContent(
                  """
                            [
                              {"source":"JSON feed body from the discovered article.","translation":"来自已发现文章的 JSON Feed 正文。"}
                            ]
                            """));
        }
        return new MockResponse().setResponseCode(500);
      }
      return notFound();
    }

    private MockResponse notFound() {
      return new MockResponse().setResponseCode(404);
    }

    private MockResponse xml(String body) {
      return new MockResponse()
          .setHeader("Content-Type", "application/rss+xml; charset=utf-8")
          .setBody(body);
    }

    private MockResponse html(String body) {
      return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body);
    }

    private MockResponse json(String body) {
      return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private boolean hasReaderFriendlyHeaders(RecordedRequest request) {
      String userAgent = request.getHeader("User-Agent");
      String accept = request.getHeader("Accept");
      return userAgent != null
          && userAgent.startsWith("RSSCopilot/")
          && accept != null
          && accept.contains("application/rss+xml")
          && accept.contains("application/atom+xml")
          && accept.contains("application/feed+json");
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
                          <author>Jane Analyst</author>
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

    private String discoveredFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Discovered Feed</title>
                        <link>%s/discover</link>
                        <description>Discovered feed description</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String linkedFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Linked Feed</title>
                        <link>%s/discover-link</link>
                        <description>Feed found from a body RSS link</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String redirectedRelativeFeed() {
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Redirected Relative Feed</title>
                        <link>./</link>
                        <description>Feed reached through a redirect.</description>
                        <item>
                          <title>Redirect Relative Entry</title>
                          <link>entry</link>
                          <guid>redirect-relative-entry</guid>
                          <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                          <description><![CDATA[Entry with a relative link.]]></description>
                        </item>
                      </channel>
                    </rss>
                    """;
    }

    private String initialMovingFeed() {
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Initial Moving Feed</title>
                        <link>/</link>
                        <description>Feed before the origin moves it.</description>
                      </channel>
                    </rss>
                    """;
    }

    private String finalMovingFeed() {
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Final Moving Feed</title>
                        <link>./</link>
                        <description>Feed after the origin moves it.</description>
                        <item>
                          <title>Moved Feed Entry</title>
                          <link>entry</link>
                          <guid>moved-feed-entry</guid>
                          <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                          <description><![CDATA[Entry from the moved feed.]]></description>
                        </item>
                      </channel>
                    </rss>
                    """;
    }

    private String initialMovingErrorFeed() {
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Initial Moving Error Feed</title>
                        <link>/</link>
                        <description>Feed before the origin moves to an error page.</description>
                      </channel>
                    </rss>
                    """;
    }

    private String redirectDiscoveredFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Redirect Discovered Feed</title>
                        <link>%s/moved/discover/</link>
                        <description>Feed discovered relative to a redirected page URL.</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String jsonDiscoveryFeed() {
      return jsonDiscoveryFeed("/discover-json/");
    }

    private String jsonDiscoveryFeed(String sitePath) {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    {
                      "version": "https://jsonfeed.org/version/1.1",
                      "title": "JSON Discovery Feed",
                      "home_page_url": "%s%s",
                      "items": [
                        {
                          "id": "json-discovery-entry",
                          "url": "%s/discover-json/articles/1",
                          "title": "JSON Discovery Entry",
                          "content_text": "JSON feed body",
                          "date_published": "2026-04-08T12:00:00Z"
                        }
                      ]
                    }
                    """
          .formatted(baseUrl, sitePath, baseUrl);
    }

    private String updateLinkedFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Update Linked Feed</title>
                        <link>%s/discover-update-link</link>
                        <description>Feed found from a body RSS link during update</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String commonPathFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Common Path Feed</title>
                        <link>%s/discover-common</link>
                        <description>Feed found from a common path candidate</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String commentsFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Comments Feed</title>
                        <link>%s/discover-comments-first/comments</link>
                        <description>Lower confidence comments feed</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String mainPostsFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Main Posts Feed</title>
                        <link>%s/discover-comments-first</link>
                        <description>Main posts feed should win over comments</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String noIdFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>No ID Feed</title>
                        <link>%s/no-id</link>
                        <description>Feed with entries that omit guid and item link</description>
                        <item>
                          <title>Title Only Entry</title>
                          <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                          <description><![CDATA[Stable fallback summary]]></description>
                        </item>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String mutableFeed(boolean revised) {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      String title = revised ? "Revised Mutable Title" : "Original Mutable Title";
      String summary = revised ? "Revised RSS summary" : "Original RSS summary";
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Mutable Feed</title>
                        <link>%s/mutable</link>
                        <description>Feed that revises an existing entry</description>
                        <item>
                          <title>%s</title>
                          <link>%s/mutable-article</link>
                          <guid>mutable-entry</guid>
                          <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                          <description><![CDATA[%s]]></description>
                        </item>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl, title, baseUrl, summary);
    }

    private String mutableArticleHtml() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <html>
                      <body>
                        <article>
                          <h1>Revised Mutable Title</h1>
                          <p>Recovered full article body after the original page came back.</p>
                          <img src="%s/images/mutable-cover.png" />
                        </article>
                      </body>
                    </html>
                    """
          .formatted(baseUrl);
    }

    private String validationFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Validation Feed</title>
                        <link>%s/validation</link>
                        <description>Feed used for validation tests</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String canonicalFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Canonical Feed</title>
                        <link>%s/canonical</link>
                        <description>Feed used for URL canonicalization tests</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String validatorSourceFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Validator Source Feed</title>
                        <link>%s/etag-source</link>
                        <description>Feed with cache validators</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String validatorResetFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Validator Reset Feed</title>
                        <link>%s/validator-reset</link>
                        <description>Feed that rejects stale validators from other sources</description>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
    }

    private String headersRequiredFeed() {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0">
                      <channel>
                        <title>Headers Required Feed</title>
                        <link>%s/headers-required</link>
                        <description>Feed that requires reader-style headers</description>
                        <item>
                          <title>Header Guard Entry</title>
                          <guid>header-guard-entry</guid>
                          <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                          <description><![CDATA[Header guarded summary]]></description>
                        </item>
                      </channel>
                    </rss>
                    """
          .formatted(baseUrl);
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
