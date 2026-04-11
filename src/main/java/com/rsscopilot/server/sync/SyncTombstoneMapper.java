package com.rsscopilot.server.sync;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SyncTombstoneMapper {

  @Insert(
      """
        INSERT INTO sync_tombstone(user_id, entity_type, entity_id, deleted_at)
        VALUES(#{userId}, #{entityType}, #{entityId}, #{deletedAt})
        ON CONFLICT(user_id, entity_type, entity_id) DO UPDATE SET
            deleted_at = excluded.deleted_at
        """)
  void upsert(
      @Param("userId") long userId,
      @Param("entityType") String entityType,
      @Param("entityId") long entityId,
      @Param("deletedAt") String deletedAt);

  @Select(
      """
        SELECT entity_id
        FROM sync_tombstone
        WHERE user_id = #{userId}
          AND entity_type = #{entityType}
          AND deleted_at > #{since}
        ORDER BY deleted_at ASC, entity_id ASC
        """)
  List<Long> listDeletedIdsSince(
      @Param("userId") long userId,
      @Param("entityType") String entityType,
      @Param("since") String since);
}
