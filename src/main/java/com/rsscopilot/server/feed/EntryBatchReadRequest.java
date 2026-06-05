package com.rsscopilot.server.feed;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record EntryBatchReadRequest(@NotNull List<Long> entryIds) {}
