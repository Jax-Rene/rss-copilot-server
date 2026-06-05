package com.rsscopilot.server.feed;

import jakarta.validation.constraints.NotBlank;

public record OpmlImportRequest(@NotBlank String opml, boolean refreshAfterImport) {}
