package com.rsscopilot.server.feed;

import java.util.List;

public record EntryDetailResponse(
    long id,
    long sourceId,
    String sourceName,
    String title,
    String link,
    String publishedAt,
    String summary,
    boolean isRead,
    boolean foreign,
    String contentHtml,
    String filterReason,
    List<TranslationSegment> translationSegments) {}
