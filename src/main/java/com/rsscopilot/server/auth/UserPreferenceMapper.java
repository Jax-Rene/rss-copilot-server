package com.rsscopilot.server.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserPreferenceMapper {

  @Select(
      """
        SELECT user_id, theme_mode, default_language, created_at, updated_at
        FROM user_preference
        WHERE user_id = #{userId}
        """)
  UserPreference findByUserId(long userId);

  @Insert(
      """
        INSERT INTO user_preference(user_id, theme_mode, default_language, created_at, updated_at)
        VALUES(#{userId}, #{themeMode}, #{defaultLanguage}, #{createdAt}, #{updatedAt})
        """)
  void insert(UserPreference userPreference);

  @Update(
      """
        UPDATE user_preference
        SET theme_mode = #{themeMode},
            default_language = #{defaultLanguage},
            updated_at = #{updatedAt}
        WHERE user_id = #{userId}
        """)
  void update(UserPreference userPreference);
}
