package com.rsscopilot.server.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rometools.rome.feed.module.DCModule;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndLink;
import com.rometools.rome.feed.synd.SyndPerson;
import com.rometools.rome.io.SyndFeedInput;
import com.rsscopilot.server.common.BadRequestException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jdom2.Element;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
public class RssFeedParser {

  private static final String JSON_FEED_VERSION_PREFIX = "https://jsonfeed.org/version/";
  private static final char UTF_8_BOM = '\uFEFF';

  private final ObjectMapper objectMapper = new ObjectMapper();

  public RssParsedFeed parse(String feedXml, String fallbackFeedUrl) {
    try {
      String normalizedFeed = stripLeadingBom(feedXml);
      if (looksLikeJsonFeed(normalizedFeed)) {
        return parseJsonFeed(normalizedFeed, fallbackFeedUrl);
      }
      rejectXmlDoctype(normalizedFeed, fallbackFeedUrl);
      SyndFeed syndFeed =
          new SyndFeedInput().build(new StringReader(normalizedFeed.stripLeading()));
      String siteUrl =
          resolveUrl(fallbackFeedUrl, preferredLink(syndFeed.getLinks(), syndFeed.getLink()));
      List<RssParsedFeed.RssParsedFeedEntry> parsedEntries = new ArrayList<>();
      for (SyndEntry entry : syndFeed.getEntries()) {
        String summaryHtml = extractSummaryHtml(entry);
        String summaryText = summaryHtml == null ? null : Jsoup.parse(summaryHtml).text();
        String link =
            resolveUrl(
                firstText(siteUrl, fallbackFeedUrl),
                preferredLink(entry.getLinks(), entry.getLink()));
        String entryBaseUrl = firstText(link, siteUrl, fallbackFeedUrl);
        parsedEntries.add(
            new RssParsedFeed.RssParsedFeedEntry(
                buildExternalId(entry, link, summaryHtml),
                entry.getTitle(),
                extractAuthor(entry),
                link,
                entry.getPublishedDate() != null
                    ? entry.getPublishedDate().toInstant()
                    : entry.getUpdatedDate() != null
                        ? entry.getUpdatedDate().toInstant()
                        : Instant.now(),
                summaryHtml,
                summaryText,
                extractCoverImageUrl(entry, summaryHtml, entryBaseUrl)));
      }
      String iconUrl = resolveIconUrl(syndFeed, siteUrl, fallbackFeedUrl);
      return new RssParsedFeed(syndFeed.getTitle(), siteUrl, iconUrl, parsedEntries);
    } catch (Exception exception) {
      throw new BadRequestException("invalid rss feed: " + fallbackFeedUrl);
    }
  }

  private boolean looksLikeJsonFeed(String feedXml) {
    return hasText(feedXml) && feedXml.stripLeading().startsWith("{");
  }

  private String stripLeadingBom(String feedContent) {
    if (feedContent == null || feedContent.isEmpty() || feedContent.charAt(0) != UTF_8_BOM) {
      return feedContent;
    }
    return feedContent.substring(1);
  }

  private void rejectXmlDoctype(String feedXml, String fallbackFeedUrl) {
    if (hasText(feedXml) && feedXml.toLowerCase(Locale.ROOT).contains("<!doctype")) {
      throw new BadRequestException("invalid rss feed: " + fallbackFeedUrl);
    }
  }

  private RssParsedFeed parseJsonFeed(String feedJson, String fallbackFeedUrl) throws Exception {
    JsonNode root = objectMapper.readTree(feedJson);
    validateJsonFeedVersion(root, fallbackFeedUrl);
    JsonNode itemsNode = root.path("items");
    if (!itemsNode.isArray()) {
      throw new BadRequestException("invalid rss feed: " + fallbackFeedUrl);
    }

    String siteUrl = resolveUrl(fallbackFeedUrl, text(root, "home_page_url"));
    String baseUrl = firstText(siteUrl, fallbackFeedUrl);
    String feedAuthor = jsonFeedAuthor(root);
    List<RssParsedFeed.RssParsedFeedEntry> entries = new ArrayList<>();
    for (JsonNode item : itemsNode) {
      String link = resolveUrl(baseUrl, firstText(text(item, "url"), text(item, "external_url")));
      String summaryHtml = firstText(text(item, "content_html"), text(item, "summary"));
      String contentText = text(item, "content_text");
      String summaryText =
          hasText(summaryHtml)
              ? Jsoup.parse(summaryHtml).text()
              : firstText(contentText, text(item, "summary"));
      entries.add(
          new RssParsedFeed.RssParsedFeedEntry(
              jsonEntryExternalId(item, link, summaryHtml, contentText),
              firstText(text(item, "title"), link, "Untitled"),
              jsonEntryAuthor(item, feedAuthor),
              link,
              jsonEntryPublishedAt(item),
              firstText(summaryHtml, contentText),
              summaryText,
              jsonEntryCoverImageUrl(item, firstText(summaryHtml, contentText), baseUrl)));
    }

    return new RssParsedFeed(
        text(root, "title"),
        siteUrl,
        resolveUrl(baseUrl, firstText(text(root, "icon"), text(root, "favicon"))),
        entries);
  }

  private void validateJsonFeedVersion(JsonNode root, String fallbackFeedUrl) {
    String version = text(root, "version");
    if (!hasText(version) || !version.startsWith(JSON_FEED_VERSION_PREFIX)) {
      throw new BadRequestException("invalid rss feed: " + fallbackFeedUrl);
    }
  }

  private String jsonEntryExternalId(
      JsonNode item, String link, String summaryHtml, String contentText) {
    String id = text(item, "id");
    if (hasText(id)) {
      return id;
    }
    if (hasText(link)) {
      return link;
    }
    String seed =
        String.join(
            "\n",
            nullToBlank(text(item, "title")),
            nullToBlank(text(item, "date_published")),
            nullToBlank(text(item, "date_modified")),
            nullToBlank(firstText(summaryHtml, contentText)));
    return "fallback:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }

  private String jsonFeedAuthor(JsonNode root) {
    return firstText(jsonAuthorsText(root.path("authors")), jsonAuthorText(root.path("author")));
  }

  private String jsonEntryAuthor(JsonNode item, String feedAuthor) {
    return firstText(
        jsonAuthorsText(item.path("authors")), jsonAuthorText(item.path("author")), feedAuthor);
  }

  private String jsonAuthorsText(JsonNode authors) {
    if (authors.isArray()) {
      for (JsonNode author : authors) {
        String authorText = jsonAuthorText(author);
        if (hasText(authorText)) {
          return authorText;
        }
      }
    }
    return null;
  }

  private String jsonEntryCoverImageUrl(JsonNode item, String summaryHtml, String baseUrl) {
    String imageUrl =
        resolveUrl(baseUrl, firstText(text(item, "image"), text(item, "banner_image")));
    if (hasText(imageUrl)) {
      return imageUrl;
    }
    imageUrl = extractCoverImageFromSummary(summaryHtml, baseUrl);
    if (hasText(imageUrl)) {
      return imageUrl;
    }
    return jsonAttachmentImageUrl(item.path("attachments"), baseUrl);
  }

  private String jsonAttachmentImageUrl(JsonNode attachments, String baseUrl) {
    if (!attachments.isArray()) {
      return null;
    }
    for (JsonNode attachment : attachments) {
      String url = text(attachment, "url");
      String mimeType = nullToBlank(text(attachment, "mime_type")).toLowerCase(Locale.ROOT);
      if (hasText(url) && (mimeType.startsWith("image/") || isImageUrl(url))) {
        return resolveUrl(baseUrl, url);
      }
    }
    return null;
  }

  private String jsonAuthorText(JsonNode author) {
    if (!author.isObject()) {
      return null;
    }
    return firstText(text(author, "name"), text(author, "url"), text(author, "avatar"));
  }

  private Instant jsonEntryPublishedAt(JsonNode item) {
    String publishedAt = firstText(text(item, "date_published"), text(item, "date_modified"));
    if (!hasText(publishedAt)) {
      return Instant.now();
    }
    try {
      return Instant.parse(publishedAt);
    } catch (DateTimeParseException exception) {
      return Instant.now();
    }
  }

  private String extractAuthor(SyndEntry entry) {
    String authorFromPeople = extractAuthorFromPeople(entry.getAuthors());
    if (hasText(authorFromPeople)) {
      return authorFromPeople;
    }
    if (hasText(entry.getAuthor())) {
      return entry.getAuthor().trim();
    }
    if (entry.getModule(DCModule.URI) instanceof DCModule dcModule) {
      List<String> creators = dcModule.getCreators();
      String dcCreator = creators == null ? null : firstText(creators.toArray(new String[0]));
      if (hasText(dcCreator)) {
        return dcCreator;
      }
    }
    return extractAuthorFromForeignMarkup(entry.getForeignMarkup());
  }

  private String preferredLink(List<SyndLink> links, String fallbackLink) {
    if (links == null || links.isEmpty()) {
      return fallbackLink;
    }
    String genericLink = null;
    for (SyndLink link : links) {
      if (link == null || !hasText(link.getHref())) {
        continue;
      }
      String rel = nullToBlank(link.getRel()).toLowerCase(Locale.ROOT);
      String type = nullToBlank(link.getType()).toLowerCase(Locale.ROOT);
      if ("alternate".equals(rel) && isReadableLinkType(type)) {
        return link.getHref();
      }
      if ((rel.isEmpty() || "alternate".equals(rel)) && genericLink == null) {
        genericLink = link.getHref();
      }
    }
    return firstText(genericLink, fallbackLink);
  }

  private boolean isReadableLinkType(String type) {
    return type.isEmpty() || type.startsWith("text/") || type.contains("html");
  }

  private String extractAuthorFromPeople(List<SyndPerson> people) {
    if (people == null || people.isEmpty()) {
      return null;
    }
    for (SyndPerson person : people) {
      if (person == null) {
        continue;
      }
      String author = firstText(person.getName(), person.getEmail(), person.getUri());
      if (hasText(author)) {
        return author;
      }
    }
    return null;
  }

  private String extractAuthorFromForeignMarkup(List<Element> elements) {
    if (elements == null || elements.isEmpty()) {
      return null;
    }
    for (Element element : elements) {
      String name = element.getName().toLowerCase(Locale.ROOT);
      if ("creator".equals(name) || "author".equals(name)) {
        String author = firstText(element.getTextNormalize(), element.getAttributeValue("name"));
        if (hasText(author)) {
          return author;
        }
      }
      String childAuthor = extractAuthorFromForeignMarkup(element.getChildren());
      if (hasText(childAuthor)) {
        return childAuthor;
      }
    }
    return null;
  }

  private String extractSummaryHtml(SyndEntry entry) {
    if (entry.getContents() != null && !entry.getContents().isEmpty()) {
      for (SyndContent content : entry.getContents()) {
        if (content != null && hasText(content.getValue())) {
          return content.getValue();
        }
      }
    }
    if (entry.getDescription() != null && hasText(entry.getDescription().getValue())) {
      return entry.getDescription().getValue();
    }
    return null;
  }

  private String buildExternalId(SyndEntry entry, String link, String summaryHtml) {
    if (entry.getUri() != null && !entry.getUri().isBlank()) {
      return entry.getUri().trim();
    }
    if (hasText(link)) {
      return link;
    }
    String seed =
        String.join(
            "\n",
            nullToBlank(entry.getTitle()),
            entry.getPublishedDate() == null ? "" : entry.getPublishedDate().toInstant().toString(),
            entry.getUpdatedDate() == null ? "" : entry.getUpdatedDate().toInstant().toString(),
            nullToBlank(summaryHtml));
    return "fallback:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value.trim();
  }

  private String extractCoverImageUrl(SyndEntry entry, String summaryHtml, String baseUrl) {
    String summaryImageUrl = extractCoverImageFromSummary(summaryHtml, baseUrl);
    if (hasText(summaryImageUrl)) {
      return summaryImageUrl;
    }
    String linkImageUrl = extractCoverImageFromLinks(entry.getLinks(), baseUrl);
    if (hasText(linkImageUrl)) {
      return linkImageUrl;
    }
    String mediaImageUrl = extractCoverImageFromForeignMarkup(entry.getForeignMarkup(), baseUrl);
    if (hasText(mediaImageUrl)) {
      return mediaImageUrl;
    }
    return extractCoverImageFromEnclosures(entry.getEnclosures(), baseUrl);
  }

  private String extractCoverImageFromSummary(String summaryHtml, String baseUrl) {
    if (!hasText(summaryHtml)) {
      return null;
    }
    for (org.jsoup.nodes.Element image : Jsoup.parse(summaryHtml, baseUrl).select("img")) {
      String imageUrl = summaryImageUrl(image, baseUrl);
      if (hasText(imageUrl)) {
        return imageUrl;
      }
    }
    return null;
  }

  private String summaryImageUrl(org.jsoup.nodes.Element image, String baseUrl) {
    String directUrl =
        firstText(
            summaryImageAttributeUrl(image, "src", baseUrl),
            summaryImageAttributeUrl(image, "data-src", baseUrl),
            summaryImageAttributeUrl(image, "data-original", baseUrl),
            summaryImageAttributeUrl(image, "data-lazy-src", baseUrl));
    if (hasText(directUrl)) {
      return directUrl;
    }
    return firstText(
        imageUrlFromSrcset(image.attr("srcset"), baseUrl),
        imageUrlFromSrcset(image.attr("data-srcset"), baseUrl));
  }

  private String summaryImageAttributeUrl(
      org.jsoup.nodes.Element image, String attributeName, String baseUrl) {
    String imageUrl =
        firstText(image.absUrl(attributeName), resolveUrl(baseUrl, image.attr(attributeName)));
    return isUsableSummaryImageUrl(imageUrl) ? imageUrl : null;
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
        String imageUrl = resolveUrl(baseUrl, parts[0]);
        if (isUsableSummaryImageUrl(imageUrl)) {
          return imageUrl;
        }
      }
    }
    return null;
  }

  private boolean isUsableSummaryImageUrl(String imageUrl) {
    if (!hasText(imageUrl)) {
      return false;
    }
    String normalizedUrl = imageUrl.trim().toLowerCase(Locale.ROOT);
    return !normalizedUrl.startsWith("about:")
        && !normalizedUrl.startsWith("blob:")
        && !normalizedUrl.startsWith("data:")
        && !normalizedUrl.startsWith("javascript:");
  }

  private String extractCoverImageFromLinks(List<SyndLink> links, String baseUrl) {
    if (links == null || links.isEmpty()) {
      return null;
    }
    for (SyndLink link : links) {
      if (link == null || !hasText(link.getHref())) {
        continue;
      }
      String rel = nullToBlank(link.getRel()).toLowerCase(Locale.ROOT);
      String type = nullToBlank(link.getType()).toLowerCase(Locale.ROOT);
      boolean imageLink =
          "enclosure".equals(rel) && (type.startsWith("image/") || isImageUrl(link.getHref()));
      if (imageLink) {
        return resolveUrl(baseUrl, link.getHref());
      }
    }
    return null;
  }

  private String extractCoverImageFromForeignMarkup(List<Element> elements, String baseUrl) {
    if (elements == null || elements.isEmpty()) {
      return null;
    }
    for (Element element : elements) {
      String candidateUrl = mediaImageUrl(element);
      if (hasText(candidateUrl)) {
        return resolveUrl(baseUrl, candidateUrl);
      }
      String childCandidateUrl = extractCoverImageFromForeignMarkup(element.getChildren(), baseUrl);
      if (hasText(childCandidateUrl)) {
        return childCandidateUrl;
      }
    }
    return null;
  }

  private String mediaImageUrl(Element element) {
    String name = element.getName().toLowerCase(Locale.ROOT);
    if ("image".equals(name) && isItunesElement(element)) {
      return firstText(element.getAttributeValue("href"), element.getAttributeValue("url"));
    }
    if (!"thumbnail".equals(name) && !"content".equals(name)) {
      return null;
    }

    String url = element.getAttributeValue("url");
    if (!hasText(url)) {
      return null;
    }
    if ("thumbnail".equals(name)) {
      return url;
    }

    String type = nullToBlank(element.getAttributeValue("type")).toLowerCase(Locale.ROOT);
    String medium = nullToBlank(element.getAttributeValue("medium")).toLowerCase(Locale.ROOT);
    return type.startsWith("image/") || "image".equals(medium) || isImageUrl(url) ? url : null;
  }

  private boolean isItunesElement(Element element) {
    String prefix = nullToBlank(element.getNamespacePrefix()).toLowerCase(Locale.ROOT);
    String namespaceUri = nullToBlank(element.getNamespaceURI()).toLowerCase(Locale.ROOT);
    return "itunes".equals(prefix) || namespaceUri.contains("itunes");
  }

  private String extractCoverImageFromEnclosures(List<SyndEnclosure> enclosures, String baseUrl) {
    if (enclosures == null || enclosures.isEmpty()) {
      return null;
    }
    for (SyndEnclosure enclosure : enclosures) {
      String url = enclosure.getUrl();
      if (hasText(url) && isImageEnclosure(enclosure)) {
        return resolveUrl(baseUrl, url);
      }
    }
    return null;
  }

  private boolean isImageEnclosure(SyndEnclosure enclosure) {
    String type = nullToBlank(enclosure.getType()).toLowerCase(Locale.ROOT);
    return type.startsWith("image/") || isImageUrl(enclosure.getUrl());
  }

  private boolean isImageUrl(String url) {
    String normalizedUrl = nullToBlank(url).toLowerCase(Locale.ROOT);
    int queryStart = normalizedUrl.indexOf('?');
    if (queryStart >= 0) {
      normalizedUrl = normalizedUrl.substring(0, queryStart);
    }
    return normalizedUrl.endsWith(".jpg")
        || normalizedUrl.endsWith(".jpeg")
        || normalizedUrl.endsWith(".png")
        || normalizedUrl.endsWith(".gif")
        || normalizedUrl.endsWith(".webp")
        || normalizedUrl.endsWith(".avif");
  }

  private String resolveIconUrl(SyndFeed syndFeed, String siteUrl, String fallbackFeedUrl) {
    if (syndFeed.getIcon() != null && hasText(syndFeed.getIcon().getUrl())) {
      return resolveUrl(firstText(siteUrl, fallbackFeedUrl), syndFeed.getIcon().getUrl());
    }
    if (syndFeed.getImage() != null && hasText(syndFeed.getImage().getUrl())) {
      return resolveUrl(firstText(siteUrl, fallbackFeedUrl), syndFeed.getImage().getUrl());
    }
    String foreignIconUrl =
        extractFeedIconFromForeignMarkup(
            syndFeed.getForeignMarkup(), firstText(siteUrl, fallbackFeedUrl));
    if (hasText(foreignIconUrl)) {
      return foreignIconUrl;
    }
    return inferFavicon(siteUrl);
  }

  private String extractFeedIconFromForeignMarkup(List<Element> elements, String baseUrl) {
    if (elements == null || elements.isEmpty()) {
      return null;
    }
    for (Element element : elements) {
      String iconUrl = feedIconUrl(element);
      if (hasText(iconUrl)) {
        return resolveUrl(baseUrl, iconUrl);
      }
      String childIconUrl = extractFeedIconFromForeignMarkup(element.getChildren(), baseUrl);
      if (hasText(childIconUrl)) {
        return childIconUrl;
      }
    }
    return null;
  }

  private String feedIconUrl(Element element) {
    String name = element.getName().toLowerCase(Locale.ROOT);
    boolean knownIconElement =
        ("icon".equals(name) || "logo".equals(name)) && isFeedIconElement(element);
    if (!knownIconElement) {
      return null;
    }
    return firstText(
        element.getAttributeValue("href"),
        element.getAttributeValue("url"),
        element.getTextNormalize());
  }

  private boolean isFeedIconElement(Element element) {
    String prefix = nullToBlank(element.getNamespacePrefix()).toLowerCase(Locale.ROOT);
    String namespaceUri = nullToBlank(element.getNamespaceURI()).toLowerCase(Locale.ROOT);
    return prefix.isEmpty()
        || "atom".equals(prefix)
        || "webfeeds".equals(prefix)
        || namespaceUri.contains("atom")
        || namespaceUri.contains("webfeeds");
  }

  private String inferFavicon(String siteUrl) {
    if (!hasText(siteUrl)) {
      return null;
    }
    try {
      URI uri = URI.create(siteUrl);
      if (!hasText(uri.getScheme()) || !hasText(uri.getHost())) {
        return null;
      }
      String origin =
          uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
      return origin + "/favicon.ico";
    } catch (IllegalArgumentException exception) {
      return null;
    }
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

  private String text(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    if (!value.isTextual()) {
      return null;
    }
    return value.asText();
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
