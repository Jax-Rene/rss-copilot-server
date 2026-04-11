package com.rsscopilot.server.feed;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiResultTranslationMapper {

  @Insert(
      """
        INSERT INTO ai_result_translation(user_id, entry_id, model, status, target_language, translation_segments_json,
                                          raw_response, created_at, updated_at)
        VALUES(#{userId}, #{entryId}, #{model}, #{status}, #{targetLanguage}, #{translationSegmentsJson},
               #{rawResponse}, #{now}, #{now})
        ON CONFLICT(entry_id) DO UPDATE SET
            model = excluded.model,
            status = excluded.status,
            target_language = excluded.target_language,
            translation_segments_json = excluded.translation_segments_json,
            raw_response = excluded.raw_response,
            updated_at = excluded.updated_at
        """)
  void upsert(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("model") String model,
      @Param("status") String status,
      @Param("targetLanguage") String targetLanguage,
      @Param("translationSegmentsJson") String translationSegmentsJson,
      @Param("rawResponse") String rawResponse,
      @Param("now") String now);
}
