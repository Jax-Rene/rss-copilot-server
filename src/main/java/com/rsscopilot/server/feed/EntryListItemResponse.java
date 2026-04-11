package com.rsscopilot.server.feed;

public record EntryListItemResponse(
    long id,
    long sourceId,
    String sourceName,
    String title,
    String link,
    String publishedAt,
    String summary,
    boolean isRead,
    boolean foreign,
    String coverImageUrl) {

  public static EntryListItemResponse from(FeedEntryListItem item) {
    return new EntryListItemResponse(
        item.getId(),
        item.getSourceId(),
        item.getSourceName(),
        item.getTitle(),
        item.getLink(),
        item.getPublishedAt(),
        item.getSummary(),
        item.isRead(),
        item.isForeignLanguage(),
        item.getCoverImageUrl());
  }
}
