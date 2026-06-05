package com.rsscopilot.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class SimpleHttpClientUnitTest {

  @Test
  void decodesBodyWithContentTypeCharset() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/xml; charset=ISO-8859-1")
              .setBody(new Buffer().write("Café RSS".getBytes(StandardCharsets.ISO_8859_1))));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/latin-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.body()).isEqualTo("Café RSS");
    }
  }

  @Test
  void decodesXmlBodyWithDeclaredEncodingWhenContentTypeHasNoCharset() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      String xml =
          """
          <?xml version="1.0" encoding="GBK"?>
          <rss><channel><title>中文订阅</title></channel></rss>
          """;
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml")
              .setBody(new Buffer().write(xml.getBytes(Charset.forName("GBK")))));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/gbk-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.body()).contains("<title>中文订阅</title>");
    }
  }

  @Test
  void fallsBackToUtf8AndStripsUtf8Bom() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml")
              .setBody(
                  new Buffer().write("\uFEFF<rss>Résumé</rss>".getBytes(StandardCharsets.UTF_8))));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/utf8-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.body()).isEqualTo("<rss>Résumé</rss>");
    }
  }

  @Test
  void requestsAndDecodesGzipResponses() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml; charset=UTF-8")
              .setHeader("Content-Encoding", "gzip")
              .setBody(new Buffer().write(gzip("<rss><title>Compressed RSS</title></rss>"))));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/gzip-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(server.takeRequest().getHeader("Accept-Encoding")).isEqualTo("gzip, deflate");
      assertThat(response.body()).isEqualTo("<rss><title>Compressed RSS</title></rss>");
    }
  }

  @Test
  void decodesDeflateResponses() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml; charset=UTF-8")
              .setHeader("Content-Encoding", "deflate")
              .setBody(new Buffer().write(deflate("<rss><title>Deflated RSS</title></rss>"))));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/deflate-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.body()).isEqualTo("<rss><title>Deflated RSS</title></rss>");
    }
  }

  @Test
  void decodesRawDeflateResponses() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml; charset=UTF-8")
              .setHeader("Content-Encoding", "deflate")
              .setBody(
                  new Buffer().write(rawDeflate("<rss><title>Raw Deflate RSS</title></rss>"))));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/raw-deflate-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.body()).isEqualTo("<rss><title>Raw Deflate RSS</title></rss>");
    }
  }

  @Test
  void decodesChainedContentEncodingsInReverseOrder() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      byte[] encodedBody = gzip(deflate("<rss><title>Chained Encoding RSS</title></rss>"));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml; charset=UTF-8")
              .setHeader("Content-Encoding", "deflate, gzip")
              .setBody(new Buffer().write(encodedBody)));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/chained-feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.body()).isEqualTo("<rss><title>Chained Encoding RSS</title></rss>");
    }
  }

  @Test
  void decodesRepeatedContentEncodingHeadersInReverseOrder() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      byte[] encodedBody = gzip(deflate("<rss><title>Repeated Encoding RSS</title></rss>"));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml; charset=UTF-8")
              .addHeader("Content-Encoding", "deflate")
              .addHeader("Content-Encoding", "gzip")
              .setBody(new Buffer().write(encodedBody)));
      server.start();

      HttpResponseData response =
          new SimpleHttpClient()
              .get(
                  server.url("/repeated-encoding-feed.xml").toString(),
                  Map.of(),
                  Duration.ofSeconds(1));

      assertThat(response.body()).isEqualTo("<rss><title>Repeated Encoding RSS</title></rss>");
    }
  }

  @Test
  void followsRedirectsAndReportsFinalUrl() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .setHeader("Location", server.url("/canonical-feed.xml").toString()));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/rss+xml; charset=UTF-8")
              .setBody("<rss><title>Redirected RSS</title></rss>"));

      HttpResponseData response =
          new SimpleHttpClient()
              .get(server.url("/feed.xml").toString(), Map.of(), Duration.ofSeconds(1));

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("<rss><title>Redirected RSS</title></rss>");
      assertThat(response.finalUrl()).isEqualTo(server.url("/canonical-feed.xml").toString());
    }
  }

  private byte[] gzip(String value) throws Exception {
    return gzip(value.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] gzip(byte[] value) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOutput = new GZIPOutputStream(output)) {
      gzipOutput.write(value);
    }
    return output.toByteArray();
  }

  private byte[] deflate(String value) throws Exception {
    return deflate(value.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] deflate(byte[] value) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflateOutput = new DeflaterOutputStream(output)) {
      deflateOutput.write(value);
    }
    return output.toByteArray();
  }

  private byte[] rawDeflate(String value) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    try (DeflaterOutputStream deflateOutput = new DeflaterOutputStream(output, deflater)) {
      deflateOutput.write(value.getBytes(StandardCharsets.UTF_8));
    } finally {
      deflater.end();
    }
    return output.toByteArray();
  }
}
