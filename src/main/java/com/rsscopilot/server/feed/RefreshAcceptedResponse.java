package com.rsscopilot.server.feed;

public record RefreshAcceptedResponse(
    boolean accepted, int acceptedCount, int requestedCount, int skippedCount) {

  public static RefreshAcceptedResponse from(RefreshSelectionResult result) {
    return new RefreshAcceptedResponse(
        true, result.acceptedCount(), result.requestedCount(), result.skippedCount());
  }
}
