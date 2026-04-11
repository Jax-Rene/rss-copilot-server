package com.rsscopilot.server.feed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsscopilot.server.ai.AiGenerationResult;
import com.rsscopilot.server.ai.AiProvider;
import com.rsscopilot.server.common.InstantMapper;
import com.rsscopilot.server.setting.AiPromptConfig;
import com.rsscopilot.server.setting.SettingsService;
import java.time.Instant;
import java.util.List;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AiProcessingService {

  private final FeedEntryMapper feedEntryMapper;
  private final AiResultFilterMapper aiResultFilterMapper;
  private final AiResultSummaryMapper aiResultSummaryMapper;
  private final AiResultTranslationMapper aiResultTranslationMapper;
  private final SettingsService settingsService;
  private final AiProvider aiProvider;
  private final TaskExecutor aiProcessingExecutor;
  private final ObjectMapper objectMapper;

  public AiProcessingService(
      FeedEntryMapper feedEntryMapper,
      AiResultFilterMapper aiResultFilterMapper,
      AiResultSummaryMapper aiResultSummaryMapper,
      AiResultTranslationMapper aiResultTranslationMapper,
      SettingsService settingsService,
      AiProvider aiProvider,
      TaskExecutor aiProcessingExecutor,
      ObjectMapper objectMapper) {
    this.feedEntryMapper = feedEntryMapper;
    this.aiResultFilterMapper = aiResultFilterMapper;
    this.aiResultSummaryMapper = aiResultSummaryMapper;
    this.aiResultTranslationMapper = aiResultTranslationMapper;
    this.settingsService = settingsService;
    this.aiProvider = aiProvider;
    this.aiProcessingExecutor = aiProcessingExecutor;
    this.objectMapper = objectMapper;
  }

  public void enqueue(long userId, long entryId) {
    aiProcessingExecutor.execute(() -> processEntry(userId, entryId));
  }

  @Transactional
  public void processEntry(long userId, long entryId) {
    FeedEntry feedEntry = feedEntryMapper.findByIdAndUserId(entryId, userId);
    if (feedEntry == null) {
      return;
    }
    AiPromptConfig aiPromptConfig = settingsService.getAiPromptConfig(userId);
    if (!StringUtils.hasText(aiPromptConfig.getApiKey())) {
      markSkipped(userId, entryId, aiPromptConfig);
      return;
    }
    processFilter(feedEntry, aiPromptConfig);
    processSummary(feedEntry, aiPromptConfig);
    processTranslation(feedEntry, aiPromptConfig);
  }

  private void processFilter(FeedEntry feedEntry, AiPromptConfig aiPromptConfig) {
    Instant now = Instant.now();
    try {
      AiGenerationResult result =
          aiProvider.generate(
              aiPromptConfig.getApiKey(),
              aiPromptConfig.getFilterPrompt(),
              buildUserPrompt(feedEntry));
      JsonNode node = objectMapper.readTree(result.content());
      boolean isNoise = node.path("isNoise").asBoolean(false);
      String reason = node.path("reason").asText(null);
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateFilterProjection(
          feedEntry.getId(), feedEntry.getUserId(), "SUCCESS", isNoise, reason, nowText);
      aiResultFilterMapper.upsert(
          feedEntry.getUserId(),
          feedEntry.getId(),
          result.model(),
          "SUCCESS",
          isNoise,
          reason,
          result.rawResponse(),
          nowText);
    } catch (Exception exception) {
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateFilterProjection(
          feedEntry.getId(),
          feedEntry.getUserId(),
          "FAILED",
          false,
          exception.getMessage(),
          nowText);
      aiResultFilterMapper.upsert(
          feedEntry.getUserId(),
          feedEntry.getId(),
          null,
          "FAILED",
          false,
          exception.getMessage(),
          null,
          nowText);
    }
  }

  private void processSummary(FeedEntry feedEntry, AiPromptConfig aiPromptConfig) {
    Instant now = Instant.now();
    if (!aiPromptConfig.isAutoSummaryEnabled()) {
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateSummaryProjection(
          feedEntry.getId(), feedEntry.getUserId(), "SKIPPED", null, nowText);
      aiResultSummaryMapper.upsert(
          feedEntry.getUserId(), feedEntry.getId(), null, "SKIPPED", null, null, nowText);
      return;
    }
    try {
      AiGenerationResult result =
          aiProvider.generate(
              aiPromptConfig.getApiKey(),
              aiPromptConfig.getSummaryPrompt(),
              buildUserPrompt(feedEntry));
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateSummaryProjection(
          feedEntry.getId(), feedEntry.getUserId(), "SUCCESS", result.content(), nowText);
      aiResultSummaryMapper.upsert(
          feedEntry.getUserId(),
          feedEntry.getId(),
          result.model(),
          "SUCCESS",
          result.content(),
          result.rawResponse(),
          nowText);
    } catch (Exception exception) {
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateSummaryProjection(
          feedEntry.getId(), feedEntry.getUserId(), "FAILED", null, nowText);
      aiResultSummaryMapper.upsert(
          feedEntry.getUserId(), feedEntry.getId(), null, "FAILED", null, null, nowText);
    }
  }

  private void processTranslation(FeedEntry feedEntry, AiPromptConfig aiPromptConfig) {
    Instant now = Instant.now();
    if (!aiPromptConfig.isAutoTranslationEnabled() || !feedEntry.isForeignLanguage()) {
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateTranslationProjection(
          feedEntry.getId(),
          feedEntry.getUserId(),
          "SKIPPED",
          aiPromptConfig.getOutputLanguage(),
          null,
          nowText);
      aiResultTranslationMapper.upsert(
          feedEntry.getUserId(),
          feedEntry.getId(),
          null,
          "SKIPPED",
          aiPromptConfig.getOutputLanguage(),
          null,
          null,
          nowText);
      return;
    }
    try {
      AiGenerationResult result =
          aiProvider.generate(
              aiPromptConfig.getApiKey(),
              aiPromptConfig.getTranslationPrompt(),
              buildUserPrompt(feedEntry));
      List<TranslationSegment> translationSegments =
          objectMapper.readValue(
              result.content(), new TypeReference<List<TranslationSegment>>() {});
      String translationSegmentsJson = objectMapper.writeValueAsString(translationSegments);
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateTranslationProjection(
          feedEntry.getId(),
          feedEntry.getUserId(),
          "SUCCESS",
          aiPromptConfig.getOutputLanguage(),
          translationSegmentsJson,
          nowText);
      aiResultTranslationMapper.upsert(
          feedEntry.getUserId(),
          feedEntry.getId(),
          result.model(),
          "SUCCESS",
          aiPromptConfig.getOutputLanguage(),
          translationSegmentsJson,
          result.rawResponse(),
          nowText);
    } catch (Exception exception) {
      String nowText = InstantMapper.toText(now);
      feedEntryMapper.updateTranslationProjection(
          feedEntry.getId(),
          feedEntry.getUserId(),
          "FAILED",
          aiPromptConfig.getOutputLanguage(),
          null,
          nowText);
      aiResultTranslationMapper.upsert(
          feedEntry.getUserId(),
          feedEntry.getId(),
          null,
          "FAILED",
          aiPromptConfig.getOutputLanguage(),
          null,
          null,
          nowText);
    }
  }

  private void markSkipped(long userId, long entryId, AiPromptConfig aiPromptConfig) {
    String nowText = InstantMapper.toText(Instant.now());
    feedEntryMapper.updateFilterProjection(entryId, userId, "SKIPPED", false, null, nowText);
    feedEntryMapper.updateSummaryProjection(entryId, userId, "SKIPPED", null, nowText);
    feedEntryMapper.updateTranslationProjection(
        entryId, userId, "SKIPPED", aiPromptConfig.getOutputLanguage(), null, nowText);
    aiResultFilterMapper.upsert(userId, entryId, null, "SKIPPED", false, null, null, nowText);
    aiResultSummaryMapper.upsert(userId, entryId, null, "SKIPPED", null, null, nowText);
    aiResultTranslationMapper.upsert(
        userId, entryId, null, "SKIPPED", aiPromptConfig.getOutputLanguage(), null, null, nowText);
  }

  private String buildUserPrompt(FeedEntry feedEntry) {
    String content =
        StringUtils.hasText(feedEntry.getContentText())
            ? feedEntry.getContentText()
            : feedEntry.getRssSummary();
    return """
            标题: %s
            链接: %s
            正文:
            %s
            """
        .formatted(feedEntry.getTitle(), feedEntry.getLink(), content);
  }
}
