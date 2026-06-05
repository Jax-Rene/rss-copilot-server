package com.rsscopilot.server.feed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsscopilot.server.support.TestJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
class OpmlIntegrationTest {

  private static final Path DB_PATH = createDbPath();
  private static final MockWebServer MOCK_WEB_SERVER = createServer();

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
    registry.add("app.bootstrap.default-user.email", () -> "demo@example.com");
    registry.add("app.bootstrap.default-user.password", () -> "pass123456");
  }

  @BeforeEach
  void resetFeedData() {
    jdbcTemplate.update("DELETE FROM sync_tombstone");
    jdbcTemplate.update("DELETE FROM user_entry_state");
    jdbcTemplate.update("DELETE FROM ai_result_filter");
    jdbcTemplate.update("DELETE FROM ai_result_summary");
    jdbcTemplate.update("DELETE FROM ai_result_translation");
    jdbcTemplate.update("DELETE FROM feed_entry");
    jdbcTemplate.update("DELETE FROM feed_source");
  }

  @AfterAll
  static void cleanup() throws Exception {
    MOCK_WEB_SERVER.shutdown();
    Files.deleteIfExists(DB_PATH);
  }

  @Test
  void shouldImportAndExportOpmlSubscriptions() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(sampleOpml())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(2))
        .andExpect(jsonPath("$.skippedCount").value(2))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(0))
        .andExpect(jsonPath("$.sources.length()").value(2));

    MvcResult sourcesResult =
        mockMvc
            .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn();
    assertThat(
            TestJson.parse(sourcesResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .findValuesAsText("name"))
        .containsExactlyInAnyOrder("Example & Analysis", "Nested Feed");
    assertThat(
            TestJson.parse(sourcesResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .findValuesAsText("folder"))
        .containsExactlyInAnyOrder("未分组", "Folder");

    MvcResult exportResult =
        mockMvc
            .perform(get("/api/feed-sources/opml").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String exported = exportResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(exported).contains("<opml version=\"2.0\">");
    assertThat(exported).contains("text=\"Folder\"");
    assertThat(exported).contains("Example &amp; Analysis");
    assertThat(exported).contains("Nested Feed");
    assertThat(exported).contains("xmlUrl=\"https://example.com/feed.xml\"");
    assertThat(exported).contains("category=\"/Folder\"");
    assertThat(exported).contains("htmlUrl=\"https://nested.example.com\"");

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(sampleOpml())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(0))
        .andExpect(jsonPath("$.skippedCount").value(4))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(0));
  }

  @Test
  void shouldImportMixedCaseOpmlAttributesAndNestedFolders() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(mixedCaseNestedOpml())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(2))
        .andExpect(jsonPath("$.skippedCount").value(0))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(0));

    MvcResult sourcesResult =
        mockMvc
            .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String body = sourcesResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(TestJson.parse(body).findValuesAsText("name"))
        .containsExactlyInAnyOrder("Deep Feed", "Lower Feed");
    assertThat(TestJson.parse(body).findValuesAsText("folder"))
        .containsExactlyInAnyOrder("Parent / Child", "未分组");
    assertThat(TestJson.parse(body).findValuesAsText("rssUrl"))
        .containsExactlyInAnyOrder("https://deep.example/rss", "https://example.org/rss.xml");
    assertThat(TestJson.parse(body).findValuesAsText("siteUrl"))
        .containsExactlyInAnyOrder("https://deep.example", "https://example.org");

    MvcResult exportResult =
        mockMvc
            .perform(get("/api/feed-sources/opml").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String exported = exportResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(exported).contains("<outline text=\"Parent\" title=\"Parent\">");
    assertThat(exported).contains("<outline text=\"Child\" title=\"Child\">");
    assertThat(exported).contains("xmlUrl=\"https://deep.example/rss\"");
    assertThat(exported).contains("category=\"/Parent/Child\"");
    assertThat(exported).contains("<outline text=\"Lower Feed\"");
  }

  @Test
  void shouldImportFlatOpmlCategoryAsFolder() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(categoryOpml())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(3))
        .andExpect(jsonPath("$.skippedCount").value(0))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(0));

    MvcResult sourcesResult =
        mockMvc
            .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String body = sourcesResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(TestJson.parse(body).findValuesAsText("name"))
        .containsExactlyInAnyOrder("AI Notes", "Growth Feed", "Nested Category Wins");
    assertThat(TestJson.parse(body).findValuesAsText("folder"))
        .containsExactlyInAnyOrder("Engineering / AI", "Growth", "Parent Folder");
  }

  @Test
  void shouldExportOpmlInStableFolderAndSourceOrder() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    insertSource(userId, "Zoo Root", "https://zoo.example/rss", "未分组");
    insertSource(userId, "Alpha Root", "https://alpha.example/rss", "未分组");
    insertSource(userId, "Beta Zebra", "https://beta-zebra.example/rss", "Beta Folder");
    insertSource(userId, "Beta Alpha", "https://beta-alpha.example/rss", "Beta Folder");
    insertSource(userId, "Child B Feed", "https://child-b.example/rss", "Alpha Folder / Child B");
    insertSource(userId, "Child A Feed", "https://child-a.example/rss", "Alpha Folder / Child A");

    MvcResult exportResult =
        mockMvc
            .perform(get("/api/feed-sources/opml").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String exported = exportResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(exported.indexOf("Alpha Root")).isLessThan(exported.indexOf("Zoo Root"));
    assertThat(exported.indexOf("Zoo Root")).isLessThan(exported.indexOf("Alpha Folder"));
    assertThat(exported.indexOf("Alpha Folder")).isLessThan(exported.indexOf("Beta Folder"));
    assertThat(exported.indexOf("Child A")).isLessThan(exported.indexOf("Child B"));
    assertThat(exported.indexOf("Beta Alpha")).isLessThan(exported.indexOf("Beta Zebra"));
  }

  @Test
  void shouldImportUppercaseOpmlElements() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(uppercaseElementsOpml())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(1))
        .andExpect(jsonPath("$.skippedCount").value(0))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(0));

    MvcResult sourcesResult =
        mockMvc
            .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String body = sourcesResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(TestJson.parse(body).findValuesAsText("name")).containsExactly("Caps Feed");
    assertThat(TestJson.parse(body).findValuesAsText("folder")).containsExactly("Caps Folder");
    assertThat(TestJson.parse(body).findValuesAsText("rssUrl"))
        .containsExactly("https://caps.example/rss.xml");
  }

  @Test
  void shouldImportOpmlWithUtf8Bom() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(utf8BomOpml())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(1))
        .andExpect(jsonPath("$.skippedCount").value(0))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(0));

    MvcResult sourcesResult =
        mockMvc
            .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String body = sourcesResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    assertThat(TestJson.parse(body).findValuesAsText("name")).containsExactly("BOM Feed");
    assertThat(TestJson.parse(body).findValuesAsText("folder")).containsExactly("Migrated / UTF-8");
    assertThat(TestJson.parse(body).findValuesAsText("rssUrl"))
        .containsExactly("https://bom.example/rss.xml");
  }

  @Test
  void shouldRejectNonOpmlXmlDocuments() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(nonOpmlXmlWithOutline())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid opml document"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldRejectOpmlDocumentsWithExternalEntities() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(externalEntityOpml())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid opml document"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldRejectOversizedOpmlDocuments() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(oversizedOpml())))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
        .andExpect(jsonPath("$.message").value("opml document is too large"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldRejectOpmlDocumentsWithTooManySubscriptions() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(opmlWithSubscriptions(1_001))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("opml contains too many subscriptions"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldRejectOpmlDocumentsWithoutSubscriptions() throws Exception {
    String token = login();

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(opmlWithoutSubscriptions())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("opml contains no rss subscriptions"));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldRefreshImportedSourcesAfterTransactionCommit() throws Exception {
    String token = login();
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(refreshableOpml(baseUrl), true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(2))
        .andExpect(jsonPath("$.skippedCount").value(0))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(2));

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
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].title").value("Imported Two"))
                    .andExpect(jsonPath("$.items[1].title").value("Imported One")));

    mockMvc
        .perform(get("/api/feed-sources").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].lastFetchedAt").exists())
        .andExpect(jsonPath("$[0].hasError").value(false))
        .andExpect(jsonPath("$[1].lastFetchedAt").exists())
        .andExpect(jsonPath("$[1].hasError").value(false));
  }

  @Test
  void shouldRefreshOnlyNewlyImportedSourcesAfterTransactionCommit() throws Exception {
    String token = login();
    Long userId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM user_account WHERE email = ?", Long.class, "demo@example.com");
    String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
    String existingRssUrl = baseUrl + "/existing-before-import.xml";
    insertSource(userId, "Existing Before Import", existingRssUrl);

    mockMvc
        .perform(
            post("/api/feed-sources/opml/import")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonForOpml(refreshableOpml(baseUrl), true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.importedCount").value(2))
        .andExpect(jsonPath("$.skippedCount").value(0))
        .andExpect(jsonPath("$.refreshAcceptedCount").value(2));

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
                    .andExpect(jsonPath("$.items.length()").value(2)));

    String existingStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM feed_source WHERE user_id = ? AND rss_url = ?",
            String.class,
            userId,
            existingRssUrl);
    String existingLastFetchedAt =
        jdbcTemplate.queryForObject(
            "SELECT last_fetched_at FROM feed_source WHERE user_id = ? AND rss_url = ?",
            String.class,
            userId,
            existingRssUrl);
    String existingLastErrorMessage =
        jdbcTemplate.queryForObject(
            "SELECT last_error_message FROM feed_source WHERE user_id = ? AND rss_url = ?",
            String.class,
            userId,
            existingRssUrl);

    assertThat(existingStatus).isEqualTo("IDLE");
    assertThat(existingLastFetchedAt).isNull();
    assertThat(existingLastErrorMessage).isNull();
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

  private void insertSource(long userId, String name, String rssUrl) {
    insertSource(userId, name, rssUrl, "Existing");
  }

  private void insertSource(long userId, String name, String rssUrl, String folder) {
    jdbcTemplate.update(
        """
            INSERT INTO feed_source(
                user_id, name, rss_url, site_url, icon_url, folder, enabled, status, etag,
                last_modified, last_fetched_at, last_error_at, last_error_message, created_at, updated_at
            )
            VALUES(?, ?, ?, NULL, NULL, ?, 1, 'IDLE', NULL, NULL, NULL, NULL, NULL,
                   '2026-04-08T00:00:00Z', '2026-04-08T00:00:00Z')
            """,
        userId,
        name,
        rssUrl,
        folder);
  }

  private static String sampleOpml() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>Reader export</title></head>
          <body>
            <outline text="Example &amp; Analysis" xmlUrl="https://example.com/feed.xml" />
            <outline text="Duplicate" xmlUrl="https://example.com/feed.xml" />
            <outline text="Broken" xmlUrl="://not-a-url" />
            <outline text="Folder">
              <outline title="Nested Feed" xmlUrl="https://nested.example.com/rss" htmlUrl="https://nested.example.com" />
            </outline>
          </body>
        </opml>
        """;
  }

  private static String mixedCaseNestedOpml() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <outline TEXT="Parent">
              <outline Title="Child">
                <outline TEXT="Deep Feed" XMLURL="https://deep.example/rss" HTMLURL="https://deep.example" />
              </outline>
            </outline>
            <outline TEXT="Lower Feed" xmlurl="example.org/rss.xml" htmlurl="https://example.org" />
          </body>
        </opml>
        """;
  }

  private static String uppercaseElementsOpml() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <OPML version="2.0">
          <BODY>
            <OUTLINE TEXT="Caps Folder">
              <OUTLINE TEXT="Caps Feed" XMLURL="caps.example/rss.xml" />
            </OUTLINE>
          </BODY>
        </OPML>
        """;
  }

  private static String categoryOpml() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <outline text="AI Notes" xmlUrl="https://ai.example/rss.xml" category="/Engineering/AI" />
            <outline text="Growth Feed" xmlUrl="https://growth.example/rss.xml" category="Growth,Later" />
            <outline text="Parent Folder">
              <outline text="Nested Category Wins" xmlUrl="https://nested-category.example/rss.xml" category="Ignored" />
            </outline>
          </body>
        </opml>
        """;
  }

  private static String utf8BomOpml() {
    return "\uFEFF"
        + """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <outline text="Migrated">
              <outline text="UTF-8">
                <outline text="BOM Feed" xmlUrl="https://bom.example/rss.xml" />
              </outline>
            </outline>
          </body>
        </opml>
        """;
  }

  private static String externalEntityOpml() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE opml [
          <!ENTITY secret SYSTEM "file:///etc/passwd">
        ]>
        <opml version="2.0">
          <body>
            <outline text="&secret;" xmlUrl="https://example.com/feed.xml" />
          </body>
        </opml>
        """;
  }

  private static String nonOpmlXmlWithOutline() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <html>
          <body>
            <outline text="Not OPML" xmlUrl="https://example.com/feed.xml" />
          </body>
        </html>
        """;
  }

  private static String oversizedOpml() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0"><body>
        """
        + " ".repeat(1_000_001)
        + """
        </body></opml>
        """;
  }

  private static String opmlWithSubscriptions(int count) {
    StringBuilder builder =
        new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml><body>");
    for (int index = 0; index < count; index += 1) {
      builder
          .append("<outline text=\"Feed ")
          .append(index)
          .append("\" xmlUrl=\"https://example.com/feed-")
          .append(index)
          .append(".xml\" />");
    }
    return builder.append("</body></opml>").toString();
  }

  private static String opmlWithoutSubscriptions() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <outline text="Imported Folder">
              <outline text="Nested Empty Folder" />
            </outline>
          </body>
        </opml>
        """;
  }

  private static String refreshableOpml(String baseUrl) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>Refresh import</title></head>
          <body>
            <outline text="Refresh One" xmlUrl="%s/refresh-one.xml" />
            <outline text="Refresh Folder">
              <outline text="Refresh Two" xmlUrl="%s/refresh-two.xml" />
            </outline>
          </body>
        </opml>
        """
        .formatted(baseUrl, baseUrl);
  }

  private static String jsonForOpml(String opml) {
    return jsonForOpml(opml, false);
  }

  private static String jsonForOpml(String opml, boolean refreshAfterImport) {
    return """
        {
          "opml": "%s",
          "refreshAfterImport": %s
        }
        """
        .formatted(escapeJson(opml), refreshAfterImport);
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private static Path createDbPath() {
    try {
      return Files.createTempFile("rss-copilot-opml-", ".db");
    } catch (Exception exception) {
      throw new IllegalStateException("failed to create temp db", exception);
    }
  }

  private static MockWebServer createServer() {
    try {
      MockWebServer server = new MockWebServer();
      server.setDispatcher(new RefreshImportDispatcher());
      server.start();
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to start mock web server", exception);
    }
  }

  private static final class RefreshImportDispatcher extends Dispatcher {

    @Override
    public MockResponse dispatch(RecordedRequest request) {
      String path = request.getPath();
      if (path == null) {
        return new MockResponse().setResponseCode(404);
      }
      if (path.equals("/refresh-one.xml")) {
        return xml(refreshFeed("Imported One Feed", "Imported One", "imported-one", 9));
      }
      if (path.equals("/refresh-two.xml")) {
        return xml(refreshFeed("Imported Two Feed", "Imported Two", "imported-two", 10));
      }
      if (path.equals("/articles/imported-one") || path.equals("/articles/imported-two")) {
        return html("<html><body><article>Imported article body</article></body></html>");
      }
      return new MockResponse().setResponseCode(404);
    }

    private static String refreshFeed(String feedTitle, String entryTitle, String slug, int hour) {
      String baseUrl = MOCK_WEB_SERVER.url("").toString().replaceAll("/$", "");
      return """
          <?xml version="1.0" encoding="UTF-8" ?>
          <rss version="2.0">
            <channel>
              <title>%s</title>
              <link>%s</link>
              <description>Imported feed</description>
              <item>
                <title>%s</title>
                <link>%s/articles/%s</link>
                <guid>%s-guid</guid>
                <pubDate>Tue, 08 Apr 2026 %02d:00:00 GMT</pubDate>
                <description><![CDATA[Imported summary]]></description>
              </item>
            </channel>
          </rss>
          """
          .formatted(feedTitle, baseUrl, entryTitle, baseUrl, slug, slug, hour);
    }

    private static MockResponse xml(String body) {
      return new MockResponse()
          .setHeader("Content-Type", "application/rss+xml; charset=utf-8")
          .setBody(body);
    }

    private static MockResponse html(String body) {
      return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body);
    }
  }
}
