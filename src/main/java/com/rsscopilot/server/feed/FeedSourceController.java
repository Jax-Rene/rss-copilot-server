package com.rsscopilot.server.feed;

import com.rsscopilot.server.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

  @GetMapping(value = "/api/feed-sources/opml", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> exportOpml(CurrentUser currentUser) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_XML)
        .body(feedSourceService.exportOpml(currentUser.id()));
  }

  @PostMapping("/api/feed-sources/opml/import")
  public OpmlImportResponse importOpml(
      CurrentUser currentUser, @Valid @RequestBody OpmlImportRequest request) {
    return feedSourceService.importOpml(currentUser.id(), request);
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
  public RefreshAcceptedResponse refreshSources(
      CurrentUser currentUser, @RequestBody(required = false) RefreshSourcesRequest request) {
    RefreshSelectionResult result;
    if (request == null || request.sourceIds() == null) {
      result = feedSourceService.refreshAllAsync(currentUser.id());
    } else {
      result = feedSourceService.refreshSourcesAsync(currentUser.id(), request.sourceIds());
    }
    return RefreshAcceptedResponse.from(result);
  }

  @PostMapping("/api/feed-sources/{sourceId}/refresh")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public RefreshAcceptedResponse refreshSource(
      CurrentUser currentUser, @PathVariable long sourceId) {
    RefreshSelectionResult result =
        feedSourceService.refreshSourceAsync(currentUser.id(), sourceId);
    return RefreshAcceptedResponse.from(result);
  }

  @GetMapping("/api/feed-sources/{sourceId}/entries")
  public EntryListResponse listSourceEntries(
      CurrentUser currentUser,
      @PathVariable long sourceId,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String beforePublishedAt,
      @RequestParam(required = false) Long beforeId,
      @RequestParam(required = false, name = "q") String searchQuery) {
    feedSourceService.requireExistingSource(currentUser.id(), sourceId);
    return entryService.listEntries(
        currentUser.id(),
        "all",
        sourceId,
        null,
        false,
        limit,
        beforePublishedAt,
        beforeId,
        searchQuery);
  }
}
