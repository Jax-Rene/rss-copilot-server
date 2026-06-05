package com.rsscopilot.server.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrationInitializer implements ApplicationRunner {

  private final JdbcTemplate jdbcTemplate;

  public SchemaMigrationInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensureFeedSourceFolderColumn();
    ensureFeedSourceErrorColumns();
    ensureUserEntryStateSavedColumns();
    ensureUserEntryStateReadingProgressColumns();
  }

  private void ensureFeedSourceFolderColumn() {
    List<String> columns =
        jdbcTemplate.query("PRAGMA table_info(feed_source)", (rs, rowNum) -> rs.getString("name"));
    if (!columns.contains("folder")) {
      jdbcTemplate.execute("ALTER TABLE feed_source ADD COLUMN folder TEXT NOT NULL DEFAULT '未分组'");
    }
  }

  private void ensureFeedSourceErrorColumns() {
    List<String> columns =
        jdbcTemplate.query("PRAGMA table_info(feed_source)", (rs, rowNum) -> rs.getString("name"));
    if (!columns.contains("last_error_at")) {
      jdbcTemplate.execute("ALTER TABLE feed_source ADD COLUMN last_error_at TEXT");
    }
    if (!columns.contains("last_error_message")) {
      jdbcTemplate.execute("ALTER TABLE feed_source ADD COLUMN last_error_message TEXT");
    }
  }

  private void ensureUserEntryStateSavedColumns() {
    List<String> columns =
        jdbcTemplate.query(
            "PRAGMA table_info(user_entry_state)", (rs, rowNum) -> rs.getString("name"));
    if (!columns.contains("is_saved")) {
      jdbcTemplate.execute(
          "ALTER TABLE user_entry_state ADD COLUMN is_saved INTEGER NOT NULL DEFAULT 0");
    }
    if (!columns.contains("saved_at")) {
      jdbcTemplate.execute("ALTER TABLE user_entry_state ADD COLUMN saved_at TEXT");
    }
    jdbcTemplate.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_user_entry_state_user_saved_updated
        ON user_entry_state(user_id, is_saved, updated_at DESC)
        """);
  }

  private void ensureUserEntryStateReadingProgressColumns() {
    List<String> columns =
        jdbcTemplate.query(
            "PRAGMA table_info(user_entry_state)", (rs, rowNum) -> rs.getString("name"));
    if (!columns.contains("reading_progress")) {
      jdbcTemplate.execute(
          "ALTER TABLE user_entry_state ADD COLUMN reading_progress REAL NOT NULL DEFAULT 0");
    }
    if (!columns.contains("reading_progress_updated_at")) {
      jdbcTemplate.execute(
          "ALTER TABLE user_entry_state ADD COLUMN reading_progress_updated_at TEXT");
    }
  }
}
