package com.rsscopilot.server.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.rsscopilot.server.support.TestJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ServerE2ETest {

  private static final Path DB_PATH = createDbPath();
  private static final MockWebServer MOCK_WEB_SERVER = createServer();

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate testRestTemplate;

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
  void shouldPassMainWorkflowOverRealHttp() {
    String baseUrl = "http://localhost:" + port;
    String token = login(baseUrl);

    ResponseEntity<String> createResponse =
        testRestTemplate.exchange(
            baseUrl + "/api/feed-sources",
            HttpMethod.POST,
            authorizedJsonEntity(
                token,
                Map.of("rssUrl", MOCK_WEB_SERVER.url("/feed.xml").toString().replaceAll("/$", ""))),
            String.class);

    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(TestJson.parse(createResponse.getBody()).path("name").asText())
        .isEqualTo("Sample Feed");

    ResponseEntity<String> refreshResponse =
        testRestTemplate.exchange(
            baseUrl + "/api/feed-sources/refresh",
            HttpMethod.POST,
            authorizedEntity(token),
            String.class);
    assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    Awaitility.await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              ResponseEntity<String> feedResponse =
                  testRestTemplate.exchange(
                      baseUrl + "/api/entries?view=feed",
                      HttpMethod.GET,
                      authorizedEntity(token),
                      String.class);
              assertThat(feedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(TestJson.parse(feedResponse.getBody()).path("items")).hasSize(1);
            });

    ResponseEntity<String> noiseResponse =
        testRestTemplate.exchange(
            baseUrl + "/api/entries?view=noise",
            HttpMethod.GET,
            authorizedEntity(token),
            String.class);
    assertThat(noiseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(TestJson.parse(noiseResponse.getBody()).path("items")).hasSize(1);

    ResponseEntity<String> syncResponse =
        testRestTemplate.exchange(
            baseUrl + "/api/sync/bootstrap", HttpMethod.GET, authorizedEntity(token), String.class);
    assertThat(syncResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(TestJson.parse(syncResponse.getBody()).path("sources")).hasSize(1);
    assertThat(TestJson.parse(syncResponse.getBody()).path("entries")).hasSize(2);
  }

  private String login(String baseUrl) {
    ResponseEntity<String> loginResponse =
        testRestTemplate.postForEntity(
            baseUrl + "/api/auth/login",
            jsonEntity(Map.of("email", "demo@example.com", "password", "pass123456")),
            String.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return TestJson.parse(loginResponse.getBody()).path("token").asText();
  }

  private HttpEntity<Map<String, Object>> jsonEntity(Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(Map.copyOf(body), headers);
  }

  private HttpEntity<Map<String, Object>> authorizedJsonEntity(String token, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    return new HttpEntity<>(Map.copyOf(body), headers);
  }

  private HttpEntity<Void> authorizedEntity(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return new HttpEntity<>(headers);
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-e2e-", ".db");
    } catch (IOException exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }

  private static MockWebServer createServer() {
    try {
      MockWebServer server = new MockWebServer();
      server.setDispatcher(new E2EDispatcher());
      server.start();
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to start mock web server", exception);
    }
  }

  private static final class E2EDispatcher extends Dispatcher {

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
        return html("<html><body><article><p>Short update only.</p></article></body></html>");
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
          return json(chatContent("E2E 摘要"));
        }
        if (isSummaryRequest && body.contains("quick news")) {
          return json(chatContent("E2E 短摘要"));
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
