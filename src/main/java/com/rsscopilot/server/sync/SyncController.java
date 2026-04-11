package com.rsscopilot.server.sync;

import com.rsscopilot.server.auth.CurrentUser;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SyncController {

  private final SyncService syncService;

  public SyncController(SyncService syncService) {
    this.syncService = syncService;
  }

  @GetMapping("/api/sync/bootstrap")
  public SyncBootstrapResponse bootstrap(CurrentUser currentUser) {
    return syncService.bootstrap(currentUser);
  }

  @GetMapping("/api/sync/changes")
  public SyncChangesResponse changes(
      CurrentUser currentUser,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
    return syncService.changes(currentUser, since);
  }
}
