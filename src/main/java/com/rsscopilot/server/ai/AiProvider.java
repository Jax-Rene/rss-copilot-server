package com.rsscopilot.server.ai;

public interface AiProvider {

  AiGenerationResult generate(String apiKey, String systemPrompt, String userPrompt);
}
