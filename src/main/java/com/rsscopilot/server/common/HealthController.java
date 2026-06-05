package com.rsscopilot.server.common;

import java.time.Instant;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  private static final String SERVICE_NAME = "rss-copilot-server";
  private static final int API_VERSION = 1;

  private final HealthEndpoint healthEndpoint;

  public HealthController(HealthEndpoint healthEndpoint) {
    this.healthEndpoint = healthEndpoint;
  }

  @GetMapping("/api/health")
  public HealthResponse health() {
    HealthComponent health = healthEndpoint.health();
    return new HealthResponse(
        SERVICE_NAME, API_VERSION, health.getStatus().getCode(), Instant.now());
  }
}
