package com.rsscopilot.server.feed;

public class FeedEntry {

  private Long id;
  private Long userId;
  private Long sourceId;
  private String externalId;
  private String title;
  private String author;
  private String link;
  private String publishedAt;
  private String language;
  private boolean foreignLanguage;
  private String coverImageUrl;
  private String rssSummary;
  private String contentHtml;
  private String contentText;
  private boolean contentFetched;
  private String filterStatus;
  private boolean filterIsNoise;
  private String filterReason;
  private String summaryStatus;
  private String summaryText;
  private String translationStatus;
  private String translationLanguage;
  private String translationSegmentsJson;
  private String createdAt;
  private String updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getSourceId() {
    return sourceId;
  }

  public void setSourceId(Long sourceId) {
    this.sourceId = sourceId;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
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

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public boolean isForeignLanguage() {
    return foreignLanguage;
  }

  public void setForeignLanguage(boolean foreignLanguage) {
    this.foreignLanguage = foreignLanguage;
  }

  public String getCoverImageUrl() {
    return coverImageUrl;
  }

  public void setCoverImageUrl(String coverImageUrl) {
    this.coverImageUrl = coverImageUrl;
  }

  public String getRssSummary() {
    return rssSummary;
  }

  public void setRssSummary(String rssSummary) {
    this.rssSummary = rssSummary;
  }

  public String getContentHtml() {
    return contentHtml;
  }

  public void setContentHtml(String contentHtml) {
    this.contentHtml = contentHtml;
  }

  public String getContentText() {
    return contentText;
  }

  public void setContentText(String contentText) {
    this.contentText = contentText;
  }

  public boolean isContentFetched() {
    return contentFetched;
  }

  public void setContentFetched(boolean contentFetched) {
    this.contentFetched = contentFetched;
  }

  public String getFilterStatus() {
    return filterStatus;
  }

  public void setFilterStatus(String filterStatus) {
    this.filterStatus = filterStatus;
  }

  public boolean isFilterIsNoise() {
    return filterIsNoise;
  }

  public void setFilterIsNoise(boolean filterIsNoise) {
    this.filterIsNoise = filterIsNoise;
  }

  public String getFilterReason() {
    return filterReason;
  }

  public void setFilterReason(String filterReason) {
    this.filterReason = filterReason;
  }

  public String getSummaryStatus() {
    return summaryStatus;
  }

  public void setSummaryStatus(String summaryStatus) {
    this.summaryStatus = summaryStatus;
  }

  public String getSummaryText() {
    return summaryText;
  }

  public void setSummaryText(String summaryText) {
    this.summaryText = summaryText;
  }

  public String getTranslationStatus() {
    return translationStatus;
  }

  public void setTranslationStatus(String translationStatus) {
    this.translationStatus = translationStatus;
  }

  public String getTranslationLanguage() {
    return translationLanguage;
  }

  public void setTranslationLanguage(String translationLanguage) {
    this.translationLanguage = translationLanguage;
  }

  public String getTranslationSegmentsJson() {
    return translationSegmentsJson;
  }

  public void setTranslationSegmentsJson(String translationSegmentsJson) {
    this.translationSegmentsJson = translationSegmentsJson;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
