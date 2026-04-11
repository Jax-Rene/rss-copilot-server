package com.rsscopilot.server.sync;

import com.rsscopilot.server.feed.EntryDetailResponse;
import com.rsscopilot.server.feed.FeedSourceResponse;
import com.rsscopilot.server.setting.SettingsResponse;
import java.time.Instant;
import java.util.List;

public record SyncBootstrapResponse(
    Instant serverTime,
    List<FeedSourceResponse> sources,
    List<EntryDetailResponse> entries,
    SettingsResponse settings) {}
