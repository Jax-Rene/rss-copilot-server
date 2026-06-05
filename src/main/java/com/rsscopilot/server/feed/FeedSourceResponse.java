package com.rsscopilot.server.feed;

import com.fasterxml.jackson.annotation.JsonInclude;

public record FeedSourceResponse(
    long id,
    String name,
    String rssUrl,
    String siteUrl,
    @JsonInclude(JsonInclude.Include.ALWAYS) String iconUrl,
    String folder,
    boolean enabled,
    String lastFetchedAt,
    boolean hasError,
    String lastErrorAt,
    String lastErrorMessage,
    int unreadCount) {

  public static FeedSourceResponse from(FeedSourceSummary summary) {
    return new FeedSourceResponse(
        summary.getId(),
        summary.getName(),
        summary.getRssUrl(),
        summary.getSiteUrl(),
        summary.getIconUrl(),
        summary.getFolder(),
        summary.isEnabled(),
        summary.getLastFetchedAt(),
        summary.isHasError(),
        summary.getLastErrorAt(),
        summary.getLastErrorMessage(),
        summary.getUnreadCount());
  }

  public static FeedSourceResponse from(FeedSource feedSource) {
    return new FeedSourceResponse(
        feedSource.getId(),
        feedSource.getName(),
        feedSource.getRssUrl(),
        feedSource.getSiteUrl(),
        feedSource.getIconUrl(),
        feedSource.getFolder(),
        feedSource.isEnabled(),
        feedSource.getLastFetchedAt(),
        "ERROR".equals(feedSource.getStatus()),
        feedSource.getLastErrorAt(),
        feedSource.getLastErrorMessage(),
        0);
  }
}
