package com.rsscopilot.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TestJson {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TestJson() {}

  public static JsonNode parse(String content) {
    try {
      return OBJECT_MAPPER.readTree(content);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to parse json", exception);
    }
  }
}
