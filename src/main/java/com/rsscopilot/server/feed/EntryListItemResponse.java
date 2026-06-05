package com.rsscopilot.server.feed;

import com.fasterxml.jackson.annotation.JsonInclude;

public record EntryListItemResponse(
    long id,
    long sourceId,
    String sourceName,
    @JsonInclude(JsonInclude.Include.ALWAYS) String sourceIconUrl,
    String author,
    String title,
    String link,
    String publishedAt,
    String summary,
    boolean isRead,
    boolean isSaved,
    double readingProgress,
    boolean isNoise,
    boolean foreign,
    String filterStatus,
    String summaryStatus,
    String translationStatus,
    String coverImageUrl) {

  public static EntryListItemResponse from(FeedEntryListItem item) {
    return new EntryListItemResponse(
        item.getId(),
        item.getSourceId(),
        item.getSourceName(),
        item.getSourceIconUrl(),
        item.getAuthor(),
        item.getTitle(),
        item.getLink(),
        item.getPublishedAt(),
        item.getSummary(),
        item.isRead(),
        item.isSaved(),
        item.getReadingProgress(),
        item.isNoise(),
        item.isForeignLanguage(),
        item.getFilterStatus(),
        item.getSummaryStatus(),
        item.getTranslationStatus(),
        item.getCoverImageUrl());
  }
}
