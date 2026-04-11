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
public interface FeedSourceMapper {

  @Insert(
      """
        INSERT INTO feed_source(
            user_id, name, rss_url, site_url, icon_url, enabled, status, etag, last_modified,
            last_fetched_at, last_error_at, last_error_message, created_at, updated_at
        )
        VALUES(
            #{userId}, #{name}, #{rssUrl}, #{siteUrl}, #{iconUrl}, #{enabled}, #{status}, #{etag}, #{lastModified},
            #{lastFetchedAt}, #{lastErrorAt}, #{lastErrorMessage}, #{createdAt}, #{updatedAt}
        )
        """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(FeedSource feedSource);

  @Select(
      """
        SELECT id, user_id, name, rss_url, site_url, icon_url, enabled, status, etag, last_modified,
               last_fetched_at, last_error_at, last_error_message, created_at, updated_at
        FROM feed_source
        WHERE id = #{id} AND user_id = #{userId}
        """)
  FeedSource findByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

  @Select(
      """
        SELECT id, user_id, name, rss_url, site_url, icon_url, enabled, status, etag, last_modified,
               last_fetched_at, last_error_at, last_error_message, created_at, updated_at
        FROM feed_source
        WHERE rss_url = #{rssUrl} AND user_id = #{userId}
        """)
  FeedSource findByUserIdAndRssUrl(@Param("userId") long userId, @Param("rssUrl") String rssUrl);

  @Select(
      """
        SELECT id, user_id, name, rss_url, site_url, icon_url, enabled, status, etag, last_modified,
               last_fetched_at, last_error_at, last_error_message, created_at, updated_at
        FROM feed_source
        WHERE user_id = #{userId} AND enabled = 1
        ORDER BY id ASC
        """)
  List<FeedSource> listEnabledByUserId(long userId);

  @Select(
      """
        SELECT id, user_id, name, rss_url, site_url, icon_url, enabled, status, etag, last_modified,
               last_fetched_at, last_error_at, last_error_message, created_at, updated_at
        FROM feed_source
        WHERE enabled = 1
        ORDER BY user_id ASC, id ASC
        """)
  List<FeedSource> listAllEnabled();

  @Select(
      """
        SELECT s.id,
               s.name,
               s.rss_url,
               s.site_url,
               s.icon_url,
               s.enabled,
               s.last_fetched_at,
               CASE WHEN s.status = 'ERROR' THEN 1 ELSE 0 END AS has_error,
               COALESCE((
                   SELECT COUNT(1)
                   FROM feed_entry e
                   LEFT JOIN user_entry_state st
                     ON st.entry_id = e.id AND st.user_id = s.user_id
                   WHERE e.source_id = s.id
                     AND e.filter_is_noise = 0
                     AND COALESCE(st.is_read, 0) = 0
               ), 0) AS unread_count
        FROM feed_source s
        WHERE s.user_id = #{userId}
        ORDER BY s.name COLLATE NOCASE ASC
        """)
  List<FeedSourceSummary> listSummariesByUserId(long userId);

  @Select(
      """
        SELECT s.id,
               s.name,
               s.rss_url,
               s.site_url,
               s.icon_url,
               s.enabled,
               s.last_fetched_at,
               CASE WHEN s.status = 'ERROR' THEN 1 ELSE 0 END AS has_error,
               COALESCE((
                   SELECT COUNT(1)
                   FROM feed_entry e
                   LEFT JOIN user_entry_state st
                     ON st.entry_id = e.id AND st.user_id = s.user_id
                   WHERE e.source_id = s.id
                     AND e.filter_is_noise = 0
                     AND COALESCE(st.is_read, 0) = 0
               ), 0) AS unread_count
        FROM feed_source s
        WHERE s.user_id = #{userId}
          AND s.updated_at > #{since}
        ORDER BY s.updated_at ASC, s.id ASC
        """)
  List<FeedSourceSummary> listSummariesUpdatedSince(
      @Param("userId") long userId, @Param("since") String since);

  @Update(
      """
        UPDATE feed_source
        SET name = #{name},
            rss_url = #{rssUrl},
            site_url = #{siteUrl},
            icon_url = #{iconUrl},
            enabled = #{enabled},
            updated_at = #{updatedAt}
        WHERE id = #{id} AND user_id = #{userId}
        """)
  int updateEditableFields(FeedSource feedSource);

  @Update(
      """
        UPDATE feed_source
        SET name = #{name},
            site_url = #{siteUrl},
            icon_url = #{iconUrl},
            status = #{status},
            etag = #{etag},
            last_modified = #{lastModified},
            last_fetched_at = #{lastFetchedAt},
            last_error_at = #{lastErrorAt},
            last_error_message = #{lastErrorMessage},
            updated_at = #{updatedAt}
        WHERE id = #{id}
        """)
  void updateAfterRefresh(FeedSource feedSource);

  @Delete(
      """
        DELETE FROM feed_source
        WHERE id = #{id} AND user_id = #{userId}
        """)
  int deleteByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
