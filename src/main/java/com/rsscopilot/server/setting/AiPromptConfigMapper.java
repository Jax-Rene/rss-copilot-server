package com.rsscopilot.server.setting;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiPromptConfigMapper {

  @Select(
      """
        SELECT user_id, provider, api_key, filter_prompt, summary_prompt, translation_prompt,
               auto_summary_enabled, auto_translation_enabled, output_language, created_at, updated_at
        FROM ai_prompt_config
        WHERE user_id = #{userId}
        """)
  AiPromptConfig findByUserId(long userId);

  @Insert(
      """
        INSERT INTO ai_prompt_config(
            user_id, provider, api_key, filter_prompt, summary_prompt, translation_prompt,
            auto_summary_enabled, auto_translation_enabled, output_language, created_at, updated_at
        )
        VALUES(
            #{userId}, #{provider}, #{apiKey}, #{filterPrompt}, #{summaryPrompt}, #{translationPrompt},
            #{autoSummaryEnabled}, #{autoTranslationEnabled}, #{outputLanguage}, #{createdAt}, #{updatedAt}
        )
        """)
  void insert(AiPromptConfig aiPromptConfig);

  @Update(
      """
        UPDATE ai_prompt_config
        SET provider = #{provider},
            api_key = #{apiKey},
            filter_prompt = #{filterPrompt},
            summary_prompt = #{summaryPrompt},
            translation_prompt = #{translationPrompt},
            auto_summary_enabled = #{autoSummaryEnabled},
            auto_translation_enabled = #{autoTranslationEnabled},
            output_language = #{outputLanguage},
            updated_at = #{updatedAt}
        WHERE user_id = #{userId}
        """)
  void update(AiPromptConfig aiPromptConfig);
}
