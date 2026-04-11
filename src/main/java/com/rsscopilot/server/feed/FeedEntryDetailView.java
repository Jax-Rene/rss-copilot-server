package com.rsscopilot.server.feed;

public class FeedEntryDetailView {

  private long id;
  private long sourceId;
  private String sourceName;
  private String title;
  private String link;
  private String publishedAt;
  private String summary;
  private boolean read;
  private boolean foreignLanguage;
  private String contentHtml;
  private String filterReason;
  private String translationSegmentsJson;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public long getSourceId() {
    return sourceId;
  }

  public void setSourceId(long sourceId) {
    this.sourceId = sourceId;
  }

  public String getSourceName() {
    return sourceName;
  }

  public void setSourceName(String sourceName) {
    this.sourceName = sourceName;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getLink() {
    return link;
  }

  public void setLink(String link) {
    this.link = link;
  }

  public String getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(String publishedAt) {
    this.publishedAt = publishedAt;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public boolean isRead() {
    return read;
  }

  public void setRead(boolean read) {
    this.read = read;
  }

  public boolean isForeignLanguage() {
    return foreignLanguage;
  }

  public void setForeignLanguage(boolean foreignLanguage) {
    this.foreignLanguage = foreignLanguage;
  }

  public String getContentHtml() {
    return contentHtml;
  }

  public void setContentHtml(String contentHtml) {
    this.contentHtml = contentHtml;
  }

  public String getFilterReason() {
    return filterReason;
  }

  public void setFilterReason(String filterReason) {
    this.filterReason = filterReason;
  }

  public String getTranslationSegmentsJson() {
    return translationSegmentsJson;
  }

  public void setTranslationSegmentsJson(String translationSegmentsJson) {
    this.translationSegmentsJson = translationSegmentsJson;
  }
}
