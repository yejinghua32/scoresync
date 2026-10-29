# 剪辑时间轴、精选片段与按范围导出规划

## 背景与问题

剪辑页目前在视频下方显示保留帧时间轴，而源时间轴处于左列下一行；右侧已存档片段列表虽设置了纵向溢出滚动，但没有稳定的高度边界，也不会随当前帧匹配列表项。片段只支持存档和删除，不能在列表中调整边界或标记精选；导出固定拼接全部 KEEP 片段。

本次在现有单视频剪辑项目上完善浏览、修订与精选导出。原剪辑方案见 `docs/plans/2026-10-26-video-clipping.md`；以当前代码和本次已确认决定为准。

## 目标与成功标准

1. 源时间轴在视频正下方，保留帧时间轴移至原源时间轴区域；桌面右侧存档区从主区域顶部对齐且独立滚动，窄屏不横向溢出。
2. 当前帧属于某个已存档半开区间时，该列表项高亮并滚动到可见区域；在未标记空隙中不错误选中。播放、拖动定位、键盘移动及点击时间轴均同步。
3. KEEP 片段可切换持久化精选状态；旧项目重开后原片段默认为非精选，DROP 不可精选。片段起止帧可在列表中精确调整，覆盖相邻片段后分类与精选状态确定，刷新后仍正确。
4. 删除片段不弹确认框；成功后区间回到未标记，失败时列表不伪装为已删除。
5. 导出可选全部保留或仅精选，默认全部保留；仅精选为空时不能启动。开始任务时冻结相应区间，后续编辑不改变进行中的导出；导出精度、取消/失败清理和自动登记沿用现有行为。

## 范围与非目标

- 包含：剪辑页两条时间轴换位、顶部对齐、右侧列表滚动与当前帧同步；存档片段编辑和直接删除；KEEP 精选持久化；两种导出范围及旧库迁移。
- 非目标：片段重排、多视频拼接、只播放精选的预览模式、修改记分项目、修改 FFmpeg 按解码帧精确选取及音频拼接机制、精选专用备份格式。

## 约束与确认依据

- 用户在本会话明确确认：精选是 KEEP 的持久属性，旧片段默认非精选、DROP 不可精选；边界调整覆盖相邻存档片段；删除不弹确认。随后确认了布局、同步选中、编辑控件、覆盖后精选归属、同状态合并、两种导出范围和预览不变的完整摘要。
- 来源为 `brainstorming`；本会话只交付此文档。实施须由用户在新会话使用 `subagent-driven-development` 启动，不在本会话修改代码、数据库或创建 commit。
- 当前 `clip_segment` 为互不重叠的 KEEP/DROP `[startFrame, endFrame)`，未标记是空隙；`ClipProjectService.commitSegments` 会覆盖重叠旧片段、合并相邻同类片段，并重写所有行及 ID。精选不能只绑定前端状态或依赖 ID 永不变化。
- Spring Boot 3.2 / Java 17、SQLite/JDBC、纯 HTML/CSS/ES 模块；`schema.sql` 与 `SchemaMigrationRunner` 必须同步，手工 SQLite DDL 的纯 JUnit 测试也须更新。项目权威验证命令为 `mvn test`，测试不调用真实 FFmpeg。

## 关键设计与契约

### 布局与同步

- 将源时间轴整体（标题、当前帧/暂选信息、轴与刻度）置于视频正下方；保留帧时间轴整体放到原源轴位置。宽屏右侧存档区顶部与主区域顶部对齐，列表获得明确的可用高度及自身纵向滚动；窄屏按既有单列顺序排列并给列表合理的最大高度。轴的源时间/成片时间语义及点击行为不互换。
- 利用现有帧索引把视频播放时间映射到当前帧；仅包含 `startFrame <= frame < endFrame` 的一项被高亮，空隙清除高亮。选中项变更时将其滚到列表可见范围，避免每次 `timeupdate` 抖动；手动滚动仍可用。编辑控件点击不能触发行定位或双击循环。

### 精选、编辑与覆盖

- 在 `clip_segment` 增加布尔精选值，数据库用 `INTEGER NOT NULL DEFAULT 0` 并约束仅 KEEP 可以为 1；API 响应包含 `featured`。新存档默认非精选，旧库迁移后已有数据保持类型与范围、精选为假。
- 精选开关只作用于当前项目的 KEEP 片段；DROP 请求精选或不存在/不属于该项目的 ID 必须拒绝。建议使用 `PATCH /api/clip-projects/{id}/segments/{segmentId}` 提交 `{ "featured": true|false }`，返回更新后的项目详情；服务层在同一事务及现有片段锁内读、改、合并。
- 建议使用 `PUT /api/clip-projects/{id}/segments/{segmentId}` 提交 `{ "startFrame": n, "endFrame": m }`，编辑时保留该片段原类型和精选状态。UI 提供起止帧数值输入、各自逐帧增减和“设为当前帧”；显示开始帧含、结束帧不含，设置结束边界到当前帧时使用 `cursor + 1`。只能保存 `0 <= startFrame < endFrame <= totalFrames`；无效范围/索引未就绪返回中文可理解的验证错误，失败保持编辑草稿供修正。
- 编辑先移除原区间：缩短后腾出的帧变为未标记；扩大或平移后的新区间按原存档覆盖规则覆盖邻段。邻段未被覆盖的残段保留各自类型及精选状态；被新片段覆盖的部分取得新片段的类型和精选状态。普通新存档也按非精选状态覆盖；相邻片段仅在类型与精选状态都相同才合并。原片段 ID 因重写/合并变化时，前端用服务端返回的完整列表刷新，不缓存旧 ID。
- 删除沿用现有 DELETE 路径及未标记语义，移除 `confirm`；仅服务端成功后刷新列表与时间轴，失败显示错误。源视频变化、帧索引和项目归属等现有校验不能因新增编辑入口而绕过。

### 导出

- 在剪辑页提供“全部保留 / 仅精选”范围选择，默认全部保留；选择仅精选且没有精选 KEEP 时禁用导出并给出中文说明。预览及保留帧时间轴仍按全部 KEEP，不随导出范围改变。
- `POST /api/clip-projects/{id}/render-jobs` 接受可选 JSON `{ "mode": "ALL"|"FEATURED" }`；无请求体保持现有全部 KEEP 行为，未知模式用统一 `DOMAIN_VALIDATION` 错误拒绝。服务端而非前端决定并验证区间集合：ALL 取全部 KEEP，FEATURED 只取精选 KEEP；集合为空拒绝创建任务，原有全进程导出占用冲突仍适用。
- 启动时冻结过滤后的区间及帧索引；复用现有帧序号精确导出、音频、输出验证、失败/取消清理及视频库登记。任务中途改精选或边界不改变该次结果，下一次导出读取新状态。不覆写已有成功文件。

## 交付与证明映射

| 成功标准/失败路径 | 实施任务 | 待执行验证证据 |
|---|---|---|
| 旧库默认非精选，覆盖/拆分/合并/编辑后精选归属正确，DROP 拒绝精选 | 任务 1 | 迁移、仓储、服务及 API 测试 |
| 列表独立滚动、当前帧匹配、轴换位、编辑控件和无确认删除 | 任务 2 | 静态资源测试及桌面/窄屏浏览器手测 |
| ALL/FEATURED 输出区间、空精选和非法模式拒绝，任务快照及失败路径 | 任务 3 | 导出服务/API/命令测试和实际样片手测 |
| 现有记分与剪辑导出互斥、旧客户端无体请求保持语义 | 任务 3、4 | 相关回归测试与 `mvn test` |

## 实施计划

### 任务 1：精选及片段边界持久契约

- **范围**：`schema.sql`、`SchemaMigrationRunner.java`、`domain/ClipSegment.java`、`repository/ClipSegmentRepository.java`、`service/ClipProjectService.java`、`web/ClipProjectController.java` 与片段 DTO；对应 `SchemaMigrationRunnerTest`、`ClipProjectRepositoryIntegrationTest`、`ClipProjectServiceTest`、`ClipProjectApiIntegrationTest` 等内联 DDL。
- **完成条件**：全新/旧库有相同精选列与默认值，迁移重复执行安全；精选开关和边界编辑按上述事务性覆盖规则工作；DROP、越界、非法 ID/项目归属及索引未就绪正确拒绝；原 POST 存档仍可使用。
- **待执行验证**：`mvn "-Dtest=SchemaMigrationRunnerTest,ClipProjectRepositoryIntegrationTest,ClipProjectServiceTest,ClipProjectApiIntegrationTest" test`；覆盖新建、旧库、覆盖精选残段、邻接合并和失败路径。

### 任务 2：剪辑页布局、列表同步与编辑

- **范围**：`src/main/resources/static/clip-editor.html`、`static/js/clip-editor.js`、`static/css/app.css` 中剪辑页局部样式，以及 `StaticPageIntegrationTest`。
- **依赖与完成条件**：依赖任务 1 的 `featured`、编辑/切换响应。两轴换位且各自时间语义不变；滚动容器在桌面/窄屏稳定；播放、暂停定位、跳帧时只高亮包含当前帧的片段；编辑的逐帧/当前帧操作、保存/失败、精选开关和直接删除不误触原列表交互。
- **待执行验证**：`mvn "-Dtest=StaticPageIntegrationTest,ClipProjectApiIntegrationTest" test`；浏览器在桌面和窄屏用有空隙、KEEP/DROP、长列表的项目检查布局、滚动、自动定位与重开持久性。

### 任务 3：按精选范围导出

- **范围**：`web/ClipRenderJobController.java`、新增范围请求 DTO、`service/render/ClipRenderJobService.java`、剪辑页导出控件与逻辑，以及 `ClipRenderJobServiceTest`、`ClipRenderJobApiIntegrationTest` 的内联 DDL/用例；FFmpeg 命令工厂仅在现有区间输入契约确有需要时修改。
- **完成条件**：无体/ALL 请求保持原行为，FEATURED 只冻结精选 KEEP；空集合及非法模式拒绝且不产生任务；任务开始后的编辑不改变已冻结输出；导出互斥、取消、失败清理及成功登记保持有效。
- **待执行验证**：`mvn "-Dtest=ClipRenderJobServiceTest,ClipRenderJobApiIntegrationTest,ClipFfmpegCommandFactoryTest,RenderJobServiceTest" test`；用真实短视频手测两种范围和空精选提示，不把 FFmpeg 引入 JUnit 测试。

### 任务 4：集成验收与交付

- **完成条件**：复核全新和旧库、片段覆盖及编辑、两种导出模式、长列表与窄屏、失败/取消路径；最终 diff 仅包含本主题及必要测试，无临时媒体或数据库文件。
- **待执行验证**：`mvn test`；手测须显式使用临时 `SCORESYNC_DATA_DIR`，若本机无 FFmpeg/可用样片则如实记录未完成的媒体检查。验证通过且可区分改动归属后按 `git-handoff` 创建聚焦本地提交；不自动 push、PR 或部署。

## 风险、恢复与开放项

- 相邻 KEEP 现在可能因精选状态不同而保持分段；这是为防止精选边界被自动合并抹去的有意变化。区间总覆盖、源帧顺序与全部保留导出的内容不变。
- 已有 `commitSegments` 全量重写 ID，实施时必须保证每个拆分残段的精选值被继承，并使并发编辑/标记/删除在同一锁与事务内完成；不能以浏览器本地标记替代持久化。
- 新列只做守卫式增量迁移，不重建或删除旧表；出现导出失败时保留项目与片段，沿用既有任务清理和下次可重试路径。回滚应用代码前需评估旧代码写入新增列默认值的兼容性，不清空用户库或已登记导出。
- 开放项：无。具体控件样式和内部方法拆分由实施者在上述行为契约内决定。
