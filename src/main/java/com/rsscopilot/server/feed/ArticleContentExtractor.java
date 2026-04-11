package com.rsscopilot.server.feed;

import com.rsscopilot.server.config.AppProperties;
import com.rsscopilot.server.http.HttpResponseData;
import com.rsscopilot.server.http.SimpleHttpClient;
import java.time.Duration;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class ArticleContentExtractor {

  private final SimpleHttpClient simpleHttpClient;
  private final Duration timeout;

  public ArticleContentExtractor(SimpleHttpClient simpleHttpClient, AppProperties appProperties) {
    this.simpleHttpClient = simpleHttpClient;
    this.timeout = Duration.ofSeconds(appProperties.getRefresh().getReadTimeoutSeconds());
  }

  public ArticleContent extract(String articleUrl, String fallbackHtml, String fallbackText) {
    try {
      HttpResponseData response = simpleHttpClient.get(articleUrl, Map.of(), timeout);
      if (response.statusCode() >= 200
          && response.statusCode() < 300
          && response.body() != null
          && !response.body().isBlank()) {
        Document document = Jsoup.parse(response.body(), articleUrl);
        document.select("script,style,noscript,nav,header,footer,aside,form").remove();
        Element contentRoot =
            firstNonNull(
                document.selectFirst("article"), document.selectFirst("main"), document.body());
        if (!contentRoot.text().isBlank()) {
          String imageUrl =
              contentRoot.select("img[src]").stream()
                  .map(element -> element.absUrl("src"))
                  .filter(url -> !url.isBlank())
                  .findFirst()
                  .orElse(null);
          return new ArticleContent(contentRoot.html(), contentRoot.text(), imageUrl, true);
        }
      }
    } catch (Exception ignored) {
      // AI and阅读链路允许降级，失败时回退 RSS 摘要内容。
    }
    return new ArticleContent(fallbackHtml, fallbackText, null, false);
  }

  private Element firstNonNull(Element first, Element second, Element third) {
    if (first != null) {
      return first;
    }
    if (second != null) {
      return second;
    }
    return third;
  }
}
