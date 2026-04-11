package com.rsscopilot.server.feed;

public record FeedSourceResponse(
    long id,
    String name,
    String rssUrl,
    String siteUrl,
    String iconUrl,
    boolean enabled,
    String lastFetchedAt,
    boolean hasError,
    int unreadCount) {

  public static FeedSourceResponse from(FeedSourceSummary summary) {
    return new FeedSourceResponse(
        summary.getId(),
        summary.getName(),
        summary.getRssUrl(),
        summary.getSiteUrl(),
        summary.getIconUrl(),
        summary.isEnabled(),
        summary.getLastFetchedAt(),
        summary.isHasError(),
        summary.getUnreadCount());
  }

  public static FeedSourceResponse from(FeedSource feedSource) {
    return new FeedSourceResponse(
        feedSource.getId(),
        feedSource.getName(),
        feedSource.getRssUrl(),
        feedSource.getSiteUrl(),
        feedSource.getIconUrl(),
        feedSource.isEnabled(),
        feedSource.getLastFetchedAt(),
        "ERROR".equals(feedSource.getStatus()),
        0);
  }
}
