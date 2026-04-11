package com.rsscopilot.server.feed;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiResultFilterMapper {

  @Insert(
      """
        INSERT INTO ai_result_filter(user_id, entry_id, model, status, is_noise, reason, raw_response, created_at, updated_at)
        VALUES(#{userId}, #{entryId}, #{model}, #{status}, #{isNoise}, #{reason}, #{rawResponse}, #{now}, #{now})
        ON CONFLICT(entry_id) DO UPDATE SET
            model = excluded.model,
            status = excluded.status,
            is_noise = excluded.is_noise,
            reason = excluded.reason,
            raw_response = excluded.raw_response,
            updated_at = excluded.updated_at
        """)
  void upsert(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("model") String model,
      @Param("status") String status,
      @Param("isNoise") Boolean isNoise,
      @Param("reason") String reason,
      @Param("rawResponse") String rawResponse,
      @Param("now") String now);
}
