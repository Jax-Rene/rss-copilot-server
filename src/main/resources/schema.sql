CREATE TABLE IF NOT EXISTS user_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_preference (
    user_id INTEGER PRIMARY KEY,
    theme_mode TEXT NOT NULL,
    default_language TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_prompt_config (
    user_id INTEGER PRIMARY KEY,
    provider TEXT NOT NULL,
    api_key TEXT,
    filter_prompt TEXT NOT NULL,
    summary_prompt TEXT NOT NULL,
    translation_prompt TEXT NOT NULL,
    auto_summary_enabled INTEGER NOT NULL,
    auto_translation_enabled INTEGER NOT NULL,
    output_language TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS feed_source (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    rss_url TEXT NOT NULL,
    site_url TEXT,
    icon_url TEXT,
    enabled INTEGER NOT NULL,
    status TEXT NOT NULL,
    etag TEXT,
    last_modified TEXT,
    last_fetched_at TEXT,
    last_error_at TEXT,
    last_error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, rss_url),
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS feed_entry (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    source_id INTEGER NOT NULL,
    external_id TEXT NOT NULL,
    title TEXT NOT NULL,
    author TEXT,
    link TEXT NOT NULL,
    published_at TEXT NOT NULL,
    language TEXT,
    foreign_language INTEGER NOT NULL,
    cover_image_url TEXT,
    rss_summary TEXT,
    content_html TEXT,
    content_text TEXT,
    content_fetched INTEGER NOT NULL,
    filter_status TEXT NOT NULL,
    filter_is_noise INTEGER NOT NULL,
    filter_reason TEXT,
    summary_status TEXT NOT NULL,
    summary_text TEXT,
    translation_status TEXT NOT NULL,
    translation_language TEXT,
    translation_segments_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, source_id, external_id),
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
    FOREIGN KEY (source_id) REFERENCES feed_source(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_entry_state (
    user_id INTEGER NOT NULL,
    entry_id INTEGER NOT NULL,
    is_read INTEGER NOT NULL,
    read_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (user_id, entry_id),
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
    FOREIGN KEY (entry_id) REFERENCES feed_entry(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_result_filter (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    entry_id INTEGER NOT NULL UNIQUE,
    model TEXT,
    status TEXT NOT NULL,
    is_noise INTEGER,
    reason TEXT,
    raw_response TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
    FOREIGN KEY (entry_id) REFERENCES feed_entry(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_result_summary (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    entry_id INTEGER NOT NULL UNIQUE,
    model TEXT,
    status TEXT NOT NULL,
    summary_text TEXT,
    raw_response TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
    FOREIGN KEY (entry_id) REFERENCES feed_entry(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_result_translation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    entry_id INTEGER NOT NULL UNIQUE,
    model TEXT,
    status TEXT NOT NULL,
    target_language TEXT,
    translation_segments_json TEXT,
    raw_response TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
    FOREIGN KEY (entry_id) REFERENCES feed_entry(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sync_tombstone (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id INTEGER NOT NULL,
    deleted_at TEXT NOT NULL,
    UNIQUE (user_id, entity_type, entity_id),
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_feed_source_user_enabled ON feed_source(user_id, enabled);
CREATE INDEX IF NOT EXISTS idx_feed_entry_user_published_at ON feed_entry(user_id, published_at DESC);
CREATE INDEX IF NOT EXISTS idx_feed_entry_user_noise ON feed_entry(user_id, filter_is_noise, published_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_entry_state_user_updated_at ON user_entry_state(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sync_tombstone_user_deleted_at ON sync_tombstone(user_id, deleted_at DESC);

