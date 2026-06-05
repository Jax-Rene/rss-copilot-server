package com.rsscopilot.server.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsscopilot.server.common.AppException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FeedSourceServiceUnitTest {

  @Test
  void shouldKeepRefreshFailureMessageWhenExceptionMessageExists() {
    assertThat(FeedSourceService.refreshFailureMessage(new RuntimeException("socket closed")))
        .isEqualTo("socket closed");
  }

  @Test
  void shouldRedactSecretsFromRefreshFailureMessage() {
    String message =
        FeedSourceService.refreshFailureMessage(
            new RuntimeException(
                "failed to fetch https://feed-user:feed-pass@private.example/rss?x-api-key=abc123&topic=ai Authorization: Bearer header.jwt X-API-Key: source-key password: source-pass Bearer abc.def Basic YmFzaWMtc2VjcmV0 Cookie: session=raw-session; theme=dark\nSet-Cookie: refresh=raw-refresh; Path=/ sk-abc123456789"));

    assertThat(message)
        .isEqualTo(
            "failed to fetch https://redacted@private.example/rss?[redacted]&topic=ai Authorization: Bearer [redacted] [redacted] [redacted] Bearer [redacted] Basic [redacted] Cookie: [redacted]\nSet-Cookie: [redacted]");
    assertThat(message).doesNotContain("feed-user", "feed-pass", "x-api-key", "abc123", "sk-");
    assertThat(message).doesNotContain("YmFzaWMtc2VjcmV0", "raw-session", "raw-refresh");
    assertThat(message).doesNotContain("source-key", "source-pass", "header.jwt");
  }

  @Test
  void shouldFallbackRefreshFailureMessageWhenExceptionMessageIsBlank() {
    assertThat(FeedSourceService.refreshFailureMessage(new RuntimeException(" ")))
        .isEqualTo("rss refresh failed: RuntimeException");
  }

  @Test
  void shouldFallbackRefreshFailureMessageWhenExceptionMessageIsNull() {
    assertThat(FeedSourceService.refreshFailureMessage(new RuntimeException()))
        .isEqualTo("rss refresh failed: RuntimeException");
  }

  @Test
  void shouldExplainRefreshTimeouts() {
    assertThat(
            FeedSourceService.refreshFailureMessage(
                new AppException(
                    "UPSTREAM_IO_ERROR",
                    HttpStatus.BAD_GATEWAY,
                    "upstream request failed",
                    new HttpTimeoutException("request timed out"))))
        .isEqualTo("rss refresh timed out");
  }

  @Test
  void shouldExplainDnsFailures() {
    assertThat(
            FeedSourceService.refreshFailureMessage(
                new AppException(
                    "UPSTREAM_IO_ERROR",
                    HttpStatus.BAD_GATEWAY,
                    "upstream request failed",
                    new UnknownHostException("feed.example.com"))))
        .isEqualTo("rss host could not be resolved: feed.example.com");
  }

  @Test
  void shouldExplainConnectionFailures() {
    assertThat(
            FeedSourceService.refreshFailureMessage(
                new AppException(
                    "UPSTREAM_IO_ERROR",
                    HttpStatus.BAD_GATEWAY,
                    "upstream request failed",
                    new ConnectException("Connection refused"))))
        .isEqualTo("rss connection failed: Connection refused");
  }

  @Test
  void shouldExplainTlsFailures() {
    assertThat(
            FeedSourceService.refreshFailureMessage(
                new AppException(
                    "UPSTREAM_IO_ERROR",
                    HttpStatus.BAD_GATEWAY,
                    "upstream request failed",
                    new SSLHandshakeException("certificate expired"))))
        .isEqualTo("rss TLS handshake failed: certificate expired");
  }

  @Test
  void shouldExplainResponseDecodeFailures() {
    assertThat(
            FeedSourceService.refreshFailureMessage(
                new UncheckedIOException(
                    "upstream response decompression failed",
                    new IOException("incorrect header check"))))
        .isEqualTo("rss response decode failed: incorrect header check");
  }

  @Test
  void shouldTruncateLongRefreshFailureMessages() {
    String longMessage = "x".repeat(260);
    assertThat(FeedSourceService.refreshFailureMessage(new RuntimeException(longMessage)))
        .hasSize(240)
        .endsWith("...");
  }
}
