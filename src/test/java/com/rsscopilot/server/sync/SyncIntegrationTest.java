package com.rsscopilot.server.sync;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsscopilot.server.support.TestJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
class SyncIntegrationTest {

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
        .andExpect(jsonPath("$.settings.ai.provider").value("DEEPSEEK"));

    mockMvc
        .perform(
            get("/api/sync/changes")
                .header("Authorization", "Bearer " + token)
                .param("since", "1970-01-01T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources.length()").value(1))
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.deletedSourceIds.length()").value(0))
        .andExpect(jsonPath("$.settings.ai.provider").value("DEEPSEEK"));
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
