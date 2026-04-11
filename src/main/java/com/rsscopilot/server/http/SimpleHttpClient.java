package com.rsscopilot.server.http;

import com.rsscopilot.server.common.AppException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SimpleHttpClient {

  private final HttpClient httpClient;

  public SimpleHttpClient() {
    this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  public HttpResponseData get(String url, Map<String, String> headers, Duration timeout) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(timeout);
    headers.forEach(builder::header);
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
    headers.forEach(builder::header);
    return send(builder.build());
  }

  private HttpResponseData send(HttpRequest request) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return new HttpResponseData(response.statusCode(), response.body(), response.headers().map());
    } catch (IOException exception) {
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
}
