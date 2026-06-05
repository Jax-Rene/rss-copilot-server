package com.rsscopilot.server.feed;

record RefreshSelectionResult(int requestedCount, int acceptedCount) {

  int skippedCount() {
    return Math.max(0, requestedCount - acceptedCount);
  }
}
