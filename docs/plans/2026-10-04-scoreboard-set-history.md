# 比分牌逐局比分展示规划

## 背景与问题

当前经典（`CLASSIC`）和现代（`MODERN`）比分牌只展示选手名、已胜局数、当前局比分和发球方，无法从视频画面直接回看此前每一局的最终比分。参考用户提供的 WTT 比分牌截图，需要把比分牌改为“选手 + 已胜局数 + 按局编号排列的逐局比分”表格。

现有 `ScoreReplayService` 已能从 `score_event` 推导已完成局和当前比分，但 `ScoreboardCue` / `ScoreboardSnapshot` 尚未携带逐局比分序列，因此预览和导出渲染器都拿不到这些数据。本功能继续坚持“比分为派生数据，不持久化”的现有架构，不新增数据库列或迁移。

## 目标与成功标准

### 目标

让编辑器预览和导出的 MP4 在两种现有模板中都以 WTT 风格表格展示逐局比分，同时保留各模板原有配色、发球提示和模板选择行为。

### 可验证成功标准

1. 比分牌按两行展示双方选手，名称后显示已胜局数，右侧以 `1`、`2`、`3` 等真实局号作为列头。
2. 已结束局显示最终比分；正在进行的局在对应局号列中实时显示当前比分；不再额外显示独立的“当前比分”方块。
3. 经典和现代模板拥有相同的信息结构，但继续使用各自现有的黑黄与深色橙色视觉主题，并保留当前发球方提示。
4. 标准 1/3/5/7 局赛制完整显示全部已开始局；超过 7 局的自定义赛制只显示当前局及最近 6 局，并保留真实局号，例如 `4 5 6 7 8 9 10`。
5. 编辑器中的 PNG 预览与最终 FFmpeg 导出使用同一份快照数据和布局规则；比分变化、局结束及比赛结束后展示一致。
6. 空比赛从第 1 局 `0-0` 开始；局结束后上一局保留最终比分，若比赛未结束则新增下一局 `0-0` 列；比赛结束后不新增空白局。
7. 比分仍完全由事件回放派生，既有数据库和备份 JSON 格式无需迁移。

## 范围与非目标

### 包含

- 扩展内部比分牌 cue 和 snapshot，使其携带截至该时刻的逐局比分。
- 调整比分回放在普通得分、局结束、下一局开始和比赛结束时生成的逐局状态。
- 重做经典、现代两个 Java2D 渲染器的表格布局、局号表头和最近 7 局窗口。
- 让比分牌 PNG 宽度随可见局数变化，并让编辑器叠加预览按 PNG 相对视频帧的真实宽度显示。
- 增加覆盖回放、快照、渲染尺寸和预览/导出一致性的自动化测试。

### 非目标

- 不新增第三个 WTT 模板，也不逐像素复制 WTT 的品牌色、赛事标题、国旗、排名或赞助元素。
- 不修改计分规则、发球规则、事件录入、撤销/编辑事件流程或渲染任务互斥规则。
- 不新增持久化比分列，不修改 `schema.sql`、`SchemaMigrationRunner` 或备份文档结构。
- 不限制现有自定义赛制的目标获胜局数；仅在比分牌上采用最近 7 局窗口。
- 不引入前端框架、图形库或新的运行时依赖。

## 约束与现状

- Spring Boot 3.2 / Java 17；比分牌由 Java2D 生成透明 PNG，再由 FFmpeg overlay 到视频。
- `ScoreReplayService.replay(...)` 是比分派生和校验的权威入口；`score_event` 是唯一持久化比分事实。
- 编辑器预览调用 `GET /api/projects/{id}/scoreboard-preview`，导出通过 `RenderPlanService` 使用相同的 `ScoreboardSnapshotService`。
- 当前编辑器 CSS 把预览图固定为视频宽度的 30%，无法反映动态 PNG 宽度，需要与服务端 `renderWidth` 基准联动。
- 项目没有前端构建步骤；权威验证命令是 `mvn test`，测试不得调用真实 FFmpeg。
- 当前工作区 `main` 分支相对 `origin/main` 已领先 1 个提交；实施时不得重置或覆盖现有历史和用户修改。

## 关键设计决定

### 1. 内部数据模型携带完整逐局状态

新增轻量不可变值对象（建议 `SetScore`），至少包含 `setNumber`、`scoreA`、`scoreB`。在 `ScoreboardCue` 和 `ScoreboardSnapshot` 中增加按局号升序排列的 `List<SetScore>`：

- 初始 cue：`[(1, 0, 0)]`。
- 普通得分：已完成局保持最终分，当前局更新实时分。
- 局结束但比赛未结束：保留刚结束局最终分，并追加下一局 `0-0`。
- 比赛结束：最后一局保留最终分，不追加下一局。

列表在构造时使用不可变副本，避免回放循环继续修改历史 cue。渲染层只截取最后 7 项，不丢弃领域层的完整历史。

**理由：** cue 是按视频时间冻结的比分牌状态；把逐局数据放入 cue 后，预览和导出无需重新查询事件，也不会因查看历史时间点而误用比赛最终结果。

**未选择方案：** 只把 `ReplayResult.completedSets` 传给渲染器。该结果代表整场回放终态，不能直接表达任意视频时间点的当前局实时分。

### 2. 同一得分时刻选择回放生成的最终 cue

现有局胜点会在同一 `startTimeMs + sequenceNo` 下产生“局末比分”和“下一局/比赛完成”两个 cue。前端通过列表最后一项取值，而 Java `ScoreboardSnapshotService` 的 `max` 在比较键完全相同时不能明确保证选择后生成的状态。

实施时应统一语义：同一时间与事件序号存在多个 cue 时，以列表中最后生成的 cue 为准。这样局胜点发生后：

- 未结束比赛立即展示上一局最终分、更新后的已胜局数以及下一局 `0-0`；
- 已结束比赛展示最终各局比分和最终已胜局数。

可通过保留双 cue 并让 snapshot 稳定选择最后一项，或收敛为每个事件一个权威 post-transition cue 实现；优先选择对现有时间边界和事件序号影响最小的前者。

### 3. WTT 信息结构，保留模板视觉主题

两种 renderer 使用统一的列结构：

```text
                   胜局   1    2    3
选手 A              2   11    8    5
选手 B              0    6   11    3
```

- 表头只覆盖“胜局 + 局号”区域，不增加赛事标题栏。
- 已胜局数使用模板强调色；当前局列应与历史局视觉可区分，但不遮蔽数字。
- 历史局按真实局号排列；超过 7 局时截取最后 7 局。
- 发球标记继续靠近选手名区域；比赛完成后不显示。
- 选手名超出可用宽度时在单行内缩放或省略，不能覆盖胜局/逐局比分列。

布局尺寸和最近 7 局选择规则应放在共享的包内辅助逻辑中，避免经典、现代模板各自实现不同的窗口和宽度算法；颜色、圆角和强调方式仍由具体 renderer 决定。

### 4. 动态宽度与预览一致性

比分牌高度增加一个紧凑表头区域；宽度由“名称区 + 胜局列 + 可见局数列 + 间距”计算，最少按 1 局布局，最多按 7 局布局。左下角锚点保持不变，新增局时只向右扩展。

导出端继续由 renderer 返回实际尺寸的 PNG，FFmpeg overlay 的左下定位不变。编辑器预览在图片加载后，根据 `naturalWidth / renderWidth` 计算相对视频宽度，而不是固定 `30%`，确保预览与导出占屏比例一致。预览请求仍使用现有 `renderWidth=960`，不改变公开 API 参数。

### 5. 兼容与持久化

这是内部响应模型和视觉布局变化：

- `EditorState.scoreboardCues` 的 JSON 会新增逐局比分字段，现有前端可忽略新增字段；不删除已有 cue 字段。
- 预览 API 仍返回 PNG，URL、参数和错误语义不变。
- 数据库、schema、迁移和备份 JSON 不变，因为逐局比分继续由事件派生。
- 如为减少无关测试构造器改动而提供旧参数便利构造器，其默认逐局数据必须与 `setNumber/scoreA/scoreB` 一致，不能默认为空列表导致渲染空态与生产状态不同。

## 交付与证明映射

| 成功标准/风险场景 | 实施任务 | 验证证据 |
|---|---|---|
| 任意时间点拥有完整逐局状态 | 任务 1、2 | `ScoreReplayServiceTest` 覆盖初始、普通得分、跨局、比赛完成和超过 7 局数据 |
| snapshot 在局胜点选择正确状态 | 任务 2 | `ScoreboardSnapshotServiceTest` 构造同时间同序号 cue，断言选择最后状态 |
| 两种模板展示相同列结构且视觉不同 | 任务 3 | `ScoreboardTemplateRendererTest` 检查尺寸、透明度、不同像素和局数增长后的宽度 |
| 最近 7 局窗口保留真实局号 | 任务 3 | 共享布局/renderer 测试断言 8 局以上时只选择最后 7 局 |
| 预览与导出使用相同比例 | 任务 3、4 | renderer 尺寸测试 + 编辑器脚本检查 + `RenderPlanServiceTest` 生成分段 PNG |
| 不改变数据库与备份契约 | 任务 1、2、5 | diff 不包含 schema/迁移/备份结构改动；完整 `mvn test` 通过 |

## 实施计划

### 任务 1：建立逐局比分快照模型

- **目标**：让每个比分牌 cue 能冻结该视频时刻所有已开始局的比分。
- **文件/范围**：
  - 新增 `src/main/java/com/scoresync/domain/SetScore.java`（名称可按现有领域命名微调）。
  - 修改 `src/main/java/com/scoresync/domain/ScoreboardCue.java`。
  - 修改 `src/main/java/com/scoresync/domain/ScoreboardSnapshot.java`。
  - 更新受影响的生产构造点：`RenderJobController`、`ScoreboardPreviewService`、`ScoreboardSnapshotService`。
- **接口与依赖**：逐局列表按局号升序、不可变；snapshot 原样继承选中 cue 的逐局状态。
- **实施要点**：保留现有比分、已胜局数和比赛完成字段；新增字段不替代现有数据，避免发球计算和现有 UI 状态受影响。
- **完成条件**：初始、历史和最终 snapshot 都能直接读取逐局比分，不需要访问终态 `ReplayResult.completedSets`。
- **验证**：运行 `mvn -Dtest=ScoreboardSnapshotServiceTest test`；主要预期为时间选择和逐局列表断言通过。

### 任务 2：在比分回放中生成时间正确的逐局状态

- **目标**：在每个 cue 中维护普通得分、局结束、下一局和比赛结束的正确逐局序列。
- **文件/范围**：
  - `src/main/java/com/scoresync/service/ScoreReplayService.java`
  - `src/test/java/com/scoresync/service/ScoreReplayServiceTest.java`
  - `src/test/java/com/scoresync/service/render/ScoreboardSnapshotServiceTest.java`
- **接口与依赖**：依赖任务 1 的逐局值对象；不改变 `score_event`、`DerivedScoreEvent` 或计分合法性规则。
- **实施要点**：每次加入 cue 时复制当前逐局列表；局胜点后的最后 cue 必须含更新后的胜局数，并在比赛未结束时追加下一局 `0-0`。明确同时间同序号 cue 以最后一项为准。
- **完成条件**：对任意历史时间点，cue 中只包含当时已开始的局；比赛终态没有多余空白局。
- **验证**：运行 `mvn -Dtest=ScoreReplayServiceTest,ScoreboardSnapshotServiceTest test`，覆盖 11:0、12:10、跨局、决胜局、同毫秒顺序及至少 8 个已开始局的窗口输入。

### 任务 3：重做经典和现代比分牌布局

- **目标**：实现共享的 WTT 表格信息结构和两套主题化绘制。
- **文件/范围**：
  - `src/main/java/com/scoresync/service/render/ClassicScoreboardRenderer.java`
  - `src/main/java/com/scoresync/service/render/ModernScoreboardRenderer.java`
  - `src/main/java/com/scoresync/service/render/RenderDimensions.java`
  - 可新增 `src/main/java/com/scoresync/service/render/ScoreboardTableLayout.java` 等包内辅助类。
  - `src/test/java/com/scoresync/service/render/ScoreboardTemplateRendererTest.java`
- **接口与依赖**：输入为任务 1、2 提供的 snapshot；输出仍为透明 ARGB `BufferedImage`。
- **实施要点**：
  - 截取逐局列表最后 7 项并显示真实局号。
  - 布局包含表头、两行选手、胜局列和逐局列；当前比分不再单独绘制。
  - 宽度随可见局数增加，高度为表头加两行；保持左下锚点与发球规则。
  - 小尺寸和长选手名不得导致负尺寸、越界绘制或文字覆盖关键比分列。
- **完成条件**：两种模板对同一 snapshot 显示相同数据列，但像素风格明显不同；1 局与 7 局的 PNG 尺寸合理，8 局以上只显示最近 7 局。
- **验证**：运行 `mvn -Dtest=ScoreboardTemplateRendererTest test`；测试透明背景、模板差异、动态宽度、最多 7 列和紧凑尺寸。实现阶段额外生成代表性 PNG（空局、三局进行中、七局、十局窗口）进行人工视觉检查，但不把临时图片提交进仓库。

### 任务 4：使编辑器预览匹配动态 PNG 宽度

- **目标**：让浏览器叠加预览与导出视频中的比分牌占屏比例一致。
- **文件/范围**：
  - `src/main/resources/static/js/editor.js`
  - `src/main/resources/static/css/app.css`
  - 如 snapshot 重建签名变化，更新 `src/main/java/com/scoresync/web/RenderJobController.java` 和 `ScoreboardPreviewService.java`。
- **接口与依赖**：继续调用现有 preview API，继续使用 `renderWidth=960` 作为服务端画布基准。
- **实施要点**：图片加载成功后以 `naturalWidth / renderWidth * 100%` 设置预览宽度；CSS 只保留定位、透明度和合理最大宽度，不再强制 `30%`。切换模板、播放时间或比分后仍刷新预览。
- **完成条件**：逐局列增加时预览向右扩展，位置保持左下，且与相同帧宽下 renderer 的实际比例一致。
- **验证**：检查最终 JS/CSS 和浏览器预览；自动化侧由 renderer 尺寸测试及 `RenderJobApiIntegrationTest` 的 PNG 响应覆盖接口未破坏。

### 任务 5：更新集成构造点并运行回归验证

- **目标**：修正新增 record 字段引起的所有生产和测试构造点，确认渲染计划及 API 未回归。
- **文件/范围**：
  - `src/test/java/com/scoresync/service/render/RenderPlanServiceTest.java`
  - `src/test/java/com/scoresync/service/render/FfmpegCommandFactoryTest.java`
  - `src/test/java/com/scoresync/service/render/ScoreboardSnapshotServiceTest.java`
  - `src/test/java/com/scoresync/web/RenderJobApiIntegrationTest.java`
  - 通过 `rg "new ScoreboardCue|new ScoreboardSnapshot" src` 找到的其他直接构造点。
- **接口与依赖**：依赖前四项完成；测试继续使用 stub，不调用真实 FFmpeg/ffprobe。
- **实施要点**：优先让测试构造的数据与实际局号、当前比分一致；不要用空历史列表掩盖 renderer 行为。API 测试至少确认预览仍返回非空 PNG，并在有跨局事件时可成功生成。
- **完成条件**：所有直接构造点编译，预览、渲染计划和 FFmpeg 命令生成路径保持可用；diff 不包含 schema 或无关格式化。
- **验证**：先运行目标测试，再运行权威全量命令 `mvn test`。全量测试通过后不重复运行相同命令，除非又修改相关文件。

## 顺序与检查点

- 串行依赖：任务 1 → 任务 2 → 任务 3；任务 4 可在任务 3 确定动态宽度契约后进行；任务 5 最后统一收敛。
- 可安全并行的工作有限：renderer Java 文件和前端预览文件写入范围不同，但二者都依赖动态宽度约定；若使用并行执行，应先由主任务锁定该契约。
- 检查点：
  1. 任务 2 后复核一个完整跨局样例，确认 cue 时间状态没有使用比赛终态“穿越”到历史画面。
  2. 任务 3 后查看代表性 PNG，确认表头、数字和长名称没有重叠。
  3. 全量测试前检查 `git diff`，确认没有 schema、迁移、备份结构或临时图片改动。

## 风险、恢复与开放项

- **风险：同一时间双 cue 选错。** 通过“同键取列表最后状态”的单元测试固定语义。
- **风险：小分辨率或七列布局文字拥挤。** 使用最小字号/单行省略和最大 7 列约束，并对 320px 紧凑输入保留 renderer 测试。
- **风险：预览与导出比例不一致。** 预览宽度必须从 PNG 固有宽度和请求画布宽度计算，不能复制一套独立列数公式。
- **风险：可变列表污染历史 cue。** 每次 cue/snapshot 构造都保存不可变副本，并用跨局测试验证早期 cue 未被后续得分修改。
- **恢复方式：** 该功能无数据库迁移；若需要回退，可回退领域字段、renderer 和前端宽度改动，已有项目数据无需恢复。
- **开放项：无。** 已确认采用 WTT 表格式、同时应用经典与现代模板，并对超过 7 局的自定义赛制展示最近 7 局。
