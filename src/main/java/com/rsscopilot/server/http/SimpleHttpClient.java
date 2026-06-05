package com.rsscopilot.server.http;

import com.rsscopilot.server.common.AppException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SimpleHttpClient {

  private static final String USER_AGENT_HEADER = "User-Agent";
  private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
  private static final String DEFAULT_USER_AGENT = "RSSCopilot/0.1";
  private static final String DEFAULT_ACCEPT_ENCODING = "gzip, deflate";
  private static final Pattern CONTENT_TYPE_CHARSET_PATTERN =
      Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\"\\s]+)\"?");
  private static final Pattern XML_DECLARATION_CHARSET_PATTERN =
      Pattern.compile("(?i)<\\?xml\\s+[^>]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]");
  private static final int XML_DECLARATION_SCAN_BYTES = 512;

  private final HttpClient httpClient;

  public SimpleHttpClient() {
    this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  public HttpResponseData get(String url, Map<String, String> headers, Duration timeout) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(timeout);
    applyHeaders(builder, headers);
    return send(builder.build());
  }

  public HttpResponseData postJson(
      String url, String jsonBody, Map<String, String> headers, Duration timeout) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(timeout);
    applyHeaders(builder, headers);
    return send(builder.build());
  }

  private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
    if (!containsHeader(headers, USER_AGENT_HEADER)) {
      builder.header(USER_AGENT_HEADER, DEFAULT_USER_AGENT);
    }
    if (!containsHeader(headers, ACCEPT_ENCODING_HEADER)) {
      builder.header(ACCEPT_ENCODING_HEADER, DEFAULT_ACCEPT_ENCODING);
    }
    headers.forEach(builder::header);
  }

  private boolean containsHeader(Map<String, String> headers, String headerName) {
    return headers.keySet().stream().anyMatch(header -> header.equalsIgnoreCase(headerName));
  }

  private HttpResponseData send(HttpRequest request) {
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      String body = decodeBody(response.body(), response);
      return new HttpResponseData(
          response.statusCode(), body, response.headers().map(), response.uri().toString());
    } catch (IOException exception) {
      throw new AppException(
          "UPSTREAM_IO_ERROR", HttpStatus.BAD_GATEWAY, "upstream request failed", exception);
    } catch (UncheckedIOException exception) {
      throw new AppException(
          "UPSTREAM_IO_ERROR", HttpStatus.BAD_GATEWAY, "upstream request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AppException(
          "UPSTREAM_INTERRUPTED",
          HttpStatus.BAD_GATEWAY,
          "upstream request interrupted",
          exception);
    }
  }

  private String decodeBody(byte[] body, HttpResponse<byte[]> response) {
    byte[] decodedBody = decodeContentEncoding(body, response);
    Charset charset = charsetFromContentType(response).orElseGet(() -> charsetFromXml(decodedBody));
    String text = new String(decodedBody, charset);
    return stripUtf8Bom(text);
  }

  private byte[] decodeContentEncoding(byte[] body, HttpResponse<byte[]> response) {
    try {
      byte[] decodedBody = body;
      List<String> encodings =
          response.headers().allValues("Content-Encoding").stream()
              .flatMap(contentEncoding -> Arrays.stream(contentEncoding.split(",")))
              .map(String::trim)
              .map(String::toLowerCase)
              .filter(encoding -> !encoding.isEmpty())
              .toList();
      for (int index = encodings.size() - 1; index >= 0; index -= 1) {
        decodedBody = decodeSingleContentEncoding(decodedBody, encodings.get(index));
      }
      return decodedBody;
    } catch (IOException exception) {
      throw new UncheckedIOException("upstream response decompression failed", exception);
    }
  }

  private byte[] decodeSingleContentEncoding(byte[] body, String contentEncoding)
      throws IOException {
    return switch (contentEncoding) {
      case "identity" -> body;
      case "gzip", "x-gzip" -> new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
      case "deflate" -> inflateDeflate(body);
      default -> body;
    };
  }

  private byte[] inflateDeflate(byte[] body) throws IOException {
    try {
      return new InflaterInputStream(new ByteArrayInputStream(body)).readAllBytes();
    } catch (IOException exception) {
      Inflater rawInflater = new Inflater(true);
      try (InflaterInputStream input =
          new InflaterInputStream(new ByteArrayInputStream(body), rawInflater)) {
        return input.readAllBytes();
      } finally {
        rawInflater.end();
      }
    }
  }

  private Optional<Charset> charsetFromContentType(HttpResponse<byte[]> response) {
    return response
        .headers()
        .firstValue("Content-Type")
        .flatMap(this::charsetFromContentTypeHeader);
  }

  private Optional<Charset> charsetFromContentTypeHeader(String contentType) {
    Matcher matcher = CONTENT_TYPE_CHARSET_PATTERN.matcher(contentType);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return charsetByName(matcher.group(1));
  }

  private Charset charsetFromXml(byte[] body) {
    int scanLength = Math.min(body.length, XML_DECLARATION_SCAN_BYTES);
    String prefix = new String(body, 0, scanLength, StandardCharsets.ISO_8859_1);
    Matcher matcher = XML_DECLARATION_CHARSET_PATTERN.matcher(prefix);
    if (!matcher.find()) {
      return StandardCharsets.UTF_8;
    }
    return charsetByName(matcher.group(1)).orElse(StandardCharsets.UTF_8);
  }

  private Optional<Charset> charsetByName(String charsetName) {
    try {
      return Optional.of(Charset.forName(charsetName));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private String stripUtf8Bom(String text) {
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      return text.substring(1);
    }
    return text;
  }
}
