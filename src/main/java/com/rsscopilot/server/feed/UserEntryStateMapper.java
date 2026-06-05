package com.rsscopilot.server.feed;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserEntryStateMapper {

  @Insert(
      """
        INSERT OR IGNORE INTO user_entry_state(user_id, entry_id, is_read, read_at, updated_at)
        VALUES(#{userId}, #{entryId}, 0, NULL, #{updatedAt})
        """)
  void insertUnread(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        INSERT INTO user_entry_state(
            user_id, entry_id, is_read, read_at, reading_progress, reading_progress_updated_at, updated_at
        )
        VALUES(#{userId}, #{entryId}, 1, #{readAt}, 1, #{updatedAt}, #{updatedAt})
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_read = 1,
            read_at = excluded.read_at,
            reading_progress = 1,
            reading_progress_updated_at = excluded.updated_at,
            updated_at = excluded.updated_at
        WHERE COALESCE(user_entry_state.is_read, 0) = 0
           OR COALESCE(user_entry_state.reading_progress, 0) < 1
        """)
  void markRead(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("readAt") String readAt,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        <script>
        INSERT INTO user_entry_state(
            user_id, entry_id, is_read, read_at, reading_progress, reading_progress_updated_at, updated_at
        )
        SELECT #{userId}, e.id, 1, #{readAt}, 1, #{updatedAt}, #{updatedAt}
        FROM feed_entry e
        LEFT JOIN user_entry_state st ON st.entry_id = e.id AND st.user_id = #{userId}
        WHERE e.user_id = #{userId}
          AND e.id IN
          <foreach collection='entryIds' item='entryId' open='(' separator=',' close=')'>
            #{entryId}
          </foreach>
          AND (COALESCE(st.is_read, 0) = 0 OR COALESCE(st.reading_progress, 0) &lt; 1)
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_read = 1,
            read_at = excluded.read_at,
            reading_progress = 1,
            reading_progress_updated_at = excluded.updated_at,
            updated_at = excluded.updated_at
        </script>
        """)
  int markEntriesRead(
      @Param("userId") long userId,
      @Param("entryIds") List<Long> entryIds,
      @Param("readAt") String readAt,
      @Param("updatedAt") String updatedAt);

  @Update(
      """
        UPDATE user_entry_state
        SET
            is_read = 0,
            read_at = NULL,
            reading_progress = 0,
            reading_progress_updated_at = #{updatedAt},
            updated_at = #{updatedAt}
        WHERE user_id = #{userId}
          AND entry_id = #{entryId}
          AND (
            COALESCE(is_read, 0) != 0
            OR COALESCE(reading_progress, 0) != 0
          )
        """)
  void markUnread(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        INSERT INTO user_entry_state(user_id, entry_id, is_read, read_at, is_saved, saved_at, updated_at)
        VALUES(#{userId}, #{entryId}, 0, NULL, 1, #{savedAt}, #{updatedAt})
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_saved = 1,
            saved_at = excluded.saved_at,
            updated_at = excluded.updated_at
        WHERE COALESCE(user_entry_state.is_saved, 0) = 0
        """)
  void markSaved(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("savedAt") String savedAt,
      @Param("updatedAt") String updatedAt);

  @Update(
      """
        UPDATE user_entry_state
        SET
            is_saved = 0,
            saved_at = NULL,
            updated_at = #{updatedAt}
        WHERE user_id = #{userId}
          AND entry_id = #{entryId}
          AND COALESCE(is_saved, 0) != 0
        """)
  void markUnsaved(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        <script>
        INSERT INTO user_entry_state(
            user_id, entry_id, is_read, read_at, reading_progress, reading_progress_updated_at, updated_at
        )
        SELECT #{userId}, e.id, 1, #{readAt}, 1, #{updatedAt}, #{updatedAt}
        FROM feed_entry e
        JOIN feed_source s ON s.id = e.source_id AND s.user_id = e.user_id
        LEFT JOIN user_entry_state st ON st.entry_id = e.id AND st.user_id = #{userId}
        WHERE e.user_id = #{userId}
          AND (COALESCE(st.is_read, 0) = 0 OR COALESCE(st.reading_progress, 0) &lt; 1)
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
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_read = 1,
            read_at = excluded.read_at,
            reading_progress = 1,
            reading_progress_updated_at = excluded.updated_at,
            updated_at = excluded.updated_at
        </script>
        """)
  int markAllRead(
      @Param("userId") long userId,
      @Param("view") String view,
      @Param("sourceId") Long sourceId,
      @Param("folder") String folder,
      @Param("readAt") String readAt,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        INSERT INTO user_entry_state(
            user_id, entry_id, is_read, read_at, reading_progress, reading_progress_updated_at, updated_at
        )
        VALUES(#{userId}, #{entryId}, 0, NULL, #{progress}, #{updatedAt}, #{updatedAt})
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            reading_progress = excluded.reading_progress,
            reading_progress_updated_at = excluded.reading_progress_updated_at,
            updated_at = excluded.updated_at
        """)
  void updateReadingProgress(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("progress") double progress,
      @Param("updatedAt") String updatedAt);
}
