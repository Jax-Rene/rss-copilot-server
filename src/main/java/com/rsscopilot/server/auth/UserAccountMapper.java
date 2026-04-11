package com.rsscopilot.server.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper {

  @Select(
      """
        SELECT id, email, password_hash, display_name, status, created_at, updated_at
        FROM user_account
        WHERE email = #{email}
        """)
  UserAccount findByEmail(String email);

  @Select(
      """
        SELECT id, email, password_hash, display_name, status, created_at, updated_at
        FROM user_account
        WHERE id = #{id}
        """)
  UserAccount findById(long id);

  @Insert(
      """
        INSERT INTO user_account(email, password_hash, display_name, status, created_at, updated_at)
        VALUES(#{email}, #{passwordHash}, #{displayName}, #{status}, #{createdAt}, #{updatedAt})
        """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(UserAccount userAccount);
}
