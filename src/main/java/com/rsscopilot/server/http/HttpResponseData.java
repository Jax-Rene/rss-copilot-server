package com.rsscopilot.server.http;

import java.util.List;
import java.util.Map;

public record HttpResponseData(int statusCode, String body, Map<String, List<String>> headers) {

  public String header(String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .filter(values -> !values.isEmpty())
        .map(values -> values.get(0))
        .findFirst()
        .orElse(null);
  }
}
