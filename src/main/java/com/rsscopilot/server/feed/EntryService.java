package com.rsscopilot.server.feed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.NotFoundException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntryService {

  private final FeedEntryMapper feedEntryMapper;
  private final UserEntryStateMapper userEntryStateMapper;
  private final ObjectMapper objectMapper;

  public EntryService(
      FeedEntryMapper feedEntryMapper,
      UserEntryStateMapper userEntryStateMapper,
      ObjectMapper objectMapper) {
    this.feedEntryMapper = feedEntryMapper;
    this.userEntryStateMapper = userEntryStateMapper;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public EntryListResponse listEntries(
      long userId, String view, Long sourceId, boolean unreadOnly) {
    String normalizedView = normalizeView(view);
    List<EntryListItemResponse> items =
        feedEntryMapper.listEntries(userId, normalizedView, sourceId, unreadOnly, 100).stream()
            .map(EntryListItemResponse::from)
            .toList();
    return new EntryListResponse(items);
  }

  @Transactional(readOnly = true)
  public List<EntryDetailResponse> listEntryDetails(long userId, String since) {
    return feedEntryMapper.listDetailsSince(userId, since).stream()
        .map(this::toDetailResponse)
        .toList();
  }

  @Transactional
  public EntryDetailResponse getEntry(long userId, long entryId, boolean markRead) {
    if (markRead) {
      markRead(userId, entryId);
    }
    FeedEntryDetailView detail = feedEntryMapper.findDetail(entryId, userId);
    if (detail == null) {
      throw new NotFoundException("entry not found");
    }
    return toDetailResponse(detail);
  }

  private EntryDetailResponse toDetailResponse(FeedEntryDetailView detail) {
    return new EntryDetailResponse(
        detail.getId(),
        detail.getSourceId(),
        detail.getSourceName(),
        detail.getTitle(),
        detail.getLink(),
        detail.getPublishedAt(),
        detail.getSummary(),
        detail.isRead(),
        detail.isForeignLanguage(),
        detail.getContentHtml(),
        detail.getFilterReason(),
        parseTranslationSegments(detail.getTranslationSegmentsJson()));
  }

  @Transactional
  public void markRead(long userId, long entryId) {
    String nowText = InstantMapper.toText(Instant.now());
    userEntryStateMapper.markRead(userId, entryId, nowText, nowText);
  }

  @Transactional
  public void markUnread(long userId, long entryId) {
    userEntryStateMapper.markUnread(userId, entryId, InstantMapper.toText(Instant.now()));
  }

  @Transactional
  public ReadAllResponse markAllRead(long userId, String view) {
    int updatedCount =
        userEntryStateMapper.markAllRead(
            userId,
            normalizeView(view),
            InstantMapper.toText(Instant.now()),
            InstantMapper.toText(Instant.now()));
    return new ReadAllResponse(updatedCount);
  }

  private String normalizeView(String view) {
    if (view == null || view.isBlank()) {
      return "feed";
    }
    return switch (view.toLowerCase()) {
      case "feed", "noise", "all" -> view.toLowerCase();
      default -> "feed";
    };
  }

  private List<TranslationSegment> parseTranslationSegments(String translationSegmentsJson) {
    if (translationSegmentsJson == null || translationSegmentsJson.isBlank()) {
      return Collections.emptyList();
    }
    try {
      return objectMapper.readValue(
          translationSegmentsJson, new TypeReference<List<TranslationSegment>>() {});
    } catch (Exception exception) {
      return Collections.emptyList();
    }
  }
}
