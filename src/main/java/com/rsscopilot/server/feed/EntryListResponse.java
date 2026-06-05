package com.rsscopilot.server.feed;

import java.util.List;

public record EntryListResponse(
    List<EntryListItemResponse> items, boolean hasMore, EntryListCursor nextCursor) {}
