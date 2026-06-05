package com.rsscopilot.server.feed;

import com.rsscopilot.server.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EntryController {

  private final EntryService entryService;

  public EntryController(EntryService entryService) {
    this.entryService = entryService;
  }

  @GetMapping("/api/entries")
  public EntryListResponse listEntries(
      CurrentUser currentUser,
      @RequestParam(defaultValue = "feed") String view,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String beforePublishedAt,
      @RequestParam(required = false) Long beforeId,
      @RequestParam(required = false) Long sourceId,
      @RequestParam(required = false) String folder,
      @RequestParam(required = false, name = "q") String searchQuery) {
    return entryService.listEntries(
        currentUser.id(),
        view,
        sourceId,
        folder,
        unreadOnly,
        limit,
        beforePublishedAt,
        beforeId,
        searchQuery);
  }

  @GetMapping("/api/entries/{entryId}")
  public EntryDetailResponse getEntry(
      CurrentUser currentUser,
      @PathVariable long entryId,
      @RequestParam(defaultValue = "false") boolean markRead) {
    return entryService.getEntry(currentUser.id(), entryId, markRead);
  }

  @PostMapping("/api/entries/{entryId}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markRead(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/read")
  public ReadAllResponse markEntriesRead(
      CurrentUser currentUser, @Valid @RequestBody EntryBatchReadRequest request) {
    return entryService.markEntriesRead(currentUser.id(), request.entryIds());
  }

  @PostMapping("/api/entries/{entryId}/unread")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markUnread(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markUnread(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/{entryId}/saved")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markSaved(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markSaved(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/{entryId}/unsaved")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markUnsaved(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markUnsaved(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/{entryId}/progress")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateReadingProgress(
      CurrentUser currentUser,
      @PathVariable long entryId,
      @Valid @RequestBody ReadingProgressRequest request) {
    entryService.updateReadingProgress(currentUser.id(), entryId, request.progress());
  }

  @PostMapping("/api/entries/{entryId}/noise")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markNoise(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markNoise(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/{entryId}/feed")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markFeed(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markFeed(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/{entryId}/ai/reprocess")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void reprocessAi(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.reprocessAi(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/read-all")
  public ReadAllResponse markAllRead(
      CurrentUser currentUser,
      @RequestParam(defaultValue = "feed") String view,
      @RequestParam(required = false) Long sourceId,
      @RequestParam(required = false) String folder) {
    return entryService.markAllRead(currentUser.id(), view, sourceId, folder);
  }
}
