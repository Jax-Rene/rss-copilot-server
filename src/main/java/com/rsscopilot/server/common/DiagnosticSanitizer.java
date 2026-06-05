package com.rsscopilot.server.common;

import java.util.regex.Pattern;

public final class DiagnosticSanitizer {

  private static final Pattern DEEPSEEK_KEY_PATTERN =
      Pattern.compile("\\bsk-[A-Za-z0-9][A-Za-z0-9_-]{6,}\\b");
  private static final Pattern BEARER_TOKEN_PATTERN =
      Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern BASIC_TOKEN_PATTERN =
      Pattern.compile("\\bBasic\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern AUTHORIZATION_HEADER_PATTERN =
      Pattern.compile(
          "\\b(Authorization)\\s*:\\s*(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern OPAQUE_AUTHORIZATION_HEADER_PATTERN =
      Pattern.compile(
          "\\b(Authorization)\\s*:\\s*(?!Bearer\\s+\\[redacted\\]|Basic\\s+\\[redacted\\])[^\\r\\n\\s]+",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern COOKIE_HEADER_PATTERN =
      Pattern.compile("\\b(Set-Cookie|Cookie)\\s*:\\s*[^\\r\\n]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern URL_USER_INFO_PATTERN =
      Pattern.compile("\\b([A-Za-z][A-Za-z0-9+.-]*://)[^\\s/?#@]+@");
  private static final Pattern SECRET_ASSIGNMENT_PATTERN =
      Pattern.compile(
          "\\b(x[_-]?api[_-]?key|api[_-]?key|access[_-]?token|auth[_-]?token|refresh[_-]?token|token|secret|password|signature|sig)\\s*[=:]\\s*([^&\\s]+)",
          Pattern.CASE_INSENSITIVE);

  private DiagnosticSanitizer() {}

  public static String redact(String value) {
    String redacted = DEEPSEEK_KEY_PATTERN.matcher(value).replaceAll("[redacted]");
    redacted = AUTHORIZATION_HEADER_PATTERN.matcher(redacted).replaceAll("$1: $2 [redacted]");
    redacted = BEARER_TOKEN_PATTERN.matcher(redacted).replaceAll("Bearer [redacted]");
    redacted = BASIC_TOKEN_PATTERN.matcher(redacted).replaceAll("Basic [redacted]");
    redacted = OPAQUE_AUTHORIZATION_HEADER_PATTERN.matcher(redacted).replaceAll("$1: [redacted]");
    redacted = COOKIE_HEADER_PATTERN.matcher(redacted).replaceAll("$1: [redacted]");
    redacted = URL_USER_INFO_PATTERN.matcher(redacted).replaceAll("$1redacted@");
    return SECRET_ASSIGNMENT_PATTERN.matcher(redacted).replaceAll("[redacted]");
  }
}
