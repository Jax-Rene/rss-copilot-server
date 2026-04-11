package com.rsscopilot.server.config;

import com.rsscopilot.server.auth.AuthInterceptor;
import com.rsscopilot.server.auth.CurrentUserArgumentResolver;
import com.rsscopilot.server.auth.SessionService;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebMvcConfig implements WebMvcConfigurer {

  private final SessionService sessionService;

  public WebMvcConfig(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new AuthInterceptor(sessionService))
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentUserArgumentResolver());
  }
}
