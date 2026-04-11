package com.rsscopilot.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsscopilot.server.common.AppException;
import com.rsscopilot.server.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class DeepSeekAiProvider implements AiProvider {

  private static final double TEMPERATURE = 0.2d;

  private final ObjectMapper objectMapper;
  private final AppProperties appProperties;

  public DeepSeekAiProvider(ObjectMapper objectMapper, AppProperties appProperties) {
    this.objectMapper = objectMapper;
    this.appProperties = appProperties;
  }

  @Override
  public AiGenerationResult generate(String apiKey, String systemPrompt, String userPrompt) {
    try {
      ChatResponse response = createChatModel(apiKey).call(buildPrompt(systemPrompt, userPrompt));
      String content = extractContent(response);
      if (!StringUtils.hasText(content)) {
        throw new AppException(
            "AI_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY, "deepseek returned empty content");
      }
      return new AiGenerationResult(
          resolveModel(response), content, serializeRawResponse(response));
    } catch (AppException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new AppException(
          "AI_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY, "deepseek request failed", exception);
    }
  }

  private DeepSeekChatModel createChatModel(String apiKey) {
    DeepSeekApi deepSeekApi =
        DeepSeekApi.builder()
            .baseUrl(appProperties.getAi().getDeepSeek().getBaseUrl())
            .apiKey(apiKey)
            .restClientBuilder(buildRestClientBuilder())
            .build();
    DeepSeekChatOptions chatOptions =
        DeepSeekChatOptions.builder()
            .model(appProperties.getAi().getDeepSeek().getModel())
            .temperature(TEMPERATURE)
            .build();
    return DeepSeekChatModel.builder().deepSeekApi(deepSeekApi).defaultOptions(chatOptions).build();
  }

  private RestClient.Builder buildRestClientBuilder() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(
        Duration.ofSeconds(appProperties.getAi().getDeepSeek().getConnectTimeoutSeconds()));
    requestFactory.setReadTimeout(
        Duration.ofSeconds(appProperties.getAi().getDeepSeek().getReadTimeoutSeconds()));
    return RestClient.builder().requestFactory(requestFactory);
  }

  private Prompt buildPrompt(String systemPrompt, String userPrompt) {
    List<Message> messages = List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt));
    return new Prompt(messages);
  }

  private String extractContent(ChatResponse response) {
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      return null;
    }
    return response.getResult().getOutput().getText();
  }

  private String resolveModel(ChatResponse response) {
    if (response != null
        && response.getMetadata() != null
        && StringUtils.hasText(response.getMetadata().getModel())) {
      return response.getMetadata().getModel();
    }
    return appProperties.getAi().getDeepSeek().getModel();
  }

  private String serializeRawResponse(ChatResponse response) throws Exception {
    return objectMapper.copy().findAndRegisterModules().writeValueAsString(response);
  }
}
