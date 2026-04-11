package com.rsscopilot.server.feed;

import com.rsscopilot.server.common.BadRequestException;
import com.rsscopilot.server.common.ConflictException;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.NotFoundException;
import com.rsscopilot.server.config.AppProperties;
import com.rsscopilot.server.http.HttpResponseData;
import com.rsscopilot.server.http.SimpleHttpClient;
import com.rsscopilot.server.sync.SyncTombstoneMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FeedSourceService {

  private final FeedSourceMapper feedSourceMapper;
  private final FeedEntryMapper feedEntryMapper;
  private final UserEntryStateMapper userEntryStateMapper;
  private final RssFeedParser rssFeedParser;
  private final SimpleHttpClient simpleHttpClient;
  private final ArticleContentExtractor articleContentExtractor;
  private final AiProcessingService aiProcessingService;
  private final SyncTombstoneMapper syncTombstoneMapper;
  private final Duration refreshTimeout;
  private final TaskExecutor aiProcessingExecutor;

  public FeedSourceService(
      FeedSourceMapper feedSourceMapper,
      FeedEntryMapper feedEntryMapper,
      UserEntryStateMapper userEntryStateMapper,
      RssFeedParser rssFeedParser,
      SimpleHttpClient simpleHttpClient,
      ArticleContentExtractor articleContentExtractor,
      AiProcessingService aiProcessingService,
      SyncTombstoneMapper syncTombstoneMapper,
      AppProperties appProperties,
      TaskExecutor aiProcessingExecutor) {
    this.feedSourceMapper = feedSourceMapper;
    this.feedEntryMapper = feedEntryMapper;
    this.userEntryStateMapper = userEntryStateMapper;
    this.rssFeedParser = rssFeedParser;
    this.simpleHttpClient = simpleHttpClient;
    this.articleContentExtractor = articleContentExtractor;
    this.aiProcessingService = aiProcessingService;
    this.syncTombstoneMapper = syncTombstoneMapper;
    this.refreshTimeout = Duration.ofSeconds(appProperties.getRefresh().getReadTimeoutSeconds());
    this.aiProcessingExecutor = aiProcessingExecutor;
  }

  @Transactional(readOnly = true)
  public List<FeedSourceResponse> listSources(long userId) {
    return feedSourceMapper.listSummariesByUserId(userId).stream()
        .map(FeedSourceResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<FeedSourceResponse> listSourcesUpdatedSince(long userId, String since) {
    return feedSourceMapper.listSummariesUpdatedSince(userId, since).stream()
        .map(FeedSourceResponse::from)
        .toList();
  }

  @Transactional
  public FeedSourceResponse createSource(long userId, FeedSourceCreateRequest request) {
    String rssUrl = normalizeUrl(request.rssUrl());
    if (feedSourceMapper.findByUserIdAndRssUrl(userId, rssUrl) != null) {
      throw new ConflictException("rss source already exists");
    }
    HttpResponseData response = simpleHttpClient.get(rssUrl, Map.of(), refreshTimeout);
    if (response.statusCode() >= 400) {
      throw new BadRequestException("rss source is unreachable");
    }
    RssParsedFeed parsedFeed = rssFeedParser.parse(response.body(), rssUrl);
    Instant now = Instant.now();
    FeedSource feedSource = new FeedSource();
    feedSource.setUserId(userId);
    feedSource.setName(
        StringUtils.hasText(parsedFeed.title())
            ? parsedFeed.title()
            : URI.create(rssUrl).getHost());
    feedSource.setRssUrl(rssUrl);
    feedSource.setSiteUrl(parsedFeed.siteUrl());
    feedSource.setIconUrl(parsedFeed.iconUrl());
    feedSource.setEnabled(true);
    feedSource.setStatus("IDLE");
    feedSource.setEtag(response.header("ETag"));
    feedSource.setLastModified(response.header("Last-Modified"));
    feedSource.setLastFetchedAt(null);
    feedSource.setCreatedAt(InstantMapper.toText(now));
    feedSource.setUpdatedAt(InstantMapper.toText(now));
    feedSourceMapper.insert(feedSource);
    return FeedSourceResponse.from(feedSource);
  }

  @Transactional
  public FeedSourceResponse updateSource(
      long userId, long sourceId, FeedSourceUpdateRequest request) {
    FeedSource existing = requireSource(userId, sourceId);
    existing.setName(request.name());
    existing.setRssUrl(normalizeUrl(request.rssUrl()));
    existing.setIconUrl(request.iconUrl());
    existing.setEnabled(request.enabled());
    existing.setUpdatedAt(InstantMapper.toText(Instant.now()));
    feedSourceMapper.updateEditableFields(existing);
    return FeedSourceResponse.from(existing);
  }

  @Transactional
  public void deleteSource(long userId, long sourceId) {
    requireSource(userId, sourceId);
    syncTombstoneMapper.upsert(
        userId, "feed_source", sourceId, InstantMapper.toText(Instant.now()));
    if (feedSourceMapper.deleteByIdAndUserId(sourceId, userId) == 0) {
      throw new NotFoundException("feed source not found");
    }
  }

  public void refreshAllAsync(long userId) {
    aiProcessingExecutor.execute(() -> refreshAll(userId));
  }

  public void refreshAllEnabledSources() {
    for (FeedSource feedSource : feedSourceMapper.listAllEnabled()) {
      refreshSource(feedSource);
    }
  }

  @Transactional
  public void refreshAll(long userId) {
    for (FeedSource feedSource : feedSourceMapper.listEnabledByUserId(userId)) {
      refreshSource(feedSource);
    }
  }

  @Transactional
  public void refreshSource(FeedSource feedSource) {
    Map<String, String> headers = new HashMap<>();
    if (StringUtils.hasText(feedSource.getEtag())) {
      headers.put("If-None-Match", feedSource.getEtag());
    }
    if (StringUtils.hasText(feedSource.getLastModified())) {
      headers.put("If-Modified-Since", feedSource.getLastModified());
    }
    Instant now = Instant.now();
    try {
      HttpResponseData response =
          simpleHttpClient.get(feedSource.getRssUrl(), headers, refreshTimeout);
      if (response.statusCode() == 304) {
        feedSource.setStatus("IDLE");
        feedSource.setLastFetchedAt(InstantMapper.toText(now));
        feedSource.setLastErrorAt(null);
        feedSource.setLastErrorMessage(null);
        feedSource.setUpdatedAt(InstantMapper.toText(now));
        feedSourceMapper.updateAfterRefresh(feedSource);
        return;
      }
      if (response.statusCode() >= 400) {
        throw new BadRequestException("rss refresh failed");
      }
      RssParsedFeed parsedFeed = rssFeedParser.parse(response.body(), feedSource.getRssUrl());
      feedSource.setName(
          StringUtils.hasText(parsedFeed.title()) ? parsedFeed.title() : feedSource.getName());
      feedSource.setSiteUrl(parsedFeed.siteUrl());
      feedSource.setIconUrl(parsedFeed.iconUrl());
      feedSource.setStatus("IDLE");
      feedSource.setEtag(response.header("ETag"));
      feedSource.setLastModified(response.header("Last-Modified"));
      feedSource.setLastFetchedAt(InstantMapper.toText(now));
      feedSource.setLastErrorAt(null);
      feedSource.setLastErrorMessage(null);
      feedSource.setUpdatedAt(InstantMapper.toText(now));
      feedSourceMapper.updateAfterRefresh(feedSource);

      for (RssParsedFeed.RssParsedFeedEntry entry : parsedFeed.entries()) {
        if (feedEntryMapper.findByExternalId(
                feedSource.getUserId(), feedSource.getId(), entry.externalId())
            != null) {
          continue;
        }
        ArticleContent articleContent =
            articleContentExtractor.extract(entry.link(), entry.summaryHtml(), entry.summaryText());
        FeedEntry feedEntry = new FeedEntry();
        feedEntry.setUserId(feedSource.getUserId());
        feedEntry.setSourceId(feedSource.getId());
        feedEntry.setExternalId(entry.externalId());
        feedEntry.setTitle(entry.title());
        feedEntry.setAuthor(entry.author());
        feedEntry.setLink(entry.link());
        feedEntry.setPublishedAt(InstantMapper.toText(entry.publishedAt()));
        feedEntry.setLanguage(
            LanguageHeuristics.isForeign(articleContent.text()) ? "foreign" : "zh");
        feedEntry.setForeignLanguage(
            LanguageHeuristics.isForeign(entry.title() + " " + articleContent.text()));
        feedEntry.setCoverImageUrl(
            StringUtils.hasText(articleContent.coverImageUrl())
                ? articleContent.coverImageUrl()
                : entry.coverImageUrl());
        feedEntry.setRssSummary(entry.summaryText());
        feedEntry.setContentHtml(articleContent.html());
        feedEntry.setContentText(articleContent.text());
        feedEntry.setContentFetched(articleContent.fetched());
        feedEntry.setFilterStatus("PENDING");
        feedEntry.setFilterIsNoise(false);
        feedEntry.setSummaryStatus("PENDING");
        feedEntry.setTranslationStatus("PENDING");
        feedEntry.setCreatedAt(InstantMapper.toText(now));
        feedEntry.setUpdatedAt(InstantMapper.toText(now));
        feedEntryMapper.insert(feedEntry);
        userEntryStateMapper.insertUnread(
            feedSource.getUserId(), feedEntry.getId(), InstantMapper.toText(now));
        aiProcessingService.enqueue(feedSource.getUserId(), feedEntry.getId());
      }
    } catch (Exception exception) {
      feedSource.setStatus("ERROR");
      feedSource.setLastErrorAt(InstantMapper.toText(now));
      feedSource.setLastErrorMessage(exception.getMessage());
      feedSource.setUpdatedAt(InstantMapper.toText(now));
      feedSourceMapper.updateAfterRefresh(feedSource);
    }
  }

  private FeedSource requireSource(long userId, long sourceId) {
    FeedSource feedSource = feedSourceMapper.findByIdAndUserId(sourceId, userId);
    if (feedSource == null) {
      throw new NotFoundException("feed source not found");
    }
    return feedSource;
  }

  private String normalizeUrl(String url) {
    try {
      return URI.create(url.trim()).toString();
    } catch (Exception exception) {
      throw new BadRequestException("invalid url");
    }
  }
}
