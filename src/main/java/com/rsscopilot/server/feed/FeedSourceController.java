package com.rsscopilot.server.feed;

import com.rsscopilot.server.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedSourceController {

  private final FeedSourceService feedSourceService;
  private final EntryService entryService;

  public FeedSourceController(FeedSourceService feedSourceService, EntryService entryService) {
    this.feedSourceService = feedSourceService;
    this.entryService = entryService;
  }

  @GetMapping("/api/feed-sources")
  public List<FeedSourceResponse> listSources(CurrentUser currentUser) {
    return feedSourceService.listSources(currentUser.id());
  }

  @PostMapping("/api/feed-sources")
  @ResponseStatus(HttpStatus.CREATED)
  public FeedSourceResponse createSource(
      CurrentUser currentUser, @Valid @RequestBody FeedSourceCreateRequest request) {
    return feedSourceService.createSource(currentUser.id(), request);
  }

  @PutMapping("/api/feed-sources/{sourceId}")
  public FeedSourceResponse updateSource(
      CurrentUser currentUser,
      @PathVariable long sourceId,
      @Valid @RequestBody FeedSourceUpdateRequest request) {
    return feedSourceService.updateSource(currentUser.id(), sourceId, request);
  }

  @DeleteMapping("/api/feed-sources/{sourceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSource(CurrentUser currentUser, @PathVariable long sourceId) {
    feedSourceService.deleteSource(currentUser.id(), sourceId);
  }

  @PostMapping("/api/feed-sources/refresh")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public RefreshAcceptedResponse refreshAll(CurrentUser currentUser) {
    feedSourceService.refreshAllAsync(currentUser.id());
    return new RefreshAcceptedResponse(true);
  }

  @GetMapping("/api/feed-sources/{sourceId}/entries")
  public EntryListResponse listSourceEntries(CurrentUser currentUser, @PathVariable long sourceId) {
    return entryService.listEntries(currentUser.id(), "all", sourceId, false);
  }
}
