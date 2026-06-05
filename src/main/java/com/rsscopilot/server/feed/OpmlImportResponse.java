package com.rsscopilot.server.feed;

import java.util.List;

public record OpmlImportResponse(
    int importedCount,
    int skippedCount,
    int refreshAcceptedCount,
    List<FeedSourceResponse> sources) {}
