package com.rsscopilot.server.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsscopilot.server.setting.AiPromptConfig;
import org.junit.jupiter.api.Test;

class AiProcessingServiceUnitTest {

  @Test
  void shouldKeepAiFailureMessageWhenExceptionMessageExists() {
    assertThat(AiProcessingService.aiFailureMessage("filter", new RuntimeException("rate limited")))
        .isEqualTo("rate limited");
  }

  @Test
  void shouldRedactSecretsFromAiFailureMessage() {
    String message =
        AiProcessingService.aiFailureMessage(
            "summary",
            new RuntimeException(
                "upstream rejected https://ai-user:ai-pass@api.deepseek.example/chat api_key=abc123 token=raw-token Authorization: Basic header-basic X-API-Key: model-key secret: model-secret Bearer abc.def Basic YmFzaWMtc2VjcmV0 Cookie: session=raw-session; theme=dark\nSet-Cookie: refresh=raw-refresh; Path=/ sk-abc123456789"));

    assertThat(message)
        .isEqualTo(
            "upstream rejected https://redacted@api.deepseek.example/chat [redacted] [redacted] Authorization: Basic [redacted] [redacted] [redacted] Bearer [redacted] Basic [redacted] Cookie: [redacted]\nSet-Cookie: [redacted]");
    assertThat(message)
        .doesNotContain("ai-user", "ai-pass", "api_key", "abc123", "token", "raw-token", "sk-");
    assertThat(message).doesNotContain("YmFzaWMtc2VjcmV0", "raw-session", "raw-refresh");
    assertThat(message).doesNotContain("header-basic", "model-key", "model-secret");
  }

  @Test
  void shouldFallbackAiFailureMessageWhenExceptionMessageIsBlank() {
    assertThat(AiProcessingService.aiFailureMessage("filter", new RuntimeException(" ")))
        .isEqualTo("ai filter failed: RuntimeException");
  }

  @Test
  void shouldFallbackAiFailureMessageWhenExceptionMessageIsNull() {
    assertThat(AiProcessingService.aiFailureMessage("filter", new RuntimeException()))
        .isEqualTo("ai filter failed: RuntimeException");
  }

  @Test
  void shouldAppendTargetLanguageToTranslationPrompt() {
    AiPromptConfig config = new AiPromptConfig();
    config.setTranslationPrompt("Translate paragraphs as a JSON array.");
    config.setOutputLanguage("en-US");

    assertThat(AiProcessingService.translationSystemPrompt(config))
        .contains("Translate paragraphs as a JSON array.")
        .contains("Target language: en-US.")
        .contains("Translate every translation field into the target language above.")
        .contains("Keep the response format required by the prompt.");
  }

  @Test
  void shouldKeepTranslationPromptWhenTargetLanguageIsBlank() {
    AiPromptConfig config = new AiPromptConfig();
    config.setTranslationPrompt("Translate paragraphs as a JSON array.");
    config.setOutputLanguage(" ");

    assertThat(AiProcessingService.translationSystemPrompt(config))
        .isEqualTo("Translate paragraphs as a JSON array.");
  }
}
