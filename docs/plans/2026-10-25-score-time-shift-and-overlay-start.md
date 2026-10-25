# 比分时间批量平移与首分显示规划

## 背景与目标

编辑器目前只能逐条修改 `score_event.video_time_ms`。当全部记分点相对视频偏早或偏晚时，逐条编辑容易遗漏。导出视频当前从 0 秒就叠加初始 `0:0` 比分板，编辑器预览也如此；期望二者从首个真实得分时刻才显示比分板。

本次面向所有项目的编辑器，`projectId=15` 只是提出需求时正在打开的项目。

## 已确认的成功标准

1. 编辑器提供 `+100ms`、`-100ms` 两个按钮。每次点击仅将当前项目所有得分事件的时间整体加/减 100 毫秒，得分方、事件 ID、序号和相对顺序不变；多次点击可累积，成功后列表与预览立即更新。
2. 任一平移后的事件时间小于 0 或大于项目视频时长时，整次操作拒绝，所有事件保持原样并显示中文错误；不会只修改一部分。无事件时按钮不可操作，服务端空集合请求不产生写入。
3. 导出 MP4 在首个得分事件之前仅显示原视频，从该事件时间点开始显示当时的比分板。首分在 0 毫秒时从开头显示；项目没有得分事件时，全程不叠加比分板。
4. 编辑器预览使用同一可见时间规则，首分前和无事件时不显示叠加图；首次得分时刻及之后照常展示。经典、现代模板均适用。

## 范围与非目标

- 包含：项目级原子平移 API、两个编辑器按钮及其状态反馈、导出分段和 FFmpeg 滤镜的空/前导区间处理、预览显隐、相应测试。
- 不包含：任意步长输入、单条事件编辑行为变更、视频本身时间轴平移、已生成 MP4 的追溯修改、比分规则或模板外观修改。
- 不增加数据库列或迁移，不改变备份 JSON 结构；现有比分仍由事件回放派生。

## 现状与设计约束

- `ScoreEventController` 暴露逐条增删改；`ScoreEventService` 在项目锁和事务内校验 `0..project.durationMs()`，`ScoreEventRepository` 以 `video_time_ms, sequence_no` 排序。批量操作应复用这一边界，不能由浏览器连续发多次逐条 PATCH。
- `ScoreReplayService` 固定生成 0 毫秒初始 `0:0` cue，真实得分 cue 使用事件的 `sequenceNo`。保留初始 cue 供内部回放使用，不把它当作“首个有比分”的依据。
- `RenderPlanService` 当前从 0 毫秒生成 PNG 分段；`FfmpegCommandFactory` 按分段生成图片输入和 `overlay enable` 滤镜链。要允许首段从首分时刻开始，且支持零个叠加分段时仅传递原视频画面。
- `editor.js` 依据播放时间请求 PNG 并将图片叠加在视频上；隐藏时也要避免较早请求的 `onload` 再次显示旧图。预览 API 可保持现有 PNG 契约，由编辑器决定何时显示。
- 项目为 Spring Boot 3.2 / Java 17、SQLite、纯 JS；测试手工搭建 SQLite 并使用渲染 stub，不调用真实 FFmpeg。权威验证命令为 `mvn test`。

## 关键设计

### 批量时间平移

- 增加项目级端点，例如 `PATCH /api/projects/{id}/events/time-shift`，请求 `{ "deltaMs": 100 }` 或 `{ "deltaMs": -100 }`，成功返回最新 `EditorState`，供编辑器一次刷新。只允许这两个值，其他值按现有 `DOMAIN_VALIDATION`（HTTP 400）返回中文错误；不存在的项目沿用 `NOT_FOUND`。
- 服务在项目锁及数据库事务内读取当前事件和项目时长，先校验全部目标时间，再按 `match_id` 执行一条限定项目范围的批量更新（同时更新 `updated_at`）。可用当前最小/最大时间判定越界，但须考虑整数溢出；同一偏移保持事件的排序及 `sequence_no`。失败不得持久化任何修改。
- 没有得分时客户端禁用两个按钮；服务端收到空集合请求直接返回当前 `EditorState`，不影响其他项目。写操作沿用编辑器现有的串行写队列，避免快速点击和录分请求互相覆盖 UI 状态。
- 不采用逐条 PATCH：中途失败会留下部分时间修改，并增加多次请求及重排风险。

### 首个得分前的显示

- 从真实 `score_event`（或 `sequenceNo > 0` 的得分 cue）确定首分时间，不能从 0 毫秒初始 cue 判断。首分时间等于视频时长或超出实际探测时长时，可能没有可见的叠加帧，仍应产生有效原视频输出。
- `RenderPlanService` 仅为首分起至视频结束的比分状态生成 PNG 分段；首分前不创建初始 `0:0` 图片输入。`FfmpegCommandFactory` 对非空分段仍按 `enable` 的起止时间叠加；零分段时构造只有原视频的视频滤镜输出，保持现有编码、音频、时长与输出流程。不要以 0:0 透明假图代替空态。
- 编辑器以 `state.events[0].videoTimeMs` 判定可见性（事件已有时间排序），在首分前或空事件时隐藏图片；在首分及以后恢复请求/显示，平移或定位后立即重算。异步图片加载回调不得覆盖随后发生的隐藏状态。
- 当前预览 API 的路径、PNG 响应与模板选择不变；这是可见时间规则，不是比分或图片样式的变化。

## 实施与验证映射

| 结果或失败路径 | 修改区域 | 证明方式 |
|---|---|---|
| 全部事件同幅平移且只影响当前项目 | `ScoreEventController`、请求 DTO、`ScoreEventService`、`ScoreEventRepository`、`editor.html`、`editor.js` | `ScoreEventServiceTest` 覆盖双方向、连续操作、顺序和另一个项目不变；API 测试检查响应状态 |
| 越界/无效偏移整次拒绝 | 同上 | 服务及 API 测试检查 400 错误码、中文消息与数据库值未变；覆盖首事件接近 0、末事件接近时长 |
| 首分前、无分时导出不叠加 | `RenderPlanService`、`FfmpegCommandFactory` | `RenderPlanServiceTest` 断言起点和 PNG 数；`FfmpegCommandFactoryTest` 断言前导时间无 overlay、零分段滤镜及输入 |
| 预览可见性与导出一致 | `editor.js`、必要时 `app.css` | 浏览器检查首分前/后、无事件、快速平移及异步加载；渲染相关测试覆盖 0 毫秒与视频末尾边界 |

## 实施顺序

1. **完成原子平移后端。** 在 `src/main/java/com/scoresync/web/ScoreEventController.java` 和 `web/dto/` 增加受限请求，在 `service/ScoreEventService.java`、`repository/ScoreEventRepository.java` 实现项目锁、全量边界校验与单次更新。扩充 `src/test/java/com/scoresync/service/ScoreEventServiceTest.java`，必要时在 `src/test/java/com/scoresync/web/` 增加手工 MockMvc 接口测试。目标命令：`mvn -Dtest=ScoreEventServiceTest test`，接口测试类加入后同命令列出其类名。
2. **完成编辑器交互。** 在 `src/main/resources/static/editor.html` 的得分控制区域添加按钮，`src/main/resources/static/js/editor.js` 接入串行写队列、禁用空态、成功状态刷新及首分前预览显隐；必要时局部调整 `static/css/app.css`。验证两按钮连续操作、越界提示以及视频播放跨过首分时刻的预览，无前端构建步骤。
3. **完成导出可见时间。** 修改 `src/main/java/com/scoresync/service/render/RenderPlanService.java` 和 `FfmpegCommandFactory.java`，扩充对应测试。先覆盖有前导空白、首分 0 毫秒、无事件、首分位于探测视频末尾，再核对音频映射和输出参数未变。目标命令：`mvn -Dtest=RenderPlanServiceTest,FfmpegCommandFactoryTest test`；测试不启动 FFmpeg。
4. **集成检查。** 对照两种模板和当前项目外的项目检查 UI 与接口；运行 `mvn test`。核对最终 diff 仅涉及本功能和测试，没有 schema、迁移或临时媒体文件。验证后若仍修改相关文件，重新运行受影响检查。

## 风险与恢复

- 数据风险集中在越界或部分更新：必须在写入前完成整体校验，并在同一事务内按项目更新；错误路径测试直接比较数据库中的每条原时间。
- 导出零 PNG 输入时，FFmpeg 滤镜脚本仍须给 `[scoreboard]` 输出标签；测试检查生成参数和滤镜，实际视觉验收可使用已有本地视频手工导出，但自动测试不依赖外部二进制。
- 若回退功能，恢复上述后端、前端与渲染代码即可；没有 schema 或数据格式迁移。已经平移的事件时间属于用户数据，代码回退不会自动撤销，应在回退前依据备份或反向平移处理。
- 开放项：无。用户已确认原子拒绝越界、无分不显示、导出与预览同步的语义。
