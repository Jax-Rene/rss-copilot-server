package com.rsscopilot.server.feed;

public record ArticleContent(String html, String text, String coverImageUrl, boolean fetched) {}
