# RSS Copilot Server

RSS Copilot 的第一版服务端实现，技术栈为 `Java 17 + Spring Boot 3 + MyBatis + SQLite`。

当前版本已覆盖的核心能力：

- 邮箱密码登录，数据库预置用户，Bearer Token 会话
- RSS 订阅源增删改查、RSS URL 自动规整去重（支持省略协议的订阅源地址）、网站 URL 自动发现 RSS/Atom/JSON Feed（alternate、页面 Feed 链接、常见 feed 路径）、HTTP 重定向后的最终 Feed 地址解析与刷新后地址迁移、文件夹分组、按小时自动刷新、全量/批量/单源手动刷新并返回已接收数量，批量刷新会跳过已删除、无归属或停用的订阅源并继续处理其余有效源，刷新失败原因会区分超时、DNS、连接、TLS、响应解码和 HTTP 状态，并带阅读器请求头提升真实源兼容性
- OPML 订阅导入/导出，便于从其他 RSS 阅读器迁移；导入会保留嵌套文件夹和扁平 OPML 的 `category` 分类，导出会同时保留嵌套文件夹与 `category`，并拒绝异常超大文件和一次性过多订阅，避免迁移文件拖垮服务端
- RSS/Atom/JSON Feed 文章抓取、作者/署名保留（含 `dc:creator`）、源站图标保留、正文清洗、正文相对链接/图片按重定向后的最终地址绝对化、全文优先回退摘要，刷新时会同步已有文章的标题/摘要修订、把曾经失败的正文抓取升级为全文，并自动重跑摘要/翻译；兼容相对链接、相对图片、懒加载图片/srcset、media / iTunes 图片和图片 enclosure
- AI 去噪、总结、翻译及处理状态透出，基于 Spring AI 集成 DeepSeek
- Feed / 噪音箱 / 稍后读 / 订阅源文章分页列表与服务端全文搜索，来源级过滤会校验订阅源归属，便于多端删除后客户端可靠清理陈旧范围
- 已读 / 未读、收藏稍后读、阅读进度同步、手动移入/恢复噪音箱、批量标记已读
- 设置页后端能力：AI 配置、外观主题更新、Feed 默认语言、账号信息
- 多端同步接口：全量快照和增量变化

## 本地启动

```bash
make test
make smoke
make build
make dev
```

默认启动端口：`8080`

默认 SQLite 文件：`./rss-copilot.db`

Makefile 会优先使用当前环境里的 `JAVA_HOME`；如果未设置且本机存在 Homebrew OpenJDK，会自动使用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`，避免 macOS Maven Wrapper 找不到 Java Runtime。

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
RSS_COPILOT_ALLOWED_ORIGIN_PATTERNS="http://localhost:*,http://127.0.0.1:*"
RSS_COPILOT_SESSION_TTL_DAYS=30
RSS_COPILOT_REFRESH_CRON="0 0 * * * *"
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

`RSS_COPILOT_ALLOWED_ORIGIN_PATTERNS` 用于本地 Web 调试或反向代理接入时的 CORS 白名单，多个值用英文逗号分隔。

## AI 集成说明

- 当前项目已接入 [Spring AI](https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html) 的 DeepSeek 模块。
- AI 设置里的 `provider` 当前仅支持 `DEEPSEEK`；服务端会拒绝其他值，避免保存后才在后台处理阶段失败。
- 由于 API Key 是按用户保存在 `ai_prompt_config` 表里，而不是全局单实例配置，所以这里采用的是“程序化创建 `DeepSeekChatModel`”的方式，而不是直接依赖全局 `spring.ai.deepseek.api-key`。
- 现有环境变量 `DEEPSEEK_BASE_URL` 和 `DEEPSEEK_MODEL` 仍然有效，用于控制默认模型和兼容 OpenAI 风格的 DeepSeek 接口地址。

## Makefile

- `make build`: 打包
- `make test`: 跑测试
- `make smoke`: 跑真实 HTTP 主流程验收，覆盖健康检查、登录、加源、刷新、阅读详情、收藏、进度、已读、OPML 和同步启动包
- `make lint`: 运行 Spotless 检查
- `make dev` / `make run`: 本地启动
- `make deploy`: 构建 Docker 镜像

定向回归单个测试类时可传 Maven 参数：

```bash
make test ARGS="-Dtest=HealthIntegrationTest"
```

## Docker

```bash
make deploy
mkdir -p data
docker run --rm --name rss-copilot-server \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  -e RSS_COPILOT_DEFAULT_USER_EMAIL=you@example.com \
  -e RSS_COPILOT_DEFAULT_USER_PASSWORD=your-password \
  rss-copilot-server:latest
```

镜像内置 Docker `HEALTHCHECK`，会直接请求容器内 `http://127.0.0.1:${SERVER_PORT:-8080}/api/health`，不依赖基础镜像里的 `curl` / `wget`。启动后可用下面命令确认容器是否已真正可用：

```bash
docker inspect --format='{{.State.Health.Status}}' rss-copilot-server
```

生产或公网自部署时建议把服务端放在 HTTPS 反向代理之后，并把 `RSS_COPILOT_ALLOWED_ORIGIN_PATTERNS` 设置为客户端实际域名；本地 Mac / Android 真机调试可以保留局域网 HTTP，但不要把默认 demo 密码暴露到公网。

## 主要接口

详细接口说明见：[docs/client-api.md](docs/client-api.md)

### System

- `GET /api/health`

### Auth

- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`

### Feed Sources

- `GET /api/feed-sources`
- `GET /api/feed-sources/opml`
- `POST /api/feed-sources/opml/import`
- `POST /api/feed-sources`
- `PUT /api/feed-sources/{id}`
- `DELETE /api/feed-sources/{id}`
- `POST /api/feed-sources/refresh`
- `POST /api/feed-sources/{id}/refresh`
- `GET /api/feed-sources/{id}/entries`

### Entries

- `GET /api/entries?view=feed|noise|saved|all&unreadOnly=true|false&sourceId=1&folder=Tech&q=keyword&limit=60&beforePublishedAt=...&beforeId=...`，`q` 支持空格分隔多关键词，关键词之间为 AND 关系，最多取前 8 个唯一关键词
- `GET /api/entries/{id}?markRead=true|false`
- `POST /api/entries/{id}/read`
- `POST /api/entries/read`
- `POST /api/entries/{id}/unread`
- `POST /api/entries/{id}/saved`
- `POST /api/entries/{id}/unsaved`
- `POST /api/entries/{id}/progress`
- `POST /api/entries/{id}/noise`
- `POST /api/entries/{id}/feed`
- `POST /api/entries/{id}/ai/reprocess`
- `POST /api/entries/read-all?view=feed|noise|saved|all&sourceId=1&folder=Tech`

### Settings

- `GET /api/settings`
- `PUT /api/settings/ai`
- `PUT /api/settings/appearance`
- `PUT /api/settings/feeds`

### Sync

- `GET /api/sync/bootstrap`
- `GET /api/sync/changes?since=2026-04-08T00:00:00Z`

## 当前实现说明

- AI 失败不会阻塞阅读链路，文章依然可读。
- AI 失败或跳过后可对单篇文章手动重新处理，服务端会先返回处理中状态再后台重试。
- 去噪失败时文章默认留在主 Feed，不会误进噪音箱。
- 翻译结果以段落双语数组形式返回，方便客户端做沉浸式双语阅读。
- 同步接口基于 `updated_at` 与 tombstone 表实现，适合 Mac / Android 轮询同步。
