# AGENTS.md

本地运行的 Spring Boot 3.2 / Java 17 应用，把乒乓球视频转换为带比分的 MP4。
单进程：Web UI + SQLite + 外部 FFmpeg。无鉴权、无云服务。

## 命令

- `mvn spring-boot:run` — 启动开发服务器（端口 **5574**）。可用 `$env:SCORESYNC_DATA_DIR = '...'`（PowerShell）覆盖数据目录。
- `mvn clean package` → `java -jar target/score-sync-0.0.1-SNAPSHOT.jar` — 打包后运行。
- `mvn test` — 唯一的验证步骤（无 CI、无 lint、无 formatter、无 checkstyle）。
- `mvn -Dtest=RenderJobServiceTest test` — 仅运行单个测试类。
- 不带 Maven wrapper — 使用系统 `mvn`。

## Schema 与迁移 — 重要

**没有 Flyway/Liquibase**。以下两套机制必须保持同步：

1. `src/main/resources/schema.sql` — 幂等的 `CREATE TABLE IF NOT EXISTS`。通过 `spring.sql.init.mode: always` 在**每次**启动时执行。
2. `src/main/java/com/scoresync/config/SchemaMigrationRunner.java` — 通过 `PRAGMA table_info(...)` 检查守卫的 `ALTER TABLE ... ADD COLUMN`。

新增列时：在 `schema.sql` 中更新以适配全新安装，**同时**在 `SchemaMigrationRunner` 中添加受守卫的 ALTER 以适配既有数据库。

## 测试不使用 Spring 上下文 — 最大陷阱

`src/test/` 下几乎所有测试都是**纯 JUnit**，手动构造 `JdbcTemplate` 连接到一个临时文件 SQLite 数据库，并使用**内联的 `CREATE TABLE` 语句**。它们**不会**加载 `application.yml`、**不会**读取 `schema.sql`、也**不会**运行 `SchemaMigrationRunner`。

由此带来的后果：

- 修改 `schema.sql` **不会**自动更新测试。必须手动修改各测试的内联 DDL（如 `RepositoryIntegrationTest`、`RenderJobApiIntegrationTest`、`RenderJobServiceTest`），否则在插入数据时会失败。
- 测试**从不调用真实 FFmpeg**。渲染相关测试通过 stub 子类化 `FfmpegRenderService` / `FfmpegProbeService`（如 `BlockingFakeRenderService`、`FixedProbeService`、`FakeRenderJobService`）。不要引入直接 shell 调用 `ffmpeg`/`ffprobe` 的测试。
- 没有充分理由不要迁移到 `@SpringBootTest` — 这种手工装配是有意为之且速度很快。

## 文件名不明显体现的架构事实

- **包结构与 `需求.txt` 不一致**（后者部分已过时 — 以代码为准）。使用 `web`（控制器）、`repository`（JDBC 仓储，无 ORM）、`domain`（不可变 Java records）、`support`（自定义异常）、`service.render`（FFmpeg 层）。`需求.txt` 中的 `controller`/`mapper`/`entity` 命名是错的。
- **比分是派生出来的，从不持久化。** 只存储 `score_event` 行（`match_id`、`video_time_ms`、`player_side`、`sequence_no`）。局比分、是否完成本局、当前发球方等都由 `ScoreReplayService.replay(targetWins, events)` 推导。该方法同时是校验器 — 向已结束的比赛添加比分会抛出 `DomainValidationException`。**不要**新增 `score_a`/`score_b` 列。
- **全进程同一时刻仅允许一个渲染任务。** 由 `RenderJobService` 互斥锁 + `render_job` 数据库查询共同保证。第二次 `POST /api/projects/{id}/render-jobs` → 409，返回 `{ "code": "RENDER_JOB_ACTIVE" }`。启动时 `RenderJobService.failInterruptedJobs()`（一个 `CommandLineRunner`，`@Order(20)`）会把崩溃遗留的 QUEUED/RUNNING 任务标记为 FAILED。
- **媒体按已登记的 asset ID 流式返回**（`GET /api/media/{assetId}`，支持 `Range` 的 HTTP 206）。文件**从不**复制，只存储绝对路径。不要新增可读取任意本地路径的接口。
- **备份/恢复使用 JSON**（`BackupController`）：从编辑器导出，导入到视频库。导入前需先重新登记与原视频元信息（size + mtime）相同的视频。

## FFmpeg

- 外部二进制，**启动时不强制要求**。若不在 `PATH` 中，可设置 `$env:SCORESYNC_FFMPEG_PATH` / `$env:SCORESYNC_FFPROBE_PATH`（PowerShell）。
- 默认值：`crf 18`、`preset medium`、音频 `192k`。输出分辨率/帧率通过 `ffprobe` 与源视频对齐。
- 输出目录：`${SCORESYNC_DATA_DIR}/exports`；工作目录：`${SCORESYNC_DATA_DIR}/render-work`。被取消/失败的任务会删除其工作目录。

## 数据目录

`SCORESYNC_DATA_DIR`（默认 `${user.home}/.scoresync`）存放 `score-sync.db`、`exports/`、`render-work/`。由 `DataSourceConfig.@PostConstruct` 按需创建。不同操作系统默认位置不同 — 手动测试时若要使用已知数据库，请显式设置该环境变量。

## 前端

`src/main/resources/static/` 下的纯 HTML + ES 模块（`index.html` = 视频库，`editor.html` = 记分）。**无构建步骤、无框架、无 bundler。** `static/js/api.js` 是唯一的 HTTP 辅助文件。键盘快捷键：`A`/`L`（左右两侧选手得分）、`Space`（播放/暂停）、`←/→`（按 `scoresync.editor.seek-seconds` 跳转，默认 1 秒）。

## 约定

- 所有面向用户的错误信息为**中文**；机器可读的错误码（`RENDER_JOB_ACTIVE`、`DOMAIN_VALIDATION`、`MEDIA_UNAVAILABLE`、`NOT_FOUND`、`RENDER_JOB_NOT_CANCELLABLE`）为英文。请保持这种区分。
- 错误响应通过 `ApiError(code, message, details)` 由 `ApiExceptionHandler`（`@RestControllerAdvice`）统一返回。新增异常类型时在这里登记。
- `需求.txt` 与 `docs/superpowers/specs|plans/*.md` 是背景设计文档（中文）— 用于理解意图，但与代码冲突时以代码为准（如端口、包名、数据库列名）。

## Phase 1 限制（有意为之，非 bug）

视频格式仅限 `.mp4` / `.webm` / `.mov`。不做转码 — 实际能否播放取决于浏览器编解码器。无自定义快捷键、无云端同步。
