package com.rsscopilot.server.feed;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.rsscopilot.server.common.BadRequestException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class RssFeedParser {

  public RssParsedFeed parse(String feedXml, String fallbackFeedUrl) {
    try {
      SyndFeed syndFeed =
          new SyndFeedInput()
              .build(
                  new XmlReader(
                      new ByteArrayInputStream(feedXml.getBytes(StandardCharsets.UTF_8))));
      List<RssParsedFeed.RssParsedFeedEntry> parsedEntries = new ArrayList<>();
      for (SyndEntry entry : syndFeed.getEntries()) {
        String summaryHtml = extractSummaryHtml(entry);
        String summaryText = summaryHtml == null ? null : Jsoup.parse(summaryHtml).text();
        parsedEntries.add(
            new RssParsedFeed.RssParsedFeedEntry(
                buildExternalId(entry),
                entry.getTitle(),
                entry.getAuthor(),
                entry.getLink(),
                entry.getPublishedDate() != null
                    ? entry.getPublishedDate().toInstant()
                    : entry.getUpdatedDate() != null
                        ? entry.getUpdatedDate().toInstant()
                        : Instant.now(),
                summaryHtml,
                summaryText,
                extractCoverImageUrl(summaryHtml)));
      }
      String siteUrl = syndFeed.getLink();
      String iconUrl =
          syndFeed.getImage() != null ? syndFeed.getImage().getUrl() : inferFavicon(siteUrl);
      return new RssParsedFeed(syndFeed.getTitle(), siteUrl, iconUrl, parsedEntries);
    } catch (Exception exception) {
      throw new BadRequestException("invalid rss feed: " + fallbackFeedUrl);
    }
  }

  private String extractSummaryHtml(SyndEntry entry) {
    if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
      return entry.getDescription().getValue();
    }
    if (entry.getContents() != null && !entry.getContents().isEmpty()) {
      SyndContent content = entry.getContents().get(0);
      return content == null ? null : content.getValue();
    }
    return null;
  }

  private String buildExternalId(SyndEntry entry) {
    if (entry.getUri() != null && !entry.getUri().isBlank()) {
      return entry.getUri();
    }
    if (entry.getLink() != null && !entry.getLink().isBlank()) {
      return entry.getLink();
    }
    return entry.getTitle() + "::" + Instant.now();
  }

  private String extractCoverImageUrl(String summaryHtml) {
    if (summaryHtml == null || summaryHtml.isBlank()) {
      return null;
    }
    return Jsoup.parse(summaryHtml).select("img[src]").stream()
        .map(element -> element.attr("src"))
        .filter(src -> !src.isBlank())
        .findFirst()
        .orElse(null);
  }

  private String inferFavicon(String siteUrl) {
    if (siteUrl == null || siteUrl.isBlank()) {
      return null;
    }
    URI uri = URI.create(siteUrl);
    return uri.getScheme() + "://" + uri.getHost() + "/favicon.ico";
  }
}
