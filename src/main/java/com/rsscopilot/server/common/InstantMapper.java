package com.rsscopilot.server.common;

import java.time.Instant;

public final class InstantMapper {

  private InstantMapper() {}

  public static String toText(Instant instant) {
    return instant.toString();
  }

  public static Instant fromText(String value) {
    return value == null ? null : Instant.parse(value);
  }
}
