package com.rsscopilot.server.feed;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiResultSummaryMapper {

  @Insert(
      """
        INSERT INTO ai_result_summary(user_id, entry_id, model, status, summary_text, raw_response, created_at, updated_at)
        VALUES(#{userId}, #{entryId}, #{model}, #{status}, #{summaryText}, #{rawResponse}, #{now}, #{now})
        ON CONFLICT(entry_id) DO UPDATE SET
            model = excluded.model,
            status = excluded.status,
            summary_text = excluded.summary_text,
            raw_response = excluded.raw_response,
            updated_at = excluded.updated_at
        """)
  void upsert(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("model") String model,
      @Param("status") String status,
      @Param("summaryText") String summaryText,
      @Param("rawResponse") String rawResponse,
      @Param("now") String now);
}
