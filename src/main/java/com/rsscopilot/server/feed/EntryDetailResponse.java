package com.rsscopilot.server.feed;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record EntryDetailResponse(
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
    String coverImageUrl,
    String contentHtml,
    String filterReason,
    List<TranslationSegment> translationSegments) {}
