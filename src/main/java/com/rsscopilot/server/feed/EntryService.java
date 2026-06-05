package com.rsscopilot.server.feed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsscopilot.server.common.BadRequestException;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.NotFoundException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class EntryService {

  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_BATCH_ENTRY_COUNT = 100;
  private static final int MAX_SEARCH_TOKEN_COUNT = 8;
  private static final double READING_COMPLETE_PROGRESS = 0.98;

  private final FeedEntryMapper feedEntryMapper;
  private final FeedSourceMapper feedSourceMapper;
  private final UserEntryStateMapper userEntryStateMapper;
  private final AiProcessingService aiProcessingService;
  private final ObjectMapper objectMapper;

  public EntryService(
      FeedEntryMapper feedEntryMapper,
      FeedSourceMapper feedSourceMapper,
      UserEntryStateMapper userEntryStateMapper,
      AiProcessingService aiProcessingService,
      ObjectMapper objectMapper) {
    this.feedEntryMapper = feedEntryMapper;
    this.feedSourceMapper = feedSourceMapper;
    this.userEntryStateMapper = userEntryStateMapper;
    this.aiProcessingService = aiProcessingService;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public EntryListResponse listEntries(
      long userId,
      String view,
      Long sourceId,
      String folder,
      boolean unreadOnly,
      int limit,
      String beforePublishedAt,
      Long beforeId,
      String searchQuery) {
    String normalizedView = normalizeView(view);
    Long normalizedSourceId = normalizeSourceIdFilter(sourceId);
    requireSourceFilter(userId, normalizedSourceId);
    String normalizedFolder = normalizeFolderFilter(folder);
    validatePaginationCursor(beforePublishedAt, beforeId);
    List<String> searchPatterns = toSearchPatterns(searchQuery);
    int pageSize = normalizeLimit(limit);
    List<EntryListItemResponse> items =
        feedEntryMapper
            .listEntries(
                userId,
                normalizedView,
                normalizedSourceId,
                normalizedFolder,
                unreadOnly,
                beforePublishedAt,
                beforeId,
                searchPatterns,
                pageSize + 1)
            .stream()
            .map(EntryListItemResponse::from)
            .toList();
    boolean hasMore = items.size() > pageSize;
    List<EntryListItemResponse> pageItems = hasMore ? items.subList(0, pageSize) : items;
    return new EntryListResponse(pageItems, hasMore, nextCursor(pageItems, hasMore));
  }

  @Transactional(readOnly = true)
  public EntryListResponse listEntries(
      long userId, String view, Long sourceId, boolean unreadOnly) {
    return listEntries(
        userId, view, sourceId, null, unreadOnly, DEFAULT_PAGE_SIZE, null, null, null);
  }

  @Transactional(readOnly = true)
  public List<EntryDetailResponse> listEntryDetails(long userId, String since) {
    return listEntryDetails(userId, since, null);
  }

  @Transactional(readOnly = true)
  public List<EntryDetailResponse> listEntryDetails(long userId, String since, String until) {
    return feedEntryMapper.listDetailsSince(userId, since, until).stream()
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
        detail.getSourceIconUrl(),
        detail.getAuthor(),
        detail.getTitle(),
        detail.getLink(),
        detail.getPublishedAt(),
        detail.getSummary(),
        detail.isRead(),
        detail.isSaved(),
        detail.getReadingProgress(),
        detail.isNoise(),
        detail.isForeignLanguage(),
        detail.getFilterStatus(),
        detail.getSummaryStatus(),
        detail.getTranslationStatus(),
        detail.getCoverImageUrl(),
        detail.getContentHtml(),
        detail.getFilterReason(),
        parseTranslationSegments(detail.getTranslationSegmentsJson()));
  }

  @Transactional
  public void markRead(long userId, long entryId) {
    requireEntry(userId, entryId);
    String nowText = InstantMapper.toText(Instant.now());
    userEntryStateMapper.markRead(userId, entryId, nowText, nowText);
  }

  @Transactional
  public ReadAllResponse markEntriesRead(long userId, List<Long> entryIds) {
    List<Long> normalizedEntryIds = normalizeEntryIds(entryIds);
    if (normalizedEntryIds.isEmpty()) {
      return new ReadAllResponse(0);
    }

    String nowText = InstantMapper.toText(Instant.now());
    int updatedCount =
        userEntryStateMapper.markEntriesRead(userId, normalizedEntryIds, nowText, nowText);
    return new ReadAllResponse(updatedCount);
  }

  @Transactional
  public void markUnread(long userId, long entryId) {
    requireEntry(userId, entryId);
    userEntryStateMapper.markUnread(userId, entryId, InstantMapper.toText(Instant.now()));
  }

  @Transactional
  public void markSaved(long userId, long entryId) {
    requireEntry(userId, entryId);
    String nowText = InstantMapper.toText(Instant.now());
    userEntryStateMapper.markSaved(userId, entryId, nowText, nowText);
  }

  @Transactional
  public void markUnsaved(long userId, long entryId) {
    requireEntry(userId, entryId);
    userEntryStateMapper.markUnsaved(userId, entryId, InstantMapper.toText(Instant.now()));
  }

  @Transactional
  public void updateReadingProgress(long userId, long entryId, double progress) {
    requireEntry(userId, entryId);
    double normalizedProgress = normalizeReadingProgress(progress);
    if (isReadingComplete(normalizedProgress)) {
      markRead(userId, entryId);
      return;
    }
    FeedEntryDetailView detail = feedEntryMapper.findDetail(entryId, userId);
    if (detail != null && detail.isRead()) {
      return;
    }
    userEntryStateMapper.updateReadingProgress(
        userId, entryId, normalizedProgress, InstantMapper.toText(Instant.now()));
  }

  @Transactional
  public void markNoise(long userId, long entryId) {
    requireEntry(userId, entryId);
    feedEntryMapper.updateFilterProjection(
        entryId, userId, "MANUAL", true, "手动移入噪音箱", InstantMapper.toText(Instant.now()));
  }

  @Transactional
  public void markFeed(long userId, long entryId) {
    requireEntry(userId, entryId);
    feedEntryMapper.updateFilterProjection(
        entryId, userId, "MANUAL", false, null, InstantMapper.toText(Instant.now()));
  }

  @Transactional
  public void reprocessAi(long userId, long entryId) {
    requireEntry(userId, entryId);
    feedEntryMapper.markAiProcessingPending(entryId, userId, InstantMapper.toText(Instant.now()));
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            aiProcessingService.enqueue(userId, entryId);
          }
        });
  }

  @Transactional
  public ReadAllResponse markAllRead(long userId, String view, Long sourceId, String folder) {
    Long normalizedSourceId = normalizeSourceIdFilter(sourceId);
    requireSourceFilter(userId, normalizedSourceId);
    int updatedCount =
        userEntryStateMapper.markAllRead(
            userId,
            normalizeView(view),
            normalizedSourceId,
            normalizeFolderFilter(folder),
            InstantMapper.toText(Instant.now()),
            InstantMapper.toText(Instant.now()));
    return new ReadAllResponse(updatedCount);
  }

  private String normalizeView(String view) {
    if (view == null || view.isBlank()) {
      return "feed";
    }
    String normalizedView = view.trim().toLowerCase();
    return switch (normalizedView) {
      case "feed", "noise", "all", "saved" -> normalizedView;
      default -> throw new BadRequestException("invalid view");
    };
  }

  private String normalizeFolderFilter(String folder) {
    if (!StringUtils.hasText(folder)) {
      return null;
    }
    return folder.trim();
  }

  private Long normalizeSourceIdFilter(Long sourceId) {
    if (sourceId == null) {
      return null;
    }
    if (sourceId <= 0) {
      throw new BadRequestException("invalid source id");
    }
    return sourceId;
  }

  private void requireSourceFilter(long userId, Long sourceId) {
    if (sourceId == null) {
      return;
    }
    if (feedSourceMapper.findByIdAndUserId(sourceId, userId) == null) {
      throw new NotFoundException("feed source not found");
    }
  }

  private void validatePaginationCursor(String beforePublishedAt, Long beforeId) {
    boolean hasPublishedAt = StringUtils.hasText(beforePublishedAt);
    boolean hasBeforeId = beforeId != null;
    if (hasPublishedAt != hasBeforeId) {
      throw new BadRequestException("invalid pagination cursor");
    }
    if (!hasPublishedAt) {
      return;
    }
    if (beforeId <= 0) {
      throw new BadRequestException("invalid pagination cursor");
    }
    try {
      Instant.parse(beforePublishedAt);
    } catch (DateTimeParseException exception) {
      throw new BadRequestException("invalid pagination cursor");
    }
  }

  private FeedEntry requireEntry(long userId, long entryId) {
    FeedEntry entry = feedEntryMapper.findByIdAndUserId(entryId, userId);
    if (entry == null) {
      throw new NotFoundException("entry not found");
    }
    return entry;
  }

  private int normalizeLimit(int limit) {
    if (limit <= 0) {
      throw new BadRequestException("invalid limit");
    }
    return Math.min(limit, MAX_PAGE_SIZE);
  }

  private List<Long> normalizeEntryIds(List<Long> entryIds) {
    if (entryIds == null || entryIds.isEmpty()) {
      return Collections.emptyList();
    }
    if (entryIds.size() > MAX_BATCH_ENTRY_COUNT) {
      throw new BadRequestException("too many entry ids");
    }
    List<Long> normalizedEntryIds =
        entryIds.stream().filter(id -> id != null && id > 0).distinct().toList();
    return normalizedEntryIds;
  }

  private double normalizeReadingProgress(double progress) {
    if (Double.isNaN(progress) || Double.isInfinite(progress)) {
      return 0;
    }
    if (progress < 0) {
      return 0;
    }
    if (progress > 1) {
      return 1;
    }
    return progress;
  }

  private boolean isReadingComplete(double progress) {
    return progress >= READING_COMPLETE_PROGRESS;
  }

  private List<String> toSearchPatterns(String searchQuery) {
    if (searchQuery == null || searchQuery.isBlank()) {
      return List.of();
    }
    String normalized = searchQuery.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 120) {
      normalized = normalized.substring(0, 120);
    }
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : normalized.split("\\s+")) {
      if (!token.isBlank()) {
        tokens.add("%" + escapeLikePattern(token) + "%");
        if (tokens.size() >= MAX_SEARCH_TOKEN_COUNT) {
          break;
        }
      }
    }
    return new ArrayList<>(tokens);
  }

  private String escapeLikePattern(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private EntryListCursor nextCursor(List<EntryListItemResponse> items, boolean hasMore) {
    if (!hasMore || items.isEmpty()) {
      return null;
    }
    EntryListItemResponse lastItem = items.get(items.size() - 1);
    return new EntryListCursor(lastItem.publishedAt(), lastItem.id());
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
