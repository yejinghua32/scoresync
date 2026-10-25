# 得分队高亮规划

## 背景与目标

当前比分牌在得分后只更新数字；观众不易立即辨认是哪一队得分。为每次真实得分增加得分队整行高亮描边，编辑器叠加预览与导出 MP4 保持一致。

高亮持续时间在规划确认后由用户调整：从固定 1.5 秒改为**持续到下一次比分变化**。

## 已确认的成功标准

1. 从得分事件的 `videoTimeMs`（含该时刻）起，得分队的比分牌行显示清晰、高对比度的描边，并一直保持到下一次得分事件发生。经典与现代模板均生效。
2. 描边只影响得分队所在行；另一队不突出，既有发球标记、数字、队名和布局不被遮挡或移动，描边不越出比分牌画布。
3. 首个得分之前没有高亮；最后一次得分的高亮保持到视频结束。预览暂停或跳转到任意视频时刻时按该时刻确定状态，不按按钮点击后的墙上时间计时；导出在相同视频时刻显示相同得分方。
4. 新得分出现在前一高亮期间时，由新得分覆盖。同一毫秒有多条得分时，以现有 `video_time_ms, sequence_no` 顺序中的最后一条为准。局胜点立即开新局或结束比赛时，仍突出该局胜点的得分队整行，不依赖当前局数字是否归零。
5. 既有首分前不显示比分牌、无事件时不叠加、音视频输出和比分回放规则均不变。

## 范围与非目标

- 包含：得分方的瞬时派生状态、两个模板的行描边、预览 PNG 与导出 PNG 的高亮传递、对应测试。
- 不包含：逐帧闪烁、缩放或粒子动画；可配置的颜色或时长；比分事件持久化或备份格式变化；修改既有 MP4；单独给编辑器按钮增加点击特效。
- 不新增数据库列、迁移、第三方依赖或独立的定时任务。

## 现状与关键设计

- `ScoreReplayService` 从事件生成比分 `ScoreboardCue`；局胜点会在同一时刻和序号先产生本局得分 cue，随后产生新局 `0:0` 或完赛 cue。`ScoreboardSnapshotService.at` 选取最后的 cue，因此不能单凭相邻比分差判断得分方，也不能只强调当前局数字。
- 将得分方作为 cue 中的**派生、非持久化**信息从真实事件传递到同一事件引起的结算/开局 cue；初始 cue 不带得分方。快照包含当前应突出的得分方，供两个模板共用。保留既有便捷构造以减少测试夹具与调用方的无关改动。
- 高亮方 = 最近一次真实得分事件的得分方。同一事件的多个 cue 共享同一时间戳，因此局胜点后开新局或完赛时仍标记该局胜点方，且高亮自然延续到下一次得分。
- `RenderPlanService` 的分段边界本身就落在 cue 时刻，而 cue 时刻全部来自得分事件，因此**无需新增边界**：每个分段以其起点时刻取快照，天然渲染出该得分方的高亮，并在下一次得分时分段切换。`FfmpegCommandFactory` 无需改动。
- `RenderJobController` 的预览端点已经接收 `timeMs`，`editor.js` 在视频 `timeupdate`/定位时请求 PNG。预览沿用此路径；复制模板快照和缩放快照时必须保留高亮方。原有首分前显隐及过期图片请求保护继续有效，不引入前端独立倒计时。
- 编辑器原先把 `timeMs` 放进预览缓存键，播放时每次 `timeupdate` 都会重新取图并把叠加图淡出再淡入，比分牌与得分高亮会不停闪烁。改为用状态版本号（每次比分状态更新递增）与当前 cue 的序号作为刷新依据，叠加图只在比分变化时更新。
- 描边几何（行右边界、线宽、颜色）集中在 `SetScoreLayout`，两个模板共用同一份实现，仅配色体系不同。静态行描边优于“+1”标识（避免与发球标识竞争队名区）及逐帧动画（现有按事件生成 PNG 的架构无法低成本精确复现）。

## 交付与证明映射

| 结果 | 实施区域 | 验证证据 |
|---|---|---|
| 得分方派生与覆盖顺序准确 | `ScoreReplayService`、`ScoreboardCue`、`ScoreboardSnapshotService`、`ScoreboardSnapshot` | 回放/快照测试覆盖初始 cue 无得分方、得分 cue 携带得分方、局胜点与完赛 cue 保留得分方、同毫秒以序号决胜 |
| 高亮持续到下一次比分变化 | `ScoreboardSnapshotService` | 快照测试覆盖高分前为空、跨长时间保持、下一次得分时切换、最后一次得分保持到结尾 |
| 两模板行描边一致 | 两个模板 renderer、`SetScoreLayout` | 像素对比检查 A 行在上 B 行在下、无高亮时无高亮像素、320 与 1920 宽下不越界 |
| 预览与导出传递高亮 | `ScoreboardPreviewService`、`web/RenderJobController`、`RenderPlanService` | 预览端点集成测试检查得分时刻有高亮、首分前无高亮；导出分段测试检查各段 PNG 的高亮行随得分方切换 |

## 实施计划

### 任务 1：派生得分方与快照（已完成）

- **目标**：`ScoreboardCue`/`ScoreboardSnapshot` 携带得分方，回放服务传递，快照服务选出最近一次得分方。
- **文件/范围**：`domain/ScoreboardCue.java`、`domain/ScoreboardSnapshot.java`、`service/ScoreReplayService.java`、`service/render/ScoreboardSnapshotService.java` 及对应测试。
- **完成条件**：得分 cue 携带得分方，初始 cue 不携带；局胜点与完赛 cue 保留得分方；快照在高亮范围内返回得分方。
- **验证**：`mvn "-Dtest=ScoreReplayServiceTest,ScoreboardSnapshotServiceTest" test` 通过。

### 任务 2：模板行描边与预览透传（已完成）

- **目标**：两个模板为得分队整行绘制描边；预览与控制器在复制快照时透传得分方。
- **文件/范围**：`service/render/SetScoreLayout.java`、两个 renderer、`service/render/ScoreboardPreviewService.java`、`web/RenderJobController.java` 及对应测试。
- **完成条件**：描边不越界、不遮挡内容；无得分方时不绘制；A 行高亮在上、B 行高亮在下。
- **验证**：`mvn "-Dtest=ScoreboardTemplateRendererTest,ScoreboardSnapshotServiceTest" test` 通过。

### 任务 3：导出一致性与集成验证（已完成）

- **目标**：验证导出分段在得分方之间正确切换高亮，补齐预览端点集成测试，并消除编辑器预览的重复刷新闪烁。
- **文件/范围**：`service/render/RenderPlanService.java`（无需改动）、`src/main/resources/static/js/editor.js`、`src/test/java/com/scoresync/service/render/RenderPlanServiceTest.java`、`src/test/java/com/scoresync/web/RenderJobApiIntegrationTest.java`。
- **完成条件**：各段 PNG 的高亮行与该段得分方一致；分段边界仍落在得分时刻；预览端点与导出一致。
- **验证**：`mvn "-Dtest=RenderPlanServiceTest,FfmpegCommandFactoryTest,RenderJobApiIntegrationTest" test` 通过，随后 `mvn test` 全量通过；浏览器实测编辑器预览在得分时更新且播放中不再闪烁，导出 MP4 画面含高亮描边。

## 顺序与检查点

- 串行依赖：任务 1 → 任务 2 → 任务 3。
- 可以安全并行的独立任务：无（任务 2 依赖任务 1 的快照字段，任务 3 依赖任务 2 的渲染结果）。
- 复核边界：任务 1 与任务 2 完成后复核派生状态与渲染几何；全部完成后复核 diff 范围与最终验证。

## 风险、恢复与开放项

- 同时间同序号的结算 cue 必须保留原得分方；同毫秒不同序号以最后得分事件为准。测试覆盖局切换后的 `0:0` 和完赛 cue，防止误把描边画到发球方。
- 高亮覆盖整行描边会与当前局黄色分数格同时出现；选用亮绿色并在两个模板中验证可辨识度。
- 仅改变派生渲染与预览，不迁移数据；回退代码即可恢复旧外观，已有导出文件不会自动重制。开放项：无。
