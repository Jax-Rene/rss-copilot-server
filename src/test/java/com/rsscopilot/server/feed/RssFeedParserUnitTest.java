package com.rsscopilot.server.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsscopilot.server.common.BadRequestException;
import org.junit.jupiter.api.Test;

class RssFeedParserUnitTest {

  private final RssFeedParser rssFeedParser = new RssFeedParser();

  @Test
  void shouldParseFeedMetadataAndEntries() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Parser Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Entry One</title>
                  <link>https://example.com/articles/1</link>
                  <guid>guid-1</guid>
                  <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
                  <description><![CDATA[
                    <p>Summary text</p>
                    <img src="https://example.com/cover.png" />
                  ]]></description>
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.title()).isEqualTo("Parser Feed");
    assertThat(parsedFeed.siteUrl()).isEqualTo("https://example.com");
    assertThat(parsedFeed.iconUrl()).isEqualTo("https://example.com/favicon.ico");
    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).externalId()).isEqualTo("guid-1");
    assertThat(parsedFeed.entries().get(0).summaryText()).contains("Summary text");
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/cover.png");
  }

  @Test
  void shouldThrowBadRequestForInvalidXml() {
    assertThatThrownBy(() -> rssFeedParser.parse("not-rss", "https://bad.example/feed.xml"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalid rss feed: https://bad.example/feed.xml");
  }
}
