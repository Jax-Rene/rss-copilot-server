package com.rsscopilot.server.feed;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FeedEntryMapper {

  @Delete(
      """
        DELETE FROM user_entry_state
        WHERE user_id = #{userId}
          AND entry_id IN (
              SELECT id FROM feed_entry
              WHERE user_id = #{userId} AND source_id = #{sourceId}
          )
        """)
  int deleteUserStatesByUserIdAndSourceId(
      @Param("userId") long userId, @Param("sourceId") long sourceId);

  @Delete(
      """
        DELETE FROM ai_result_filter
        WHERE user_id = #{userId}
          AND entry_id IN (
              SELECT id FROM feed_entry
              WHERE user_id = #{userId} AND source_id = #{sourceId}
          )
        """)
  int deleteFilterResultsByUserIdAndSourceId(
      @Param("userId") long userId, @Param("sourceId") long sourceId);

  @Delete(
      """
        DELETE FROM ai_result_summary
        WHERE user_id = #{userId}
          AND entry_id IN (
              SELECT id FROM feed_entry
              WHERE user_id = #{userId} AND source_id = #{sourceId}
          )
        """)
  int deleteSummaryResultsByUserIdAndSourceId(
      @Param("userId") long userId, @Param("sourceId") long sourceId);

  @Delete(
      """
        DELETE FROM ai_result_translation
        WHERE user_id = #{userId}
          AND entry_id IN (
              SELECT id FROM feed_entry
              WHERE user_id = #{userId} AND source_id = #{sourceId}
          )
        """)
  int deleteTranslationResultsByUserIdAndSourceId(
      @Param("userId") long userId, @Param("sourceId") long sourceId);

  @Delete(
      """
        DELETE FROM feed_entry
        WHERE user_id = #{userId} AND source_id = #{sourceId}
        """)
  int deleteByUserIdAndSourceId(@Param("userId") long userId, @Param("sourceId") long sourceId);

  @Insert(
      """
        INSERT INTO feed_entry(
            user_id, source_id, external_id, title, author, link, published_at, language, foreign_language,
            cover_image_url, rss_summary, content_html, content_text, content_fetched,
            filter_status, filter_is_noise, filter_reason, summary_status, summary_text,
            translation_status, translation_language, translation_segments_json, created_at, updated_at
        )
        VALUES(
            #{userId}, #{sourceId}, #{externalId}, #{title}, #{author}, #{link}, #{publishedAt}, #{language}, #{foreignLanguage},
            #{coverImageUrl}, #{rssSummary}, #{contentHtml}, #{contentText}, #{contentFetched},
            #{filterStatus}, #{filterIsNoise}, #{filterReason}, #{summaryStatus}, #{summaryText},
            #{translationStatus}, #{translationLanguage}, #{translationSegmentsJson}, #{createdAt}, #{updatedAt}
        )
        """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(FeedEntry feedEntry);

  @Update(
      """
        UPDATE feed_entry
        SET title = #{title},
            author = #{author},
            link = #{link},
            published_at = #{publishedAt},
            language = #{language},
            foreign_language = #{foreignLanguage},
            cover_image_url = #{coverImageUrl},
            rss_summary = #{rssSummary},
            content_html = #{contentHtml},
            content_text = #{contentText},
            content_fetched = #{contentFetched},
            updated_at = #{updatedAt}
        WHERE id = #{id} AND user_id = #{userId}
        """)
  int updateContentSnapshot(FeedEntry feedEntry);

  @Select(
      """
        SELECT id, user_id, source_id, external_id, title, author, link, published_at, language, foreign_language,
               cover_image_url, rss_summary, content_html, content_text, content_fetched,
               filter_status, filter_is_noise, filter_reason, summary_status, summary_text,
               translation_status, translation_language, translation_segments_json, created_at, updated_at
        FROM feed_entry
        WHERE user_id = #{userId} AND source_id = #{sourceId} AND external_id = #{externalId}
        """)
  FeedEntry findByExternalId(
      @Param("userId") long userId,
      @Param("sourceId") long sourceId,
      @Param("externalId") String externalId);

  @Select(
      """
        SELECT id, user_id, source_id, external_id, title, author, link, published_at, language, foreign_language,
               cover_image_url, rss_summary, content_html, content_text, content_fetched,
               filter_status, filter_is_noise, filter_reason, summary_status, summary_text,
               translation_status, translation_language, translation_segments_json, created_at, updated_at
        FROM feed_entry
        WHERE id = #{id} AND user_id = #{userId}
        """)
  FeedEntry findByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

  @Select(
      """
        <script>
        SELECT e.id,
               e.source_id,
               s.name AS source_name,
               s.icon_url AS source_icon_url,
               e.author,
               e.title,
               e.link,
               e.published_at,
               COALESCE(e.summary_text, e.rss_summary) AS summary,
               COALESCE(st.is_read, 0) AS read,
               COALESCE(st.is_saved, 0) AS saved,
               COALESCE(st.reading_progress, 0) AS reading_progress,
               e.filter_is_noise AS noise,
               e.foreign_language,
               e.filter_status,
               e.summary_status,
               e.translation_status,
               e.cover_image_url
        FROM feed_entry e
        JOIN feed_source s ON s.id = e.source_id
        LEFT JOIN user_entry_state st ON st.entry_id = e.id AND st.user_id = #{userId}
        WHERE e.user_id = #{userId}
        <if test='sourceId != null'>
          AND e.source_id = #{sourceId}
        </if>
        <if test='folder != null'>
          AND s.folder = #{folder}
        </if>
        <choose>
          <when test='view != null and view.equals("feed")'>
            AND e.filter_is_noise = 0
          </when>
          <when test='view != null and view.equals("noise")'>
            AND e.filter_is_noise = 1
          </when>
          <when test='view != null and view.equals("saved")'>
            AND COALESCE(st.is_saved, 0) = 1
          </when>
        </choose>
        <if test='unreadOnly'>
          AND COALESCE(st.is_read, 0) = 0
        </if>
        <if test='searchPatterns != null and searchPatterns.size() > 0'>
          <foreach collection='searchPatterns' item='searchPattern'>
            AND (
              e.title LIKE #{searchPattern} ESCAPE '!'
              OR s.name LIKE #{searchPattern} ESCAPE '!'
              OR e.author LIKE #{searchPattern} ESCAPE '!'
              OR e.link LIKE #{searchPattern} ESCAPE '!'
              OR e.rss_summary LIKE #{searchPattern} ESCAPE '!'
              OR e.content_text LIKE #{searchPattern} ESCAPE '!'
              OR e.summary_text LIKE #{searchPattern} ESCAPE '!'
              OR e.filter_reason LIKE #{searchPattern} ESCAPE '!'
              OR e.translation_segments_json LIKE #{searchPattern} ESCAPE '!'
            )
          </foreach>
        </if>
        <if test='beforePublishedAt != null and beforeId != null'>
          AND (
            julianday(e.published_at) &lt; julianday(#{beforePublishedAt})
            OR (julianday(e.published_at) = julianday(#{beforePublishedAt}) AND e.id &lt; #{beforeId})
          )
        </if>
        ORDER BY julianday(e.published_at) DESC, e.id DESC
        LIMIT #{limit}
        </script>
        """)
  List<FeedEntryListItem> listEntries(
      @Param("userId") long userId,
      @Param("view") String view,
      @Param("sourceId") Long sourceId,
      @Param("folder") String folder,
      @Param("unreadOnly") boolean unreadOnly,
      @Param("beforePublishedAt") String beforePublishedAt,
      @Param("beforeId") Long beforeId,
      @Param("searchPatterns") List<String> searchPatterns,
      @Param("limit") int limit);

  @Select(
      """
        SELECT e.id,
               e.source_id,
               s.name AS source_name,
               s.icon_url AS source_icon_url,
               e.author,
               e.title,
               e.link,
               e.published_at,
               COALESCE(e.summary_text, e.rss_summary) AS summary,
               COALESCE(st.is_read, 0) AS read,
               COALESCE(st.is_saved, 0) AS saved,
               COALESCE(st.reading_progress, 0) AS reading_progress,
               e.filter_is_noise AS noise,
               e.foreign_language,
               e.filter_status,
               e.summary_status,
               e.translation_status,
               e.cover_image_url,
               e.content_html,
               e.filter_reason,
               e.translation_segments_json
        FROM feed_entry e
        JOIN feed_source s ON s.id = e.source_id
        LEFT JOIN user_entry_state st ON st.entry_id = e.id AND st.user_id = #{userId}
        WHERE e.id = #{entryId} AND e.user_id = #{userId}
        """)
  FeedEntryDetailView findDetail(@Param("entryId") long entryId, @Param("userId") long userId);

  @Select(
      """
        <script>
        SELECT e.id,
               e.source_id,
               s.name AS source_name,
               s.icon_url AS source_icon_url,
               e.author,
               e.title,
               e.link,
               e.published_at,
               COALESCE(e.summary_text, e.rss_summary) AS summary,
               COALESCE(st.is_read, 0) AS read,
               COALESCE(st.is_saved, 0) AS saved,
               COALESCE(st.reading_progress, 0) AS reading_progress,
               e.filter_is_noise AS noise,
               e.foreign_language,
               e.filter_status,
               e.summary_status,
               e.translation_status,
               e.cover_image_url,
               e.content_html,
               e.filter_reason,
               e.translation_segments_json
        FROM feed_entry e
        JOIN feed_source s ON s.id = e.source_id
        LEFT JOIN user_entry_state st ON st.entry_id = e.id AND st.user_id = #{userId}
        WHERE e.user_id = #{userId}
        <if test='since != null'>
          AND MAX(julianday(e.updated_at), julianday(COALESCE(st.updated_at, e.updated_at)))
              > julianday(#{since})
        </if>
        <if test='until != null'>
          AND MAX(julianday(e.updated_at), julianday(COALESCE(st.updated_at, e.updated_at)))
              &lt;= julianday(#{until})
        </if>
        ORDER BY MAX(julianday(e.updated_at), julianday(COALESCE(st.updated_at, e.updated_at))) ASC,
                 e.id ASC
        </script>
        """)
  List<FeedEntryDetailView> listDetailsSince(
      @Param("userId") long userId, @Param("since") String since, @Param("until") String until);

  @Update(
      """
        UPDATE feed_entry
        SET filter_status = #{filterStatus},
            filter_is_noise = #{filterIsNoise},
            filter_reason = #{filterReason},
            updated_at = #{updatedAt}
        WHERE id = #{entryId} AND user_id = #{userId}
        """)
  void updateFilterProjection(
      @Param("entryId") long entryId,
      @Param("userId") long userId,
      @Param("filterStatus") String filterStatus,
      @Param("filterIsNoise") boolean filterIsNoise,
      @Param("filterReason") String filterReason,
      @Param("updatedAt") String updatedAt);

  @Update(
      """
        UPDATE feed_entry
        SET summary_status = #{summaryStatus},
            summary_text = #{summaryText},
            updated_at = #{updatedAt}
        WHERE id = #{entryId} AND user_id = #{userId}
        """)
  void updateSummaryProjection(
      @Param("entryId") long entryId,
      @Param("userId") long userId,
      @Param("summaryStatus") String summaryStatus,
      @Param("summaryText") String summaryText,
      @Param("updatedAt") String updatedAt);

  @Update(
      """
        UPDATE feed_entry
        SET translation_status = #{translationStatus},
            translation_language = #{translationLanguage},
            translation_segments_json = #{translationSegmentsJson},
            updated_at = #{updatedAt}
        WHERE id = #{entryId} AND user_id = #{userId}
        """)
  void updateTranslationProjection(
      @Param("entryId") long entryId,
      @Param("userId") long userId,
      @Param("translationStatus") String translationStatus,
      @Param("translationLanguage") String translationLanguage,
      @Param("translationSegmentsJson") String translationSegmentsJson,
      @Param("updatedAt") String updatedAt);

  @Update(
      """
        UPDATE feed_entry
        SET summary_status = 'PENDING',
            summary_text = NULL,
            translation_status = 'PENDING',
            translation_language = NULL,
            translation_segments_json = NULL,
            updated_at = #{updatedAt}
        WHERE id = #{entryId} AND user_id = #{userId}
        """)
  void markContentAiProcessingPending(
      @Param("entryId") long entryId,
      @Param("userId") long userId,
      @Param("updatedAt") String updatedAt);

  @Update(
      """
        UPDATE feed_entry
        SET filter_status = 'PENDING',
            summary_status = 'PENDING',
            translation_status = 'PENDING',
            updated_at = #{updatedAt}
        WHERE id = #{entryId} AND user_id = #{userId}
        """)
  void markAiProcessingPending(
      @Param("entryId") long entryId,
      @Param("userId") long userId,
      @Param("updatedAt") String updatedAt);
}
