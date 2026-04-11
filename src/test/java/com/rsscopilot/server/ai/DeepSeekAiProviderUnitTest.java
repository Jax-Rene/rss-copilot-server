package com.rsscopilot.server.ai;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsscopilot.server.config.AppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class DeepSeekAiProviderUnitTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldGenerateContentViaDeepSeekChatCompletionsApi() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .addHeader("Content-Type", "application/json")
              .setBody(
                  """
                  {
                    "id": "chatcmpl-1",
                    "object": "chat.completion",
                    "created": 1710000000,
                    "model": "deepseek-chat",
                    "choices": [
                      {
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "{\\"isNoise\\":false,\\"reason\\":\\"保留\\"}"
                        },
                        "finish_reason": "stop"
                      }
                    ],
                    "usage": {
                      "prompt_tokens": 12,
                      "completion_tokens": 8,
                      "total_tokens": 20
                    }
                  }
                  """));
      server.start();

      AppProperties appProperties = new AppProperties();
      appProperties
          .getAi()
          .getDeepSeek()
          .setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
      appProperties.getAi().getDeepSeek().setModel("deepseek-chat");
      appProperties.getAi().getDeepSeek().setConnectTimeoutSeconds(5);
      appProperties.getAi().getDeepSeek().setReadTimeoutSeconds(5);

      DeepSeekAiProvider provider = new DeepSeekAiProvider(objectMapper, appProperties);

      AiGenerationResult result = provider.generate("sk-test", "你是一个筛选助手", "请判断这篇文章是否值得阅读");

      RecordedRequest request = server.takeRequest(5, SECONDS);
      assertNotNull(request);
      assertEquals("POST", request.getMethod());
      assertEquals("/chat/completions", request.getPath());
      assertEquals("Bearer sk-test", request.getHeader("Authorization"));

      JsonNode payload = objectMapper.readTree(request.getBody().readUtf8());
      assertEquals("deepseek-chat", payload.path("model").asText());
      assertEquals(0.2d, payload.path("temperature").asDouble());
      assertEquals(2, payload.path("messages").size());
      assertEquals("system", payload.path("messages").get(0).path("role").asText());
      assertEquals("你是一个筛选助手", payload.path("messages").get(0).path("content").asText());
      assertEquals("user", payload.path("messages").get(1).path("role").asText());
      assertEquals("请判断这篇文章是否值得阅读", payload.path("messages").get(1).path("content").asText());

      assertEquals("deepseek-chat", result.model());
      assertEquals("{\"isNoise\":false,\"reason\":\"保留\"}", result.content());
      assertNotNull(result.rawResponse());
      assertFalse(result.rawResponse().isBlank());
    }
  }
}
