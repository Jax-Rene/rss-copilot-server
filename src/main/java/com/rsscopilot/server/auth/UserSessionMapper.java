package com.rsscopilot.server.auth;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserSessionMapper {

  @Insert(
      """
        INSERT INTO user_session(user_id, token_hash, expires_at, last_seen_at, created_at)
        VALUES(#{userId}, #{tokenHash}, #{expiresAt}, #{lastSeenAt}, #{createdAt})
        """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(UserSession userSession);

  @Select(
      """
        SELECT id, user_id, token_hash, expires_at, last_seen_at, created_at
        FROM user_session
        WHERE token_hash = #{tokenHash}
        """)
  UserSession findByTokenHash(String tokenHash);

  @Delete(
      """
        DELETE FROM user_session
        WHERE token_hash = #{tokenHash}
        """)
  int deleteByTokenHash(String tokenHash);

  @Update(
      """
        UPDATE user_session
        SET last_seen_at = #{lastSeenAt}, expires_at = #{expiresAt}
        WHERE id = #{id}
        """)
  void updateActivity(UserSession userSession);
}
