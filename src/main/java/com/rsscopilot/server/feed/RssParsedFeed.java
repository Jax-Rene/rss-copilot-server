package com.rsscopilot.server.feed;

import java.time.Instant;
import java.util.List;

public record RssParsedFeed(
    String title, String siteUrl, String iconUrl, List<RssParsedFeedEntry> entries) {

  public record RssParsedFeedEntry(
      String externalId,
      String title,
      String author,
      String link,
      Instant publishedAt,
      String summaryHtml,
      String summaryText,
      String coverImageUrl) {}
}
