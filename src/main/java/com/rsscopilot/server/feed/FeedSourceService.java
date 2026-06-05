package com.rsscopilot.server.feed;

import com.rsscopilot.server.common.AppException;
import com.rsscopilot.server.common.BadRequestException;
import com.rsscopilot.server.common.ConflictException;
import com.rsscopilot.server.common.DiagnosticSanitizer;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.common.NotFoundException;
import com.rsscopilot.server.config.AppProperties;
import com.rsscopilot.server.http.HttpResponseData;
import com.rsscopilot.server.http.SimpleHttpClient;
import com.rsscopilot.server.sync.SyncTombstoneMapper;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class FeedSourceService {

  static final String DEFAULT_FOLDER = "未分组";
  private static final int MAX_BATCH_REFRESH_SOURCES = 100;
  private static final String ACCEPT_HEADER = "Accept";
  private static final String FEED_ACCEPT_HEADER =
      "application/rss+xml, application/atom+xml, application/feed+json, application/json;q=0.9, "
          + "application/xml, text/xml, text/html;q=0.8, */*;q=0.5";

  private final FeedSourceMapper feedSourceMapper;
  private final FeedEntryMapper feedEntryMapper;
  private final UserEntryStateMapper userEntryStateMapper;
  private final RssFeedParser rssFeedParser;
  private final SimpleHttpClient simpleHttpClient;
  private final ArticleContentExtractor articleContentExtractor;
  private final AiProcessingService aiProcessingService;
  private final SyncTombstoneMapper syncTombstoneMapper;
  private final OpmlDocumentService opmlDocumentService;
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
      OpmlDocumentService opmlDocumentService,
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
    this.opmlDocumentService = opmlDocumentService;
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
  public List<FeedSourceResponse> listSourcesChangedBetween(
      long userId, String since, String until) {
    return feedSourceMapper.listSummariesChangedBetween(userId, since, until).stream()
        .map(FeedSourceResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public void requireExistingSource(long userId, long sourceId) {
    if (sourceId <= 0) {
      throw new BadRequestException("invalid source id");
    }
    requireSource(userId, sourceId);
  }

  @Transactional(readOnly = true)
  public String exportOpml(long userId) {
    return opmlDocumentService.render(listSources(userId));
  }

  @Transactional
  public OpmlImportResponse importOpml(long userId, OpmlImportRequest request) {
    List<OpmlSubscription> subscriptions = opmlDocumentService.parseSubscriptions(request.opml());
    if (subscriptions.isEmpty()) {
      throw new BadRequestException("opml contains no rss subscriptions");
    }

    Instant now = Instant.now();
    String nowText = InstantMapper.toText(now);
    List<FeedSource> importedFeedSources = new ArrayList<>();
    List<FeedSourceResponse> importedSources = new ArrayList<>();
    Set<String> seenUrls = new HashSet<>();
    int skippedCount = 0;

    for (OpmlSubscription subscription : subscriptions) {
      String rssUrl = normalizeImportFeedUrl(subscription.xmlUrl());
      if (!StringUtils.hasText(rssUrl)) {
        skippedCount += 1;
        continue;
      }
      if (!seenUrls.add(rssUrl) || feedSourceMapper.findByUserIdAndRssUrl(userId, rssUrl) != null) {
        skippedCount += 1;
        continue;
      }

      FeedSource feedSource = new FeedSource();
      feedSource.setUserId(userId);
      feedSource.setName(defaultSourceName(subscription.title(), rssUrl));
      feedSource.setRssUrl(rssUrl);
      feedSource.setSiteUrl(normalizeImportOptionalUrl(subscription.htmlUrl()));
      feedSource.setIconUrl(null);
      feedSource.setFolder(normalizeFolder(subscription.folder()));
      feedSource.setEnabled(true);
      feedSource.setStatus("IDLE");
      feedSource.setEtag(null);
      feedSource.setLastModified(null);
      feedSource.setLastFetchedAt(null);
      feedSource.setCreatedAt(nowText);
      feedSource.setUpdatedAt(nowText);
      feedSourceMapper.insert(feedSource);
      importedFeedSources.add(feedSource);
      importedSources.add(FeedSourceResponse.from(feedSource));
    }

    int refreshAcceptedCount = 0;
    if (request.refreshAfterImport() && !importedFeedSources.isEmpty()) {
      refreshSourcesAfterCommit(importedFeedSources);
      refreshAcceptedCount = importedFeedSources.size();
    }

    return new OpmlImportResponse(
        importedSources.size(), skippedCount, refreshAcceptedCount, importedSources);
  }

  @Transactional
  public FeedSourceResponse createSource(long userId, FeedSourceCreateRequest request) {
    String inputUrl = normalizeFeedUrl(request.rssUrl());
    if (feedSourceMapper.findByUserIdAndRssUrl(userId, inputUrl) != null) {
      throw new ConflictException("rss source already exists");
    }
    ResolvedFeed resolvedFeed = resolveFeed(inputUrl);
    if (!resolvedFeed.rssUrl().equals(inputUrl)
        && feedSourceMapper.findByUserIdAndRssUrl(userId, resolvedFeed.rssUrl()) != null) {
      throw new ConflictException("rss source already exists");
    }
    Instant now = Instant.now();
    FeedSource feedSource = new FeedSource();
    feedSource.setUserId(userId);
    feedSource.setName(
        StringUtils.hasText(resolvedFeed.parsedFeed().title())
            ? resolvedFeed.parsedFeed().title()
            : URI.create(resolvedFeed.rssUrl()).getHost());
    feedSource.setRssUrl(resolvedFeed.rssUrl());
    feedSource.setSiteUrl(resolvedFeed.parsedFeed().siteUrl());
    feedSource.setIconUrl(resolvedFeed.parsedFeed().iconUrl());
    feedSource.setFolder(normalizeFolder(request.folder()));
    feedSource.setEnabled(true);
    feedSource.setStatus("IDLE");
    feedSource.setEtag(resolvedFeed.response().header("ETag"));
    feedSource.setLastModified(resolvedFeed.response().header("Last-Modified"));
    feedSource.setLastFetchedAt(null);
    feedSource.setCreatedAt(InstantMapper.toText(now));
    feedSource.setUpdatedAt(InstantMapper.toText(now));
    feedSourceMapper.insert(feedSource);
    return FeedSourceResponse.from(feedSource);
  }

  private ResolvedFeed resolveFeed(String inputUrl) {
    HttpResponseData inputResponse = fetchSourceDocument(inputUrl);
    try {
      return new ResolvedFeed(
          inputResponse.finalUrl(),
          inputResponse,
          rssFeedParser.parse(inputResponse.body(), inputResponse.finalUrl()));
    } catch (BadRequestException exception) {
      for (String discoveredFeedUrl :
          discoverFeedUrls(inputResponse.finalUrl(), inputResponse.body())) {
        try {
          String normalizedDiscoveredFeedUrl = normalizeUrl(discoveredFeedUrl);
          HttpResponseData feedResponse = fetchSourceDocument(normalizedDiscoveredFeedUrl);
          return new ResolvedFeed(
              feedResponse.finalUrl(),
              feedResponse,
              rssFeedParser.parse(feedResponse.body(), feedResponse.finalUrl()));
        } catch (BadRequestException candidateException) {
          // Continue trying lower confidence discovery candidates.
        }
      }
      throw new BadRequestException("rss feed could not be discovered");
    }
  }

  private HttpResponseData fetchSourceDocument(String url) {
    HttpResponseData response = simpleHttpClient.get(url, feedRequestHeaders(), refreshTimeout);
    if (response.statusCode() >= 400) {
      throw new BadRequestException("rss source is unreachable: HTTP " + response.statusCode());
    }
    return response;
  }

  private List<String> discoverFeedUrls(String pageUrl, String html) {
    org.jsoup.nodes.Document document = Jsoup.parse(html, pageUrl);
    Set<String> primaryFeedUrls = new LinkedHashSet<>();
    Set<String> secondaryFeedUrls = new LinkedHashSet<>();
    document.select("link[rel~=(?i)alternate][href]").stream()
        .filter(this::isFeedLink)
        .forEach(element -> addDiscoveredFeedUrl(element, primaryFeedUrls, secondaryFeedUrls));
    document.select("a[href]").stream()
        .filter(this::isFeedAnchor)
        .forEach(element -> addDiscoveredFeedUrl(element, primaryFeedUrls, secondaryFeedUrls));
    Set<String> feedUrls = new LinkedHashSet<>();
    feedUrls.addAll(primaryFeedUrls);
    feedUrls.addAll(secondaryFeedUrls);
    feedUrls.addAll(commonFeedUrls(pageUrl));
    return List.copyOf(feedUrls);
  }

  private void addDiscoveredFeedUrl(
      Element element, Set<String> primaryFeedUrls, Set<String> secondaryFeedUrls) {
    String feedUrl = element.absUrl("href");
    if (!StringUtils.hasText(feedUrl)) {
      return;
    }
    if (isLowConfidenceFeedCandidate(element)) {
      secondaryFeedUrls.add(feedUrl);
      return;
    }
    primaryFeedUrls.add(feedUrl);
  }

  private boolean isFeedLink(Element element) {
    String type = element.attr("type").toLowerCase(Locale.ROOT);
    String href = element.attr("href").toLowerCase(Locale.ROOT);
    String title = element.attr("title").toLowerCase(Locale.ROOT);
    return type.contains("rss")
        || type.contains("atom")
        || type.contains("rdf")
        || type.contains("feed+json")
        || (type.contains("json") && (title.contains("feed") || href.contains("feed")))
        || ((type.contains("xml") || href.endsWith(".xml"))
            && (title.contains("rss")
                || title.contains("feed")
                || title.contains("atom")
                || href.contains("rss")
                || href.contains("feed")
                || href.contains("atom")));
  }

  private boolean isFeedAnchor(Element element) {
    String href = element.attr("href").toLowerCase(Locale.ROOT);
    String label =
        (element.attr("title") + " " + element.attr("aria-label") + " " + element.text())
            .toLowerCase(Locale.ROOT);
    return hasFeedLabel(label) || pathLooksLikeFeed(href);
  }

  private boolean hasFeedLabel(String label) {
    return label.contains("rss")
        || label.contains("atom")
        || (label.contains("feed") && !label.contains("feedback"))
        || label.contains("订阅");
  }

  private boolean isLowConfidenceFeedCandidate(Element element) {
    String text =
        (element.attr("title")
                + " "
                + element.attr("aria-label")
                + " "
                + element.text()
                + " "
                + lastPathSegment(element.attr("href")))
            .toLowerCase(Locale.ROOT);
    return text.contains("comment") || text.contains("评论");
  }

  private String lastPathSegment(String href) {
    if (!StringUtils.hasText(href)) {
      return "";
    }
    try {
      String path = URI.create(href).getPath();
      if (StringUtils.hasText(path)) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
      }
    } catch (IllegalArgumentException exception) {
      // Fall through to the raw href path handling.
    }
    int queryIndex = href.indexOf('?');
    String path = queryIndex >= 0 ? href.substring(0, queryIndex) : href;
    int slashIndex = path.lastIndexOf('/');
    return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
  }

  private boolean pathLooksLikeFeed(String href) {
    String path = href;
    try {
      URI uri = URI.create(href);
      if (StringUtils.hasText(uri.getPath())) {
        path = uri.getPath();
      }
    } catch (IllegalArgumentException exception) {
      path = href;
    }
    path = path.toLowerCase(Locale.ROOT);
    return path.endsWith("/feed")
        || path.endsWith("/feeds")
        || path.endsWith("/rss")
        || path.endsWith("/atom")
        || path.endsWith("/jsonfeed")
        || path.endsWith("/feed/")
        || path.endsWith("/feeds/")
        || path.endsWith("/rss/")
        || path.endsWith("/atom/")
        || path.endsWith("/jsonfeed/")
        || path.endsWith("/feed.xml")
        || path.endsWith("/feed.json")
        || path.endsWith("/rss.xml")
        || path.endsWith("/atom.xml")
        || path.endsWith("/jsonfeed.json")
        || path.endsWith("/index.xml")
        || path.contains("/feed/")
        || path.contains("/feeds/")
        || path.contains("/rss/")
        || path.contains("/atom/")
        || path.contains("/jsonfeed/");
  }

  private boolean isLikelyDirectFeedUrl(String href) {
    if (pathLooksLikeFeed(href)) {
      return true;
    }
    String segment = lastPathSegment(href).toLowerCase(Locale.ROOT);
    return segment.endsWith(".xml") || segment.endsWith(".rss") || segment.endsWith(".atom");
  }

  private List<String> commonFeedUrls(String pageUrl) {
    try {
      URI pageUri = URI.create(pageUrl);
      String origin =
          pageUri.getScheme()
              + "://"
              + pageUri.getHost()
              + (pageUri.getPort() >= 0 ? ":" + pageUri.getPort() : "");
      Set<String> urls = new LinkedHashSet<>();
      String pageDirectory = pageDirectoryPath(pageUri);
      if (StringUtils.hasText(pageDirectory)) {
        addCommonFeedUrls(urls, origin, pageDirectory);
      }
      addCommonFeedUrls(urls, origin, "");
      return List.copyOf(urls);
    } catch (IllegalArgumentException exception) {
      return List.of();
    }
  }

  private void addCommonFeedUrls(Set<String> urls, String origin, String pathPrefix) {
    String normalizedPrefix =
        StringUtils.hasText(pathPrefix) && pathPrefix.startsWith("/") ? pathPrefix : "";
    urls.add(origin + normalizedPrefix + "/feed");
    urls.add(origin + normalizedPrefix + "/feed.xml");
    urls.add(origin + normalizedPrefix + "/feed.json");
    urls.add(origin + normalizedPrefix + "/rss");
    urls.add(origin + normalizedPrefix + "/rss.xml");
    urls.add(origin + normalizedPrefix + "/atom.xml");
    urls.add(origin + normalizedPrefix + "/jsonfeed.json");
    urls.add(origin + normalizedPrefix + "/index.xml");
  }

  private String pageDirectoryPath(URI pageUri) {
    String path = pageUri.getPath();
    if (!StringUtils.hasText(path) || "/".equals(path)) {
      return null;
    }
    String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    if (normalizedPath.contains(".")) {
      int slashIndex = normalizedPath.lastIndexOf('/');
      return slashIndex > 0 ? normalizedPath.substring(0, slashIndex) : null;
    }
    return normalizedPath;
  }

  @Transactional
  public FeedSourceResponse updateSource(
      long userId, long sourceId, FeedSourceUpdateRequest request) {
    FeedSource existing = requireSource(userId, sourceId);
    String inputUrl = normalizeFeedUrl(request.rssUrl());
    FeedSource duplicate = feedSourceMapper.findByUserIdAndRssUrl(userId, inputUrl);
    if (duplicate != null && duplicate.getId() != sourceId) {
      throw new ConflictException("rss source already exists");
    }
    ResolvedFeed resolvedFeed = null;
    String nextRssUrl = inputUrl;
    if (!Objects.equals(existing.getRssUrl(), inputUrl)) {
      try {
        resolvedFeed = resolveFeed(inputUrl);
        nextRssUrl = resolvedFeed.rssUrl();
        if (!Objects.equals(nextRssUrl, inputUrl)) {
          duplicate = feedSourceMapper.findByUserIdAndRssUrl(userId, nextRssUrl);
          if (duplicate != null && duplicate.getId() != sourceId) {
            throw new ConflictException("rss source already exists");
          }
        }
      } catch (BadRequestException exception) {
        if (!isLikelyDirectFeedUrl(inputUrl)) {
          throw exception;
        }
        nextRssUrl = inputUrl;
      }
    }
    boolean rssUrlChanged = !Objects.equals(existing.getRssUrl(), nextRssUrl);

    existing.setName(normalizeSourceName(request.name()));
    existing.setRssUrl(nextRssUrl);
    existing.setFolder(
        request.folder() == null ? existing.getFolder() : normalizeFolder(request.folder()));
    existing.setEnabled(request.enabled());
    if (rssUrlChanged) {
      existing.setStatus("IDLE");
      existing.setSiteUrl(resolvedFeed == null ? null : resolvedFeed.parsedFeed().siteUrl());
      existing.setIconUrl(
          StringUtils.hasText(request.iconUrl())
              ? normalizeOptionalUrl(request.iconUrl())
              : resolvedFeed == null ? null : resolvedFeed.parsedFeed().iconUrl());
      existing.setEtag(resolvedFeed == null ? null : resolvedFeed.response().header("ETag"));
      existing.setLastModified(
          resolvedFeed == null ? null : resolvedFeed.response().header("Last-Modified"));
      existing.setLastFetchedAt(null);
      existing.setLastErrorAt(null);
      existing.setLastErrorMessage(null);
      deleteSourceEntries(userId, sourceId);
    } else {
      existing.setIconUrl(normalizeOptionalUrl(request.iconUrl()));
    }
    existing.setUpdatedAt(InstantMapper.toText(Instant.now()));
    feedSourceMapper.updateEditableFields(existing);
    FeedSourceSummary summary = feedSourceMapper.findSummaryByIdAndUserId(sourceId, userId);
    if (summary == null) {
      throw new NotFoundException("feed source not found");
    }
    return FeedSourceResponse.from(summary);
  }

  @Transactional
  public void deleteSource(long userId, long sourceId) {
    requireSource(userId, sourceId);
    syncTombstoneMapper.upsert(
        userId, "feed_source", sourceId, InstantMapper.toText(Instant.now()));
    deleteSourceEntries(userId, sourceId);
    if (feedSourceMapper.deleteByIdAndUserId(sourceId, userId) == 0) {
      throw new NotFoundException("feed source not found");
    }
  }

  private void deleteSourceEntries(long userId, long sourceId) {
    feedEntryMapper.deleteUserStatesByUserIdAndSourceId(userId, sourceId);
    feedEntryMapper.deleteFilterResultsByUserIdAndSourceId(userId, sourceId);
    feedEntryMapper.deleteSummaryResultsByUserIdAndSourceId(userId, sourceId);
    feedEntryMapper.deleteTranslationResultsByUserIdAndSourceId(userId, sourceId);
    feedEntryMapper.deleteByUserIdAndSourceId(userId, sourceId);
  }

  @Transactional(readOnly = true)
  public RefreshSelectionResult refreshAllAsync(long userId) {
    List<FeedSource> feedSources = feedSourceMapper.listEnabledByUserId(userId);
    aiProcessingExecutor.execute(() -> feedSources.forEach(this::refreshSource));
    return new RefreshSelectionResult(feedSources.size(), feedSources.size());
  }

  @Transactional(readOnly = true)
  public RefreshSelectionResult refreshSourcesAsync(long userId, List<Long> sourceIds) {
    List<Long> normalizedSourceIds = normalizeSourceIds(sourceIds);
    if (normalizedSourceIds.isEmpty()) {
      return new RefreshSelectionResult(0, 0);
    }

    List<FeedSource> feedSources =
        normalizedSourceIds.stream()
            .map(sourceId -> feedSourceMapper.findByIdAndUserId(sourceId, userId))
            .filter(Objects::nonNull)
            .filter(FeedSource::isEnabled)
            .toList();
    if (feedSources.isEmpty()) {
      return new RefreshSelectionResult(normalizedSourceIds.size(), 0);
    }

    aiProcessingExecutor.execute(() -> feedSources.forEach(this::refreshSource));
    return new RefreshSelectionResult(normalizedSourceIds.size(), feedSources.size());
  }

  private void refreshSourcesAfterCommit(List<FeedSource> feedSources) {
    List<FeedSource> refreshTargets = List.copyOf(feedSources);
    Runnable refreshTask = () -> refreshTargets.forEach(this::refreshSource);
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      aiProcessingExecutor.execute(refreshTask);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            aiProcessingExecutor.execute(refreshTask);
          }
        });
  }

  @Transactional(readOnly = true)
  public RefreshSelectionResult refreshSourceAsync(long userId, long sourceId) {
    FeedSource feedSource = requireSource(userId, sourceId);
    if (!feedSource.isEnabled()) {
      return new RefreshSelectionResult(1, 0);
    }
    aiProcessingExecutor.execute(() -> refreshSource(feedSource));
    return new RefreshSelectionResult(1, 1);
  }

  private List<Long> normalizeSourceIds(List<Long> sourceIds) {
    if (sourceIds == null || sourceIds.isEmpty()) {
      return List.of();
    }
    if (sourceIds.size() > MAX_BATCH_REFRESH_SOURCES) {
      throw new BadRequestException("too many feed sources to refresh");
    }

    Set<Long> normalizedSourceIds = new LinkedHashSet<>();
    for (Long sourceId : sourceIds) {
      if (sourceId == null || sourceId <= 0) {
        throw new BadRequestException("invalid feed source id");
      }
      normalizedSourceIds.add(sourceId);
    }
    return new ArrayList<>(normalizedSourceIds);
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
    Map<String, String> headers = feedRequestHeaders();
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
      if (response.statusCode() >= 400) {
        throw new BadRequestException("rss refresh failed: HTTP " + response.statusCode());
      }
      updateSourceUrlFromRefreshRedirect(feedSource, response.finalUrl());
      if (response.statusCode() == 304) {
        feedSource.setStatus("IDLE");
        feedSource.setLastFetchedAt(InstantMapper.toText(now));
        feedSource.setLastErrorAt(null);
        feedSource.setLastErrorMessage(null);
        feedSource.setUpdatedAt(InstantMapper.toText(now));
        feedSourceMapper.updateAfterRefresh(feedSource);
        return;
      }
      RssParsedFeed parsedFeed = rssFeedParser.parse(response.body(), response.finalUrl());
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
        FeedEntry existingEntry =
            feedEntryMapper.findByExternalId(
                feedSource.getUserId(), feedSource.getId(), entry.externalId());
        if (existingEntry != null) {
          updateExistingEntrySnapshot(feedSource, existingEntry, entry, now);
          continue;
        }
        ArticleContent articleContent = extractArticleContent(entry);
        FeedEntry feedEntry = buildEntrySnapshot(feedSource, entry, articleContent, now, null);
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
      feedSource.setLastErrorMessage(refreshFailureMessage(exception));
      feedSource.setUpdatedAt(InstantMapper.toText(now));
      feedSourceMapper.updateAfterRefresh(feedSource);
    }
  }

  private void updateSourceUrlFromRefreshRedirect(FeedSource feedSource, String finalUrl) {
    String normalizedFinalUrl = normalizeUrl(finalUrl);
    if (Objects.equals(feedSource.getRssUrl(), normalizedFinalUrl)) {
      return;
    }
    FeedSource duplicate =
        feedSourceMapper.findByUserIdAndRssUrl(feedSource.getUserId(), normalizedFinalUrl);
    if (duplicate == null || duplicate.getId() == feedSource.getId()) {
      feedSource.setRssUrl(normalizedFinalUrl);
    }
  }

  static String refreshFailureMessage(Exception exception) {
    Throwable rootCause = rootCause(exception);
    if (rootCause instanceof HttpTimeoutException) {
      return "rss refresh timed out";
    }
    if (rootCause instanceof UnknownHostException) {
      return appendDetail("rss host could not be resolved", rootCause.getMessage());
    }
    if (rootCause instanceof ConnectException) {
      return appendDetail("rss connection failed", rootCause.getMessage());
    }
    if (rootCause instanceof SSLException) {
      return appendDetail("rss TLS handshake failed", rootCause.getMessage());
    }
    if (exception instanceof AppException appException
        && StringUtils.hasText(appException.code())
        && appException.code().startsWith("UPSTREAM_")) {
      return appendDetail(
          "rss upstream request failed", firstText(rootCause.getMessage(), exception.getMessage()));
    }
    if (exception instanceof UncheckedIOException) {
      return appendDetail("rss response decode failed", rootCause.getMessage());
    }
    if (StringUtils.hasText(exception.getMessage())) {
      return truncateFailureMessage(exception.getMessage());
    }
    return "rss refresh failed: " + exception.getClass().getSimpleName();
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private static String appendDetail(String prefix, String detail) {
    if (!StringUtils.hasText(detail)) {
      return prefix;
    }
    return truncateFailureMessage(prefix + ": " + detail.trim());
  }

  private static String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first : second;
  }

  private static String truncateFailureMessage(String message) {
    String trimmed = DiagnosticSanitizer.redact(message).trim();
    if (trimmed.length() <= 240) {
      return trimmed;
    }
    return trimmed.substring(0, 237) + "...";
  }

  private void updateExistingEntrySnapshot(
      FeedSource feedSource,
      FeedEntry existingEntry,
      RssParsedFeed.RssParsedFeedEntry parsedEntry,
      Instant now) {
    String nextLink = entryLink(parsedEntry, feedSource);
    ArticleContent articleContent = existingContentSnapshot(existingEntry, parsedEntry, nextLink);
    FeedEntry refreshedEntry =
        buildEntrySnapshot(
            feedSource, parsedEntry, articleContent, now, existingEntry.getCoverImageUrl());
    refreshedEntry.setId(existingEntry.getId());
    refreshedEntry.setCreatedAt(existingEntry.getCreatedAt());
    if (hasContentSnapshotChanged(existingEntry, refreshedEntry)) {
      feedEntryMapper.updateContentSnapshot(refreshedEntry);
      feedEntryMapper.markContentAiProcessingPending(
          existingEntry.getId(), existingEntry.getUserId(), InstantMapper.toText(now));
      aiProcessingService.enqueueContentRefresh(existingEntry.getUserId(), existingEntry.getId());
    }
  }

  private ArticleContent existingContentSnapshot(
      FeedEntry existingEntry, RssParsedFeed.RssParsedFeedEntry parsedEntry, String nextLink) {
    if (!existingEntry.isContentFetched() || !Objects.equals(existingEntry.getLink(), nextLink)) {
      return extractArticleContent(parsedEntry);
    }
    return new ArticleContent(
        existingEntry.getContentHtml(),
        existingEntry.getContentText(),
        existingEntry.getCoverImageUrl(),
        existingEntry.isContentFetched());
  }

  private FeedEntry buildEntrySnapshot(
      FeedSource feedSource,
      RssParsedFeed.RssParsedFeedEntry parsedEntry,
      ArticleContent articleContent,
      Instant now,
      String existingCoverImageUrl) {
    String entryLink = entryLink(parsedEntry, feedSource);
    String entryTitle = entryTitle(parsedEntry, entryLink);
    String contentText = nullToBlank(articleContent.text());
    FeedEntry feedEntry = new FeedEntry();
    feedEntry.setUserId(feedSource.getUserId());
    feedEntry.setSourceId(feedSource.getId());
    feedEntry.setExternalId(parsedEntry.externalId());
    feedEntry.setTitle(entryTitle);
    feedEntry.setAuthor(parsedEntry.author());
    feedEntry.setLink(entryLink);
    feedEntry.setPublishedAt(InstantMapper.toText(parsedEntry.publishedAt()));
    feedEntry.setLanguage(LanguageHeuristics.isForeign(contentText) ? "foreign" : "zh");
    feedEntry.setForeignLanguage(LanguageHeuristics.isForeign(entryTitle + " " + contentText));
    feedEntry.setCoverImageUrl(coverImageUrl(articleContent, parsedEntry, existingCoverImageUrl));
    feedEntry.setRssSummary(parsedEntry.summaryText());
    feedEntry.setContentHtml(articleContent.html());
    feedEntry.setContentText(articleContent.text());
    feedEntry.setContentFetched(articleContent.fetched());
    feedEntry.setCreatedAt(InstantMapper.toText(now));
    feedEntry.setUpdatedAt(InstantMapper.toText(now));
    return feedEntry;
  }

  private String coverImageUrl(
      ArticleContent articleContent,
      RssParsedFeed.RssParsedFeedEntry parsedEntry,
      String existingCoverImageUrl) {
    if (StringUtils.hasText(articleContent.coverImageUrl())) {
      return articleContent.coverImageUrl();
    }
    if (StringUtils.hasText(existingCoverImageUrl)) {
      return existingCoverImageUrl;
    }
    return parsedEntry.coverImageUrl();
  }

  private boolean hasContentSnapshotChanged(FeedEntry existingEntry, FeedEntry refreshedEntry) {
    return !Objects.equals(existingEntry.getTitle(), refreshedEntry.getTitle())
        || !Objects.equals(existingEntry.getAuthor(), refreshedEntry.getAuthor())
        || !Objects.equals(existingEntry.getLink(), refreshedEntry.getLink())
        || !Objects.equals(existingEntry.getPublishedAt(), refreshedEntry.getPublishedAt())
        || !Objects.equals(existingEntry.getLanguage(), refreshedEntry.getLanguage())
        || existingEntry.isForeignLanguage() != refreshedEntry.isForeignLanguage()
        || !Objects.equals(existingEntry.getCoverImageUrl(), refreshedEntry.getCoverImageUrl())
        || !Objects.equals(existingEntry.getRssSummary(), refreshedEntry.getRssSummary())
        || !Objects.equals(existingEntry.getContentHtml(), refreshedEntry.getContentHtml())
        || !Objects.equals(existingEntry.getContentText(), refreshedEntry.getContentText())
        || existingEntry.isContentFetched() != refreshedEntry.isContentFetched();
  }

  private ArticleContent extractArticleContent(RssParsedFeed.RssParsedFeedEntry entry) {
    if (!StringUtils.hasText(entry.link())) {
      return articleContentExtractor.fallback(
          entry.summaryHtml(), entry.summaryText(), entry.coverImageUrl());
    }
    return articleContentExtractor.extract(entry.link(), entry.summaryHtml(), entry.summaryText());
  }

  private String entryLink(RssParsedFeed.RssParsedFeedEntry entry, FeedSource feedSource) {
    if (StringUtils.hasText(entry.link())) {
      return entry.link().trim();
    }
    if (StringUtils.hasText(feedSource.getSiteUrl())) {
      return feedSource.getSiteUrl();
    }
    return feedSource.getRssUrl();
  }

  private String entryTitle(RssParsedFeed.RssParsedFeedEntry entry, String entryLink) {
    if (StringUtils.hasText(entry.title())) {
      return entry.title().trim();
    }
    return entryLink;
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  private Map<String, String> feedRequestHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put(ACCEPT_HEADER, FEED_ACCEPT_HEADER);
    return headers;
  }

  private FeedSource requireSource(long userId, long sourceId) {
    FeedSource feedSource = feedSourceMapper.findByIdAndUserId(sourceId, userId);
    if (feedSource == null) {
      throw new NotFoundException("feed source not found");
    }
    return feedSource;
  }

  private String normalizeFeedUrl(String url) {
    return normalizeUrl(url, true);
  }

  private String normalizeUrl(String url) {
    return normalizeUrl(url, false);
  }

  private String normalizeUrl(String url, boolean allowSchemeLessFeedUrl) {
    try {
      if (!StringUtils.hasText(url)) {
        throw new BadRequestException("invalid url");
      }
      String urlText = url.trim();
      URI uri = URI.create(applyDefaultFeedUrlScheme(urlText, allowSchemeLessFeedUrl));
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null
          || host == null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw new BadRequestException("invalid url");
      }
      URI normalizedUri = uri.normalize();
      return canonicalUrl(
          scheme.toLowerCase(Locale.ROOT),
          host.toLowerCase(Locale.ROOT),
          uri.getRawUserInfo(),
          normalizePort(scheme, uri.getPort()),
          normalizedUri.getRawPath(),
          uri.getRawQuery());
    } catch (Exception exception) {
      if (exception instanceof BadRequestException badRequestException) {
        throw badRequestException;
      }
      throw new BadRequestException("invalid url");
    }
  }

  private String applyDefaultFeedUrlScheme(String urlText, boolean allowSchemeLessFeedUrl) {
    if (!allowSchemeLessFeedUrl || urlText.contains("://")) {
      return urlText;
    }
    if (urlText.startsWith("//")) {
      String host = extractSchemeLessHost(urlText.substring(2));
      if (!looksLikeSchemeLessHost(host)) {
        return urlText;
      }
      return defaultSchemeForHost(host) + ":" + urlText;
    }
    String host = extractSchemeLessHost(urlText);
    if (!looksLikeSchemeLessHost(host)) {
      return urlText;
    }
    return defaultSchemeForHost(host) + "://" + urlText;
  }

  private String extractSchemeLessHost(String urlText) {
    int endIndex = urlText.length();
    for (char delimiter : new char[] {'/', '?', '#'}) {
      int delimiterIndex = urlText.indexOf(delimiter);
      if (delimiterIndex >= 0) {
        endIndex = Math.min(endIndex, delimiterIndex);
      }
    }
    String authority = urlText.substring(0, endIndex);
    int userInfoIndex = authority.lastIndexOf('@');
    if (userInfoIndex >= 0) {
      authority = authority.substring(userInfoIndex + 1);
    }
    if (authority.startsWith("[")) {
      int bracketIndex = authority.indexOf(']');
      return bracketIndex > 0 ? authority.substring(1, bracketIndex) : authority;
    }
    int firstColonIndex = authority.indexOf(':');
    if (firstColonIndex >= 0 && firstColonIndex == authority.lastIndexOf(':')) {
      return authority.substring(0, firstColonIndex);
    }
    return authority;
  }

  private boolean looksLikeSchemeLessHost(String host) {
    if (!StringUtils.hasText(host) || host.contains(" ")) {
      return false;
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    return "localhost".equals(normalizedHost)
        || normalizedHost.contains(".")
        || normalizedHost.contains(":");
  }

  private String defaultSchemeForHost(String host) {
    return isLocalDevelopmentHost(host) ? "http" : "https";
  }

  private boolean isLocalDevelopmentHost(String host) {
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    if ("localhost".equals(normalizedHost)
        || "127.0.0.1".equals(normalizedHost)
        || "::1".equals(normalizedHost)) {
      return true;
    }
    if (normalizedHost.startsWith("10.") || normalizedHost.startsWith("192.168.")) {
      return true;
    }
    if (!normalizedHost.startsWith("172.")) {
      return false;
    }
    String[] parts = normalizedHost.split("\\.");
    if (parts.length < 2) {
      return false;
    }
    try {
      int secondOctet = Integer.parseInt(parts[1]);
      return secondOctet >= 16 && secondOctet <= 31;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private String canonicalUrl(
      String scheme, String host, String rawUserInfo, int port, String rawPath, String rawQuery) {
    StringBuilder builder = new StringBuilder();
    builder.append(scheme).append("://");
    if (StringUtils.hasText(rawUserInfo)) {
      builder.append(rawUserInfo).append('@');
    }
    if (host.contains(":") && !host.startsWith("[")) {
      builder.append('[').append(host).append(']');
    } else {
      builder.append(host);
    }
    if (port >= 0) {
      builder.append(':').append(port);
    }
    if (StringUtils.hasText(rawPath)) {
      builder.append(rawPath);
    }
    if (StringUtils.hasText(rawQuery)) {
      builder.append('?').append(rawQuery);
    }
    return builder.toString();
  }

  private int normalizePort(String scheme, int port) {
    if (port == 80 && "http".equalsIgnoreCase(scheme)) {
      return -1;
    }
    if (port == 443 && "https".equalsIgnoreCase(scheme)) {
      return -1;
    }
    return port;
  }

  private String normalizeOptionalUrl(String url) {
    if (!StringUtils.hasText(url)) {
      return null;
    }
    return normalizeUrl(url);
  }

  private String normalizeImportFeedUrl(String url) {
    try {
      return normalizeFeedUrl(url);
    } catch (BadRequestException exception) {
      return null;
    }
  }

  private String normalizeImportOptionalUrl(String url) {
    if (!StringUtils.hasText(url)) {
      return null;
    }
    try {
      return normalizeUrl(url);
    } catch (BadRequestException exception) {
      return null;
    }
  }

  private String normalizeFolder(String folder) {
    if (!StringUtils.hasText(folder)) {
      return DEFAULT_FOLDER;
    }
    return folder.trim();
  }

  private String normalizeSourceName(String name) {
    if (!StringUtils.hasText(name)) {
      throw new BadRequestException("name must not be blank");
    }
    return name.trim();
  }

  private String defaultSourceName(String title, String rssUrl) {
    if (StringUtils.hasText(title)) {
      return title.trim();
    }
    String host = URI.create(rssUrl).getHost();
    return StringUtils.hasText(host) ? host : rssUrl;
  }

  private record ResolvedFeed(String rssUrl, HttpResponseData response, RssParsedFeed parsedFeed) {}
}
