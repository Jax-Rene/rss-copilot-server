package com.rsscopilot.server.feed;

import java.util.List;

public record RefreshSourcesRequest(List<Long> sourceIds) {}
