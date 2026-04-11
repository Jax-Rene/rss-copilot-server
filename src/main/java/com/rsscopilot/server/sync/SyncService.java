package com.rsscopilot.server.sync;

import com.rsscopilot.server.auth.CurrentUser;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.feed.EntryDetailResponse;
import com.rsscopilot.server.feed.EntryService;
import com.rsscopilot.server.feed.FeedSourceResponse;
import com.rsscopilot.server.feed.FeedSourceService;
import com.rsscopilot.server.setting.SettingsResponse;
import com.rsscopilot.server.setting.SettingsService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncService {

  private final FeedSourceService feedSourceService;
  private final EntryService entryService;
  private final SettingsService settingsService;
  private final SyncTombstoneMapper syncTombstoneMapper;

  public SyncService(
      FeedSourceService feedSourceService,
      EntryService entryService,
      SettingsService settingsService,
      SyncTombstoneMapper syncTombstoneMapper) {
    this.feedSourceService = feedSourceService;
    this.entryService = entryService;
    this.settingsService = settingsService;
    this.syncTombstoneMapper = syncTombstoneMapper;
  }

  @Transactional(readOnly = true)
  public SyncBootstrapResponse bootstrap(CurrentUser currentUser) {
    List<FeedSourceResponse> sources = feedSourceService.listSources(currentUser.id());
    List<EntryDetailResponse> entries = entryService.listEntryDetails(currentUser.id(), null);
    SettingsResponse settings = settingsService.getSettings(currentUser);
    return new SyncBootstrapResponse(Instant.now(), sources, entries, settings);
  }

  @Transactional(readOnly = true)
  public SyncChangesResponse changes(CurrentUser currentUser, Instant since) {
    String sinceText = InstantMapper.toText(since);
    List<FeedSourceResponse> sources =
        feedSourceService.listSourcesUpdatedSince(currentUser.id(), sinceText);
    List<EntryDetailResponse> entries = entryService.listEntryDetails(currentUser.id(), sinceText);
    List<Long> deletedSourceIds =
        syncTombstoneMapper.listDeletedIdsSince(currentUser.id(), "feed_source", sinceText);
    SettingsResponse settings = settingsService.getSettings(currentUser);
    return new SyncChangesResponse(Instant.now(), sources, entries, deletedSourceIds, settings);
  }
}
