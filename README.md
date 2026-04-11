# RSS Copilot Server

RSS Copilot 的第一版服务端实现，技术栈为 `Java 17 + Spring Boot 3 + MyBatis + SQLite`。

当前版本已覆盖的核心能力：

- 邮箱密码登录，数据库预置用户，Bearer Token 会话
- RSS 订阅源增删改查、按小时自动刷新、手动刷新全部
- RSS 文章抓取、正文清洗、全文优先回退摘要
- AI 去噪、总结、翻译，基于 Spring AI 集成 DeepSeek
- Feed / 噪音箱 / 订阅源文章列表
- 已读 / 未读、批量标记已读
- 设置页后端能力：AI 配置、外观、Feeds、账号信息
- 多端同步接口：全量快照和增量变化

## 本地启动

```bash
make test
make build
make dev
```

默认启动端口：`8080`

默认 SQLite 文件：`./rss-copilot.db`

默认预置账号：

- Email: `demo@rsscopilot.local`
- Password: `changeme123`

建议通过环境变量覆盖：

```bash
export RSS_COPILOT_DEFAULT_USER_EMAIL=you@example.com
export RSS_COPILOT_DEFAULT_USER_PASSWORD=your-password
export RSS_COPILOT_DEFAULT_USER_DISPLAY_NAME="Your Name"
export RSS_COPILOT_DEFAULT_USER_API_KEY=sk-your-deepseek-key
```

## 关键环境变量

```bash
SERVER_PORT=8080
RSS_COPILOT_DB_PATH=./rss-copilot.db
RSS_COPILOT_SESSION_TTL_DAYS=30
RSS_COPILOT_REFRESH_CRON="0 0 * * * *"
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

## AI 集成说明

- 当前项目已接入 [Spring AI](https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html) 的 DeepSeek 模块。
- 由于 API Key 是按用户保存在 `ai_prompt_config` 表里，而不是全局单实例配置，所以这里采用的是“程序化创建 `DeepSeekChatModel`”的方式，而不是直接依赖全局 `spring.ai.deepseek.api-key`。
- 现有环境变量 `DEEPSEEK_BASE_URL` 和 `DEEPSEEK_MODEL` 仍然有效，用于控制默认模型和兼容 OpenAI 风格的 DeepSeek 接口地址。

## Makefile

- `make build`: 打包
- `make test`: 跑测试
- `make lint`: 运行 Spotless 检查
- `make dev` / `make run`: 本地启动
- `make deploy`: 构建 Docker 镜像

## Docker

```bash
docker build -t rss-copilot-server:latest .
docker run --rm -p 8080:8080 -v $(pwd)/data:/data rss-copilot-server:latest
```

## 主要接口

详细接口说明见：[docs/client-api.md](docs/client-api.md)

### Auth

- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`

### Feed Sources

- `GET /api/feed-sources`
- `POST /api/feed-sources`
- `PUT /api/feed-sources/{id}`
- `DELETE /api/feed-sources/{id}`
- `POST /api/feed-sources/refresh`
- `GET /api/feed-sources/{id}/entries`

### Entries

- `GET /api/entries?view=feed|noise&unreadOnly=true|false`
- `GET /api/entries/{id}?markRead=true|false`
- `POST /api/entries/{id}/read`
- `POST /api/entries/{id}/unread`
- `POST /api/entries/read-all?view=feed|noise|all`

### Settings

- `GET /api/settings`
- `PUT /api/settings/ai`

### Sync

- `GET /api/sync/bootstrap`
- `GET /api/sync/changes?since=2026-04-08T00:00:00Z`

## 当前实现说明

- AI 失败不会阻塞阅读链路，文章依然可读。
- 去噪失败时文章默认留在主 Feed，不会误进噪音箱。
- 翻译结果以段落双语数组形式返回，方便客户端做沉浸式双语阅读。
- 同步接口基于 `updated_at` 与 tombstone 表实现，适合 Mac / Android 轮询同步。
