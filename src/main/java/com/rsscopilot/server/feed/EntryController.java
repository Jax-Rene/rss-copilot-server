package com.rsscopilot.server.feed;

import com.rsscopilot.server.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
      @RequestParam(defaultValue = "false") boolean unreadOnly) {
    return entryService.listEntries(currentUser.id(), view, null, unreadOnly);
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

  @PostMapping("/api/entries/{entryId}/unread")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markUnread(CurrentUser currentUser, @PathVariable long entryId) {
    entryService.markUnread(currentUser.id(), entryId);
  }

  @PostMapping("/api/entries/read-all")
  public ReadAllResponse markAllRead(
      CurrentUser currentUser, @RequestParam(defaultValue = "feed") String view) {
    return entryService.markAllRead(currentUser.id(), view);
  }
}
