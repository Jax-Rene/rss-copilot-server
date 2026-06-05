import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Healthcheck {

  private static final String DEFAULT_PORT = "8080";
  private static final int OK_STATUS = 200;

  private Healthcheck() {}

  public static void main(String[] args) {
    try {
      URI healthUri = URI.create("http://127.0.0.1:" + serverPort() + "/api/health");
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpRequest request = HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(3)).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != OK_STATUS || !response.body().contains("\"status\":\"UP\"")) {
        System.exit(1);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      System.exit(1);
    } catch (RuntimeException | java.io.IOException exception) {
      System.exit(1);
    }
  }

  private static String serverPort() {
    String configuredPort = System.getenv("SERVER_PORT");
    if (configuredPort == null || configuredPort.isBlank()) {
      return DEFAULT_PORT;
    }
    return configuredPort.trim();
  }
}
