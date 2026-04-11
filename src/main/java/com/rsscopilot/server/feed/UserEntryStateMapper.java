package com.rsscopilot.server.feed;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
        INSERT INTO user_entry_state(user_id, entry_id, is_read, read_at, updated_at)
        VALUES(#{userId}, #{entryId}, 1, #{readAt}, #{updatedAt})
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_read = 1,
            read_at = excluded.read_at,
            updated_at = excluded.updated_at
        """)
  void markRead(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("readAt") String readAt,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        INSERT INTO user_entry_state(user_id, entry_id, is_read, read_at, updated_at)
        VALUES(#{userId}, #{entryId}, 0, NULL, #{updatedAt})
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_read = 0,
            read_at = NULL,
            updated_at = excluded.updated_at
        """)
  void markUnread(
      @Param("userId") long userId,
      @Param("entryId") long entryId,
      @Param("updatedAt") String updatedAt);

  @Insert(
      """
        <script>
        INSERT INTO user_entry_state(user_id, entry_id, is_read, read_at, updated_at)
        SELECT #{userId}, e.id, 1, #{readAt}, #{updatedAt}
        FROM feed_entry e
        LEFT JOIN user_entry_state st ON st.entry_id = e.id AND st.user_id = #{userId}
        WHERE e.user_id = #{userId}
          AND COALESCE(st.is_read, 0) = 0
        <choose>
          <when test='view != null and view.equals("feed")'>
            AND e.filter_is_noise = 0
          </when>
          <when test='view != null and view.equals("noise")'>
            AND e.filter_is_noise = 1
          </when>
        </choose>
        ON CONFLICT(user_id, entry_id) DO UPDATE SET
            is_read = 1,
            read_at = excluded.read_at,
            updated_at = excluded.updated_at
        </script>
        """)
  int markAllRead(
      @Param("userId") long userId,
      @Param("view") String view,
      @Param("readAt") String readAt,
      @Param("updatedAt") String updatedAt);
}
