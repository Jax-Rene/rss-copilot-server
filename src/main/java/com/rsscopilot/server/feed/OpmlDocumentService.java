package com.rsscopilot.server.feed;

import com.rsscopilot.server.common.BadRequestException;
import com.rsscopilot.server.common.PayloadTooLargeException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Service
public class OpmlDocumentService {

  private static final int MAX_OPML_CHARACTER_COUNT = 1_000_000;
  private static final int MAX_SUBSCRIPTION_COUNT = 1_000;
  private static final char UTF_8_BOM = '\uFEFF';

  public List<OpmlSubscription> parseSubscriptions(String opml) {
    validateOpmlSize(opml);
    String normalizedOpml = stripLeadingBom(opml);
    try {
      DocumentBuilderFactory factory = secureDocumentBuilderFactory();
      Document document =
          factory.newDocumentBuilder().parse(new InputSource(new StringReader(normalizedOpml)));
      List<OpmlSubscription> subscriptions = new ArrayList<>();
      Element root = document.getDocumentElement();
      validateOpmlRoot(root);
      Element body = firstElementByTagNameIgnoreCase(document, "body");
      Element outlineRoot = body == null ? root : body;
      collectSubscriptions(outlineRoot, null, subscriptions);
      return subscriptions;
    } catch (BadRequestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BadRequestException("invalid opml document");
    }
  }

  public String render(List<FeedSourceResponse> sources) {
    try {
      StringWriter output = new StringWriter();
      XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
      writer.writeStartDocument("UTF-8", "1.0");
      writer.writeStartElement("opml");
      writer.writeAttribute("version", "2.0");
      writer.writeStartElement("head");
      writer.writeStartElement("title");
      writer.writeCharacters("RSS Copilot subscriptions");
      writer.writeEndElement();
      writer.writeEndElement();
      writer.writeStartElement("body");
      FolderNode rootFolder = buildFolderTree(sources);
      for (FeedSourceResponse source : sortedSources(rootFolder.sources())) {
        writeSourceOutline(writer, source);
      }
      for (FolderNode folder : sortedFolders(rootFolder.children())) {
        writeFolderOutline(writer, folder);
      }
      writer.writeEndElement();
      writer.writeEndElement();
      writer.writeEndDocument();
      writer.close();
      return output.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("failed to render opml", exception);
    }
  }

  private void collectSubscriptions(
      Element parent, String folder, List<OpmlSubscription> subscriptions) {
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index += 1) {
      Node node = children.item(index);
      if (node.getNodeType() != Node.ELEMENT_NODE
          || !"outline".equalsIgnoreCase(node.getNodeName())) {
        continue;
      }
      Element outline = (Element) node;
      String title =
          firstNonBlank(attributeValue(outline, "title"), attributeValue(outline, "text"));
      String xmlUrl = attributeValue(outline, "xmlUrl");
      if (StringUtils.hasText(xmlUrl)) {
        if (subscriptions.size() >= MAX_SUBSCRIPTION_COUNT) {
          throw new BadRequestException("opml contains too many subscriptions");
        }
        String folderName =
            firstNonBlank(folder, categoryFolder(attributeValue(outline, "category")));
        subscriptions.add(
            new OpmlSubscription(title, xmlUrl, attributeValue(outline, "htmlUrl"), folderName));
      } else {
        collectSubscriptions(outline, childFolder(folder, title), subscriptions);
      }
    }
  }

  private void validateOpmlSize(String opml) {
    if (opml != null && opml.length() > MAX_OPML_CHARACTER_COUNT) {
      throw new PayloadTooLargeException("opml document is too large");
    }
  }

  private String stripLeadingBom(String opml) {
    if (opml == null || opml.isEmpty() || opml.charAt(0) != UTF_8_BOM) {
      return opml;
    }
    return opml.substring(1);
  }

  private void validateOpmlRoot(Element root) {
    if (root == null || !"opml".equalsIgnoreCase(root.getNodeName())) {
      throw new BadRequestException("invalid opml document");
    }
  }

  private Element firstElementByTagNameIgnoreCase(Document document, String tagName) {
    NodeList elements = document.getElementsByTagName("*");
    for (int index = 0; index < elements.getLength(); index += 1) {
      Node node = elements.item(index);
      if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equalsIgnoreCase(node.getNodeName())) {
        return (Element) node;
      }
    }
    return null;
  }

  private FolderNode buildFolderTree(List<FeedSourceResponse> sources) {
    FolderNode rootFolder = new FolderNode(null);
    for (FeedSourceResponse source : sources) {
      String folder =
          StringUtils.hasText(source.folder())
              ? source.folder().trim()
              : FeedSourceService.DEFAULT_FOLDER;
      List<String> folderPath = splitFolderPath(folder);
      if (folderPath.isEmpty()) {
        rootFolder.sources().add(source);
        continue;
      }
      FolderNode currentFolder = rootFolder;
      for (String folderName : folderPath) {
        currentFolder = currentFolder.children().computeIfAbsent(folderName, FolderNode::new);
      }
      currentFolder.sources().add(source);
    }
    return rootFolder;
  }

  private void writeFolderOutline(XMLStreamWriter writer, FolderNode folder) throws Exception {
    writer.writeStartElement("outline");
    writer.writeAttribute("text", folder.name());
    writer.writeAttribute("title", folder.name());
    for (FeedSourceResponse source : sortedSources(folder.sources())) {
      writeSourceOutline(writer, source);
    }
    for (FolderNode childFolder : sortedFolders(folder.children())) {
      writeFolderOutline(writer, childFolder);
    }
    writer.writeEndElement();
  }

  private List<FeedSourceResponse> sortedSources(List<FeedSourceResponse> sources) {
    return sources.stream()
        .sorted(
            Comparator.comparing(FeedSourceResponse::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FeedSourceResponse::rssUrl, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private List<FolderNode> sortedFolders(Map<String, FolderNode> folders) {
    return folders.values().stream()
        .sorted(Comparator.comparing(FolderNode::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private void writeSourceOutline(XMLStreamWriter writer, FeedSourceResponse source)
      throws Exception {
    writer.writeStartElement("outline");
    writer.writeAttribute("text", source.name());
    writer.writeAttribute("title", source.name());
    writer.writeAttribute("type", "rss");
    writer.writeAttribute("xmlUrl", source.rssUrl());
    String category = exportCategory(source.folder());
    if (StringUtils.hasText(category)) {
      writer.writeAttribute("category", category);
    }
    if (StringUtils.hasText(source.siteUrl())) {
      writer.writeAttribute("htmlUrl", source.siteUrl());
    }
    writer.writeEndElement();
  }

  private DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }

  private String firstNonBlank(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first;
    }
    if (StringUtils.hasText(second)) {
      return second;
    }
    return null;
  }

  private List<String> splitFolderPath(String folder) {
    if (!StringUtils.hasText(folder) || FeedSourceService.DEFAULT_FOLDER.equals(folder.trim())) {
      return List.of();
    }
    List<String> path = new ArrayList<>();
    for (String folderName : folder.split(" / ")) {
      if (StringUtils.hasText(folderName)) {
        path.add(folderName.trim());
      }
    }
    return path;
  }

  private String childFolder(String parentFolder, String childName) {
    if (!StringUtils.hasText(childName)) {
      return parentFolder;
    }
    String normalizedChildName = childName.trim();
    if (!StringUtils.hasText(parentFolder)) {
      return normalizedChildName;
    }
    return parentFolder.trim() + " / " + normalizedChildName;
  }

  private String categoryFolder(String category) {
    if (!StringUtils.hasText(category)) {
      return null;
    }
    String firstCategory = category.split(",")[0].trim();
    if (!StringUtils.hasText(firstCategory)) {
      return null;
    }
    List<String> path = new ArrayList<>();
    for (String segment : firstCategory.split("/")) {
      if (StringUtils.hasText(segment)) {
        path.add(segment.trim());
      }
    }
    return path.isEmpty() ? null : String.join(" / ", path);
  }

  private String exportCategory(String folder) {
    List<String> path = splitFolderPath(folder);
    if (path.isEmpty()) {
      return null;
    }
    return "/" + String.join("/", path);
  }

  private String attributeValue(Element element, String attributeName) {
    if (element.hasAttribute(attributeName)) {
      return element.getAttribute(attributeName);
    }
    NamedNodeMap attributes = element.getAttributes();
    for (int index = 0; index < attributes.getLength(); index += 1) {
      Node attribute = attributes.item(index);
      if (attributeName.equalsIgnoreCase(attribute.getNodeName())) {
        return attribute.getNodeValue();
      }
    }
    return "";
  }

  private record FolderNode(
      String name, Map<String, FolderNode> children, List<FeedSourceResponse> sources) {

    private FolderNode(String name) {
      this(name, new LinkedHashMap<>(), new ArrayList<>());
    }
  }
}
