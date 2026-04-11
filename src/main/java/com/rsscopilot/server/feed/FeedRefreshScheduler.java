package com.rsscopilot.server.feed;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FeedRefreshScheduler {

  private final FeedSourceService feedSourceService;

  public FeedRefreshScheduler(FeedSourceService feedSourceService) {
    this.feedSourceService = feedSourceService;
  }

  @Scheduled(cron = "${app.refresh.cron}")
  public void refreshEnabledSources() {
    feedSourceService.refreshAllEnabledSources();
  }
}
