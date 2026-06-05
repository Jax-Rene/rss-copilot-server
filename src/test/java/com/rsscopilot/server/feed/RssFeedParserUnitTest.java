package com.rsscopilot.server.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsscopilot.server.common.BadRequestException;
import java.time.Instant;
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
  void shouldParseDecodedXmlEvenWhenDeclarationKeepsOriginalEncoding() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="GBK" ?>
            <rss version="2.0">
              <channel>
                <title>中文订阅</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>早报：市场更新</title>
                  <link>https://example.com/articles/morning</link>
                  <guid>guid-cn-morning</guid>
                  <description><![CDATA[<p>这是一段已经按声明解码后的正文。</p>]]></description>
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.title()).isEqualTo("中文订阅");
    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).title()).isEqualTo("早报：市场更新");
    assertThat(parsedFeed.entries().get(0).summaryText()).contains("已经按声明解码");
  }

  @Test
  void shouldParseXmlFeedsWithUtf8Bom() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            "\uFEFF"
                + """
                <?xml version="1.0" encoding="UTF-8" ?>
                <rss version="2.0">
                  <channel>
                    <title>BOM RSS Feed</title>
                    <link>https://example.com</link>
                    <description>Sample</description>
                    <item>
                      <title>BOM Entry</title>
                      <link>https://example.com/articles/bom</link>
                      <guid>guid-bom</guid>
                      <description><![CDATA[<p>BOM body</p>]]></description>
                    </item>
                  </channel>
                </rss>
                """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.title()).isEqualTo("BOM RSS Feed");
    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).externalId()).isEqualTo("guid-bom");
    assertThat(parsedFeed.entries().get(0).summaryText()).contains("BOM body");
  }

  @Test
  void shouldParseJsonFeedMetadataAndEntries() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "JSON Feed Blog",
              "home_page_url": "https://example.com/blog/",
              "icon": "/assets/icon.png",
              "items": [
                {
                  "id": "json-entry-1",
                  "url": "/blog/json-entry",
                  "title": "JSON Feed Entry",
                  "content_html": "<p>Full JSON body</p><img src='/images/inline.jpg'>",
                  "date_published": "2026-04-08T10:00:00Z",
                  "authors": [{"name": "Jane JSON"}],
                  "image": "/images/cover.jpg"
                }
              ]
            }
            """,
            "https://example.com/feed.json");

    assertThat(parsedFeed.title()).isEqualTo("JSON Feed Blog");
    assertThat(parsedFeed.siteUrl()).isEqualTo("https://example.com/blog/");
    assertThat(parsedFeed.iconUrl()).isEqualTo("https://example.com/assets/icon.png");
    assertThat(parsedFeed.entries()).hasSize(1);
    RssParsedFeed.RssParsedFeedEntry entry = parsedFeed.entries().get(0);
    assertThat(entry.externalId()).isEqualTo("json-entry-1");
    assertThat(entry.link()).isEqualTo("https://example.com/blog/json-entry");
    assertThat(entry.author()).isEqualTo("Jane JSON");
    assertThat(entry.summaryHtml()).contains("Full JSON body");
    assertThat(entry.summaryText()).isEqualTo("Full JSON body");
    assertThat(entry.coverImageUrl()).isEqualTo("https://example.com/images/cover.jpg");
    assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-04-08T10:00:00Z"));
  }

  @Test
  void shouldParseJsonFeedsWithUtf8Bom() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            "\uFEFF"
                + """
                {
                  "version": "https://jsonfeed.org/version/1.1",
                  "title": "BOM JSON Feed",
                  "home_page_url": "https://example.com/blog/",
                  "items": [
                    {
                      "id": "json-bom-entry",
                      "url": "/blog/json-bom",
                      "content_text": "BOM JSON body"
                    }
                  ]
                }
                """,
            "https://example.com/feed.json");

    assertThat(parsedFeed.title()).isEqualTo("BOM JSON Feed");
    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).externalId()).isEqualTo("json-bom-entry");
    assertThat(parsedFeed.entries().get(0).summaryText()).isEqualTo("BOM JSON body");
  }

  @Test
  void shouldParseJsonFeedTextOnlyItemsWithFallbacks() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "Text JSON Feed",
              "items": [
                {
                  "url": "https://example.com/text-only",
                  "content_text": "Plain text JSON body",
                  "date_modified": "2026-04-09T08:30:00Z",
                  "author": {"url": "https://example.com/about"}
                }
              ]
            }
            """,
            "https://example.com/feed.json");

    assertThat(parsedFeed.entries()).hasSize(1);
    RssParsedFeed.RssParsedFeedEntry entry = parsedFeed.entries().get(0);
    assertThat(entry.externalId()).isEqualTo("https://example.com/text-only");
    assertThat(entry.title()).isEqualTo("https://example.com/text-only");
    assertThat(entry.author()).isEqualTo("https://example.com/about");
    assertThat(entry.summaryHtml()).isEqualTo("Plain text JSON body");
    assertThat(entry.summaryText()).isEqualTo("Plain text JSON body");
    assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-04-09T08:30:00Z"));
  }

  @Test
  void shouldUseJsonFeedAuthorForItemsWithoutAuthor() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "Author JSON Feed",
              "authors": [{"name": "Site Author"}],
              "items": [
                {
                  "id": "feed-author-entry",
                  "url": "https://example.com/feed-author",
                  "content_text": "Story body"
                }
              ]
            }
            """,
            "https://example.com/feed.json");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).author()).isEqualTo("Site Author");
  }

  @Test
  void shouldPreferJsonItemAuthorOverFeedAuthor() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "Author JSON Feed",
              "author": {"name": "Site Author"},
              "items": [
                {
                  "id": "item-author-entry",
                  "url": "https://example.com/item-author",
                  "content_text": "Story body",
                  "authors": [{"name": "Entry Author"}]
                }
              ]
            }
            """,
            "https://example.com/feed.json");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).author()).isEqualTo("Entry Author");
  }

  @Test
  void shouldUseJsonFeedInlineImageAsCoverFallback() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "Inline Image JSON Feed",
              "home_page_url": "https://example.com/blog/",
              "items": [
                {
                  "id": "inline-image",
                  "url": "/posts/inline-image",
                  "content_html": "<p>Story body</p><img src=\\"../images/inline-cover.webp\\">"
                }
              ]
            }
            """,
            "https://example.com/blog/feed.json");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/images/inline-cover.webp");
  }

  @Test
  void shouldUseJsonFeedImageAttachmentAsCoverFallback() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "Attachment JSON Feed",
              "home_page_url": "https://example.com/blog/",
              "items": [
                {
                  "id": "attachment-image",
                  "url": "/posts/attachment-image",
                  "content_text": "Story body",
                  "attachments": [
                    {"url": "/downloads/story.pdf", "mime_type": "application/pdf"},
                    {"url": "/media/attachment-cover.jpg", "mime_type": "image/jpeg"}
                  ]
                }
              ]
            }
            """,
            "https://example.com/blog/feed.json");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/media/attachment-cover.jpg");
  }

  @Test
  void shouldThrowBadRequestForInvalidXml() {
    assertThatThrownBy(() -> rssFeedParser.parse("not-rss", "https://bad.example/feed.xml"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalid rss feed: https://bad.example/feed.xml");
  }

  @Test
  void shouldRejectJsonDocumentsWithoutJsonFeedVersion() {
    assertThatThrownBy(
            () ->
                rssFeedParser.parse(
                    """
                    {
                      "title": "Plain API Response",
                      "items": [
                        {
                          "title": "Not a feed",
                          "url": "https://example.com/not-feed"
                        }
                      ]
                    }
                    """,
                    "https://bad.example/feed.json"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalid rss feed: https://bad.example/feed.json");
  }

  @Test
  void shouldRejectXmlFeedsWithDoctypeDeclarations() {
    assertThatThrownBy(
            () ->
                rssFeedParser.parse(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE rss [
                      <!ENTITY secret SYSTEM "file:///etc/passwd">
                    ]>
                    <rss version="2.0">
                      <channel>
                        <title>&secret;</title>
                        <link>https://example.com</link>
                        <description>Sample</description>
                      </channel>
                    </rss>
                    """,
                    "https://bad.example/feed.xml"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalid rss feed: https://bad.example/feed.xml");
  }

  @Test
  void shouldUseAtomIconAsFeedIcon() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Feed</title>
              <link href="https://example.com/" />
              <icon>/assets/atom-icon.svg</icon>
              <entry>
                <title>Atom Entry</title>
                <id>tag:example.com,2026:1</id>
                <link href="https://example.com/posts/atom" />
                <updated>2026-04-08T10:00:00Z</updated>
                <summary>Atom summary</summary>
              </entry>
            </feed>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.siteUrl()).isEqualTo("https://example.com/");
    assertThat(parsedFeed.iconUrl()).isEqualTo("https://example.com/assets/atom-icon.svg");
  }

  @Test
  void shouldPreferAtomAlternateLinksForSiteAndEntries() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Link Feed</title>
              <link rel="self" href="https://example.com/feed.xml" type="application/atom+xml" />
              <link rel="alternate" href="https://example.com/blog/" type="text/html" />
              <entry>
                <title>Atom Link Entry</title>
                <id>tag:example.com,2026:alternate</id>
                <link rel="self" href="https://example.com/feed.xml?entry=alternate" type="application/atom+xml" />
                <link rel="enclosure" href="https://example.com/audio/episode.mp3" type="audio/mpeg" />
                <link rel="alternate" href="/blog/atom-link-entry" type="text/html" />
                <updated>2026-04-08T10:00:00Z</updated>
                <summary>Atom summary</summary>
              </entry>
            </feed>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.siteUrl()).isEqualTo("https://example.com/blog/");
    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).link())
        .isEqualTo("https://example.com/blog/atom-link-entry");
  }

  @Test
  void shouldUseAtomImageEnclosureLinkAsCoverFallback() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Image Feed</title>
              <link rel="alternate" href="https://example.com/blog/" type="text/html" />
              <entry>
                <title>Atom Image Entry</title>
                <id>tag:example.com,2026:image-enclosure</id>
                <link rel="alternate" href="/blog/image-entry" type="text/html" />
                <link rel="enclosure" href="/images/atom-cover.jpg" type="image/jpeg" />
                <link rel="enclosure" href="/audio/episode.mp3" type="audio/mpeg" />
                <updated>2026-04-08T10:00:00Z</updated>
                <summary>No inline image here.</summary>
              </entry>
            </feed>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/images/atom-cover.jpg");
  }

  @Test
  void shouldBuildStableFallbackExternalIdForEntriesWithoutGuidOrLink() {
    String feedXml =
        """
        <?xml version="1.0" encoding="UTF-8" ?>
        <rss version="2.0">
          <channel>
            <title>Fallback Feed</title>
            <link>https://example.com</link>
            <description>Sample</description>
            <item>
              <title>Title Only Entry</title>
              <pubDate>Tue, 08 Apr 2026 10:00:00 GMT</pubDate>
              <description><![CDATA[Stable summary text]]></description>
            </item>
          </channel>
        </rss>
        """;

    RssParsedFeed firstParse = rssFeedParser.parse(feedXml, "https://example.com/feed.xml");
    RssParsedFeed secondParse = rssFeedParser.parse(feedXml, "https://example.com/feed.xml");

    assertThat(firstParse.entries()).hasSize(1);
    assertThat(firstParse.entries().get(0).externalId()).startsWith("fallback:");
    assertThat(firstParse.entries().get(0).externalId())
        .isEqualTo(secondParse.entries().get(0).externalId());
  }

  @Test
  void shouldPreferFullContentAndResolveRelativeUrls() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Relative Feed</title>
                <link>/blog/</link>
                <description>Sample</description>
                <item>
                  <title>Relative Entry</title>
                  <link>/posts/relative-entry</link>
                  <guid>guid-relative</guid>
                  <description><![CDATA[<p>Short teaser</p>]]></description>
                  <content:encoded><![CDATA[
                    <p>Full content body</p>
                    <img src="../images/cover.jpg" />
                  ]]></content:encoded>
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/blog/feed.xml");

    assertThat(parsedFeed.siteUrl()).isEqualTo("https://example.com/blog/");
    assertThat(parsedFeed.iconUrl()).isEqualTo("https://example.com/favicon.ico");
    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).link())
        .isEqualTo("https://example.com/posts/relative-entry");
    assertThat(parsedFeed.entries().get(0).summaryText()).contains("Full content body");
    assertThat(parsedFeed.entries().get(0).summaryText()).doesNotContain("Short teaser");
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/images/cover.jpg");
  }

  @Test
  void shouldUseLazySummaryImageAsCover() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Lazy Image Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Lazy Image Entry</title>
                  <link>https://example.com/posts/lazy-image</link>
                  <guid>guid-lazy-image</guid>
                  <description><![CDATA[
                    <p>Story body</p>
                    <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-src="/images/lazy-cover.webp" />
                  ]]></description>
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/images/lazy-cover.webp");
  }

  @Test
  void shouldUseLargestSummarySrcsetImageAsCover() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Srcset Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Srcset Entry</title>
                  <link>https://example.com/posts/srcset</link>
                  <guid>guid-srcset</guid>
                  <description><![CDATA[
                    <p>Story body</p>
                    <img srcset="/images/small.jpg 320w, /images/large.jpg 960w" />
                  ]]></description>
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/images/large.jpg");
  }

  @Test
  void shouldParseDublinCoreCreatorAsAuthor() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Creator Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Creator Entry</title>
                  <link>https://example.com/posts/creator</link>
                  <guid>guid-creator</guid>
                  <dc:creator><![CDATA[Jane Analyst]]></dc:creator>
                  <description><![CDATA[<p>Story body</p>]]></description>
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).author()).isEqualTo("Jane Analyst");
  }

  @Test
  void shouldUseImageEnclosureAsCoverFallback() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Enclosure Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Enclosure Entry</title>
                  <link>https://example.com/posts/entry</link>
                  <guid>guid-enclosure</guid>
                  <description><![CDATA[<p>No image here</p>]]></description>
                  <enclosure url="/media/cover.jpg" type="image/jpeg" length="2048" />
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/media/cover.jpg");
  }

  @Test
  void shouldUseMediaThumbnailAsCoverFallback() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Media Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Media Entry</title>
                  <link>https://example.com/posts/media</link>
                  <guid>guid-media</guid>
                  <description><![CDATA[<p>No image here</p>]]></description>
                  <media:thumbnail url="/media/thumb.jpg" />
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/media/thumb.jpg");
  }

  @Test
  void shouldUseItunesImageAsCoverFallback() {
    RssParsedFeed parsedFeed =
        rssFeedParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Podcast Feed</title>
                <link>https://example.com</link>
                <description>Sample</description>
                <item>
                  <title>Podcast Episode</title>
                  <link>https://example.com/episodes/1</link>
                  <guid>guid-podcast</guid>
                  <description><![CDATA[<p>No image here</p>]]></description>
                  <itunes:image href="/podcast/episode-cover.jpg" />
                </item>
              </channel>
            </rss>
            """,
            "https://example.com/feed.xml");

    assertThat(parsedFeed.entries()).hasSize(1);
    assertThat(parsedFeed.entries().get(0).coverImageUrl())
        .isEqualTo("https://example.com/podcast/episode-cover.jpg");
  }
}
