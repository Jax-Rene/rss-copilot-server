package com.rsscopilot.server.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsscopilot.server.config.AppProperties;
import com.rsscopilot.server.http.HttpResponseData;
import com.rsscopilot.server.http.SimpleHttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class ArticleContentExtractor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Safelist ARTICLE_HTML_SAFELIST =
      Safelist.relaxed()
          .addTags("figure", "figcaption", "picture", "source", "pre", "code")
          .addAttributes("a", "href", "title")
          .addAttributes("blockquote", "cite")
          .addAttributes("img", "src", "srcset", "alt", "title", "width", "height")
          .addAttributes("source", "src", "srcset", "type", "media")
          .addAttributes("video", "src", "poster", "controls")
          .addProtocols("a", "href", "http", "https", "mailto")
          .addProtocols("blockquote", "cite", "http", "https")
          .addProtocols("img", "src", "http", "https")
          .addProtocols("source", "src", "http", "https")
          .addProtocols("video", "src", "http", "https")
          .addProtocols("video", "poster", "http", "https");
  private static final List<String> ARTICLE_CONTAINER_SELECTORS =
      List.of(
          "article",
          "[itemprop=articleBody]",
          ".article-body",
          ".article-content",
          ".article__body",
          ".article__content",
          ".articleBody",
          ".article-text",
          ".articleText",
          ".entry-content",
          ".post-content",
          ".postArticle-content",
          ".post-body",
          ".body-content",
          ".story-body",
          ".story-content",
          ".content__article-body",
          ".c-article-body",
          "#article",
          "#post",
          "#story",
          "#content",
          "main");
  private static final String ARTICLE_CHROME_SELECTOR =
      String.join(
          ", ",
          "nav",
          "aside",
          "#comments",
          ".comments",
          ".comment-list",
          ".comment-section",
          ".related",
          ".related-posts",
          ".related-articles",
          ".recommendations",
          ".recommended",
          ".more-stories",
          ".share",
          ".share-buttons",
          ".social-share",
          ".advertisement",
          ".ad-banner",
          ".ad-container",
          ".newsletter",
          ".newsletter-signup",
          ".subscribe",
          ".subscribe-box");

  private final SimpleHttpClient simpleHttpClient;
  private final Duration timeout;

  public ArticleContentExtractor(SimpleHttpClient simpleHttpClient, AppProperties appProperties) {
    this.simpleHttpClient = simpleHttpClient;
    this.timeout = Duration.ofSeconds(appProperties.getRefresh().getReadTimeoutSeconds());
  }

  public ArticleContent extract(String articleUrl, String fallbackHtml, String fallbackText) {
    try {
      HttpResponseData response = simpleHttpClient.get(articleUrl, Map.of(), timeout);
      String contentBaseUrl = response.finalUrl();
      if (response.statusCode() >= 200
          && response.statusCode() < 300
          && response.body() != null
          && !response.body().isBlank()) {
        Document document = Jsoup.parse(response.body(), contentBaseUrl);
        ArticleContent structuredContent =
            articleContentFromStructuredData(document, contentBaseUrl);
        document.select("script,style,noscript,form").remove();
        ContentSelection contentSelection = selectContentRoot(document);
        Element contentRoot = contentSelection.element();
        if (!contentRoot.text().isBlank()) {
          removeChrome(contentRoot);
          normalizeContentUrls(contentRoot, contentBaseUrl);
          if (contentSelection.fallbackBody() && structuredContent != null) {
            return structuredContent;
          }
          String imageUrl =
              firstText(
                  firstImageUrl(contentRoot, contentBaseUrl),
                  firstMetaImageUrl(document, contentBaseUrl));
          return articleContent(contentRoot.html(), imageUrl, true);
        }
      }
    } catch (Exception ignored) {
      // AI and阅读链路允许降级，失败时回退 RSS 摘要内容。
    }
    return fallback(fallbackHtml, fallbackText, null, articleUrl);
  }

  private ContentSelection selectContentRoot(Document document) {
    for (String selector : ARTICLE_CONTAINER_SELECTORS) {
      Element bestElement = null;
      int bestTextLength = 0;
      for (Element candidate : document.select(selector)) {
        int textLength = candidate.text().trim().length();
        if (textLength > bestTextLength) {
          bestElement = candidate;
          bestTextLength = textLength;
        }
      }
      if (bestElement != null) {
        return new ContentSelection(bestElement, false);
      }
    }
    return new ContentSelection(document.body(), true);
  }

  public ArticleContent fallback(String fallbackHtml, String fallbackText, String coverImageUrl) {
    return fallback(fallbackHtml, fallbackText, coverImageUrl, null);
  }

  private ArticleContent fallback(
      String fallbackHtml, String fallbackText, String coverImageUrl, String baseUrl) {
    String normalizedHtml = normalizeFallbackHtml(fallbackHtml, baseUrl);
    String normalizedCoverImageUrl =
        firstText(coverImageUrl, fallbackCoverImageUrl(normalizedHtml, baseUrl));
    ArticleContent content = articleContent(normalizedHtml, normalizedCoverImageUrl, false);
    if (hasText(content.text()) || !hasText(fallbackText)) {
      return content;
    }
    return new ArticleContent(content.html(), fallbackText.trim(), normalizedCoverImageUrl, false);
  }

  private String normalizeFallbackHtml(String fallbackHtml, String baseUrl) {
    if (!hasText(fallbackHtml) || !hasText(baseUrl)) {
      return fallbackHtml;
    }
    Document document = Jsoup.parseBodyFragment(fallbackHtml, baseUrl);
    Element body = document.body();
    normalizeContentUrls(body, baseUrl);
    return body.html();
  }

  private String fallbackCoverImageUrl(String fallbackHtml, String baseUrl) {
    if (!hasText(fallbackHtml) || !hasText(baseUrl)) {
      return null;
    }
    Document document = Jsoup.parseBodyFragment(fallbackHtml, baseUrl);
    return firstImageUrl(document.body(), baseUrl);
  }

  private ArticleContent articleContent(String html, String coverImageUrl, boolean fetched) {
    String sanitizedHtml = sanitizeHtml(html);
    return new ArticleContent(
        sanitizedHtml, Jsoup.parse(sanitizedHtml).text(), coverImageUrl, fetched);
  }

  private ArticleContent articleContentFromStructuredData(Document document, String articleUrl) {
    for (Element script : document.select("script[type=application/ld+json]")) {
      JsonNode articleNode = readableArticleNode(script.data());
      if (articleNode == null) {
        continue;
      }
      String articleBody = textField(articleNode, "articleBody");
      if (!hasText(articleBody)) {
        continue;
      }
      String html = structuredArticleHtml(textField(articleNode, "headline"), articleBody);
      String imageUrl =
          firstText(
              structuredImageUrl(articleNode.get("image"), articleUrl),
              firstMetaImageUrl(document, articleUrl));
      return articleContent(html, imageUrl, true);
    }
    return null;
  }

  private JsonNode readableArticleNode(String json) {
    if (!hasText(json)) {
      return null;
    }
    try {
      return findReadableArticleNode(OBJECT_MAPPER.readTree(json));
    } catch (Exception exception) {
      return null;
    }
  }

  private JsonNode findReadableArticleNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        JsonNode articleNode = findReadableArticleNode(child);
        if (articleNode != null) {
          return articleNode;
        }
      }
      return null;
    }
    if (!node.isObject()) {
      return null;
    }
    if (isArticleStructuredData(node) && hasText(textField(node, "articleBody"))) {
      return node;
    }
    JsonNode graph = node.get("@graph");
    JsonNode articleNode = findReadableArticleNode(graph);
    if (articleNode != null) {
      return articleNode;
    }
    for (JsonNode child : node) {
      articleNode = findReadableArticleNode(child);
      if (articleNode != null) {
        return articleNode;
      }
    }
    return null;
  }

  private boolean isArticleStructuredData(JsonNode node) {
    JsonNode type = node.get("@type");
    if (type == null) {
      return false;
    }
    if (type.isArray()) {
      for (JsonNode item : type) {
        if (isArticleStructuredDataType(item.asText())) {
          return true;
        }
      }
      return false;
    }
    return isArticleStructuredDataType(type.asText());
  }

  private boolean isArticleStructuredDataType(String type) {
    if (!hasText(type)) {
      return false;
    }
    String normalizedType = type.trim().toLowerCase(Locale.ROOT);
    return normalizedType.endsWith("article")
        || normalizedType.endsWith("posting")
        || normalizedType.endsWith("newsarticle");
  }

  private String structuredArticleHtml(String headline, String articleBody) {
    Document document = Document.createShell("");
    Element body = document.body();
    if (hasText(headline)) {
      body.appendElement("h1").text(headline.trim());
    }
    for (String paragraph : articleBody.trim().split("(?:\\r?\\n){2,}")) {
      String normalizedParagraph = paragraph.trim();
      if (hasText(normalizedParagraph)) {
        body.appendElement("p").text(normalizedParagraph);
      }
    }
    return body.html();
  }

  private String structuredImageUrl(JsonNode imageNode, String baseUrl) {
    if (imageNode == null || imageNode.isNull()) {
      return null;
    }
    if (imageNode.isTextual()) {
      return usableResolvedUrl(baseUrl, imageNode.asText());
    }
    if (imageNode.isArray()) {
      for (JsonNode item : imageNode) {
        String imageUrl = structuredImageUrl(item, baseUrl);
        if (hasText(imageUrl)) {
          return imageUrl;
        }
      }
      return null;
    }
    if (imageNode.isObject()) {
      return firstText(
          usableResolvedUrl(baseUrl, textField(imageNode, "url")),
          usableResolvedUrl(baseUrl, textField(imageNode, "contentUrl")));
    }
    return null;
  }

  private String usableResolvedUrl(String baseUrl, String url) {
    String resolvedUrl = resolveUrl(baseUrl, url);
    return isUsableContentUrl(resolvedUrl) ? resolvedUrl : null;
  }

  private String textField(JsonNode node, String fieldName) {
    if (node == null || !node.has(fieldName) || !node.get(fieldName).isTextual()) {
      return null;
    }
    return node.get(fieldName).asText();
  }

  private String sanitizeHtml(String html) {
    if (!hasText(html)) {
      return html;
    }
    Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
    return Jsoup.clean(html, "", ARTICLE_HTML_SAFELIST, outputSettings);
  }

  private void removeChrome(Element contentRoot) {
    if ("body".equalsIgnoreCase(contentRoot.tagName())) {
      contentRoot.select("header,footer").remove();
    }
    contentRoot.select(ARTICLE_CHROME_SELECTOR).remove();
  }

  private void normalizeContentUrls(Element contentRoot, String baseUrl) {
    for (Element link : contentRoot.select("a[href]")) {
      String resolvedHref = resolveUrl(baseUrl, link.attr("href"));
      if (isUsableContentUrl(resolvedHref)) {
        link.attr("href", resolvedHref);
      }
    }

    for (Element image : contentRoot.select("img")) {
      normalizeImageSource(image, baseUrl);
    }

    for (Element media : contentRoot.select("source[src], video[src]")) {
      String resolvedSource = resolveUrl(baseUrl, media.attr("src"));
      if (isUsableContentUrl(resolvedSource)) {
        media.attr("src", resolvedSource);
      }
    }

    for (Element media :
        contentRoot.select("img[srcset], img[data-srcset], source[srcset], source[data-srcset]")) {
      normalizeSrcset(media, "srcset", baseUrl);
      if (!hasText(media.attr("srcset"))) {
        String normalizedLazySrcset = normalizedSrcset(media.attr("data-srcset"), baseUrl);
        if (hasText(normalizedLazySrcset)) {
          media.attr("srcset", normalizedLazySrcset);
        }
      }
    }
  }

  private void normalizeImageSource(Element image, String baseUrl) {
    String imageUrl =
        firstText(
            imageAttributeUrl(image, "src", baseUrl),
            imageAttributeUrl(image, "data-src", baseUrl),
            imageAttributeUrl(image, "data-original", baseUrl),
            imageAttributeUrl(image, "data-lazy-src", baseUrl),
            imageUrlFromSrcset(image.attr("srcset"), baseUrl),
            imageUrlFromSrcset(image.attr("data-srcset"), baseUrl));
    if (isUsableContentUrl(imageUrl)) {
      image.attr("src", imageUrl);
    }
  }

  private void normalizeSrcset(Element element, String attributeName, String baseUrl) {
    String srcset = element.attr(attributeName);
    if (!hasText(srcset)) {
      return;
    }

    String normalizedSrcset = normalizedSrcset(srcset, baseUrl);
    if (hasText(normalizedSrcset)) {
      element.attr(attributeName, normalizedSrcset);
    }
  }

  private String normalizedSrcset(String srcset, String baseUrl) {
    if (!hasText(srcset)) {
      return null;
    }

    List<String> normalizedCandidates = new ArrayList<>();
    for (String rawCandidate : srcset.split(",")) {
      String candidate = rawCandidate.trim();
      if (candidate.isEmpty()) {
        continue;
      }
      String[] parts = candidate.split("\\s+", 2);
      String resolvedUrl = resolveUrl(baseUrl, parts[0]);
      if (!isUsableContentUrl(resolvedUrl)) {
        continue;
      }
      normalizedCandidates.add(parts.length == 1 ? resolvedUrl : resolvedUrl + " " + parts[1]);
    }
    return normalizedCandidates.isEmpty() ? null : String.join(", ", normalizedCandidates);
  }

  private String firstImageUrl(Element contentRoot, String baseUrl) {
    for (Element image : contentRoot.select("img")) {
      String imageUrl =
          firstText(
              imageAttributeUrl(image, "src", baseUrl),
              imageAttributeUrl(image, "data-src", baseUrl),
              imageAttributeUrl(image, "data-original", baseUrl),
              imageAttributeUrl(image, "data-lazy-src", baseUrl),
              imageUrlFromSrcset(image.attr("srcset"), baseUrl),
              imageUrlFromSrcset(image.attr("data-srcset"), baseUrl));
      if (isUsableContentUrl(imageUrl)) {
        return imageUrl;
      }
    }
    return null;
  }

  private String firstMetaImageUrl(Document document, String baseUrl) {
    for (Element meta :
        document.select(
            "meta[property=og:image], meta[property=og:image:url], "
                + "meta[name=twitter:image], meta[name=twitter:image:src]")) {
      String imageUrl = resolveUrl(baseUrl, meta.attr("content"));
      if (isUsableContentUrl(imageUrl)) {
        return imageUrl;
      }
    }
    return null;
  }

  private String imageAttributeUrl(Element image, String attributeName, String baseUrl) {
    String imageUrl =
        firstText(image.absUrl(attributeName), resolveUrl(baseUrl, image.attr(attributeName)));
    return isUsableContentUrl(imageUrl) ? imageUrl : null;
  }

  private String imageUrlFromSrcset(String srcset, String baseUrl) {
    if (!hasText(srcset)) {
      return null;
    }
    String[] candidates = srcset.split(",");
    for (int index = candidates.length - 1; index >= 0; index--) {
      String candidate = candidates[index].trim();
      if (candidate.isEmpty()) {
        continue;
      }
      String[] parts = candidate.split("\\s+");
      if (parts.length > 0 && hasText(parts[0])) {
        return resolveUrl(baseUrl, parts[0]);
      }
    }
    return null;
  }

  private String resolveUrl(String baseUrl, String url) {
    if (!hasText(url)) {
      return null;
    }
    String trimmedUrl = url.trim();
    try {
      URI uri = URI.create(trimmedUrl);
      if (uri.isAbsolute()) {
        return uri.toString();
      }
      if (!hasText(baseUrl)) {
        return trimmedUrl;
      }
      return URI.create(baseUrl).resolve(uri).toString();
    } catch (IllegalArgumentException exception) {
      return trimmedUrl;
    }
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private boolean isUsableContentUrl(String url) {
    if (!hasText(url)) {
      return false;
    }
    String normalizedUrl = url.trim().toLowerCase(Locale.ROOT);
    return !normalizedUrl.startsWith("about:")
        && !normalizedUrl.startsWith("blob:")
        && !normalizedUrl.startsWith("data:")
        && !normalizedUrl.startsWith("javascript:");
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private record ContentSelection(Element element, boolean fallbackBody) {}
}
