# AI 层移植说明（2026-09）

## 来源与基线
- 底座：官方 legado 2026 终点快照 `hectorqin/legado`（HEAD da17bb2be，GPL-3.0）；本仓库 `Arisemoss/legado-ai-2026` 以其为种子。
- AI 层来源：`Arisemoss/legado`（2020 基线 AI 增强 fork）的 `io.legado.app.ai`（44 文件/5021 行）及宿主咬合点。

## 已移植内容
- `app/src/main/java/io/legado/app/ai/**`：runtime/tool/bridge/skill/model/log/ui 全量（含 22 工具注册、三协议、ApprovalBus 确认总线、AiKeyStore、AiLog）。
- 资源：`ai_*` 布局/drawable/colors、`pref_config_ai.xml`（改为 androidx.preference 类）、arrays/样式。
- 构建：`ai` flavor（applicationId `io.legado.ai`，与官方包共存）；proguard `-keep io.legado.app.ai.**`；`google-services.json` 补 ai 占位 client。
- Manifest：`AgentHubActivity`（AI 对话页）、`AiLogActivity`（运行日志页）。
- 数据：Room **v75→76**，新增 `aiSessions/aiMessages` + `migration_75_76`（老用户从其现有版本可迁移）。
- 宿主接线：`App.onCreate`（AiLog.attach + AiPlatform.init）、`PreferKey` 14 个 ai_* 键、我的页「AI 智能助手」入口（→ ConfigActivity `aiConfig`）、`AiConfigFragment`（服务商预设自动填充/加密 Key 存储/背景设置）。
- UI 现代化改动：`AiLogActivity`、`AgentHubActivity` 改为 ViewBinding + 自包含 adapter（Kotlin 2.3/AGP 8.13 无 synthetic）。

## 验证证据（GitHub Actions）
- 工作流：`.github/workflows/ai-port.yml`（push master/port-master 触发；matrix `[ai, app]`）。
- 最新绿：commit `3e7fb508c6` → run [34735570518](https://github.com/Arisemoss/legado-ai-2026/actions/runs/34735570518)：`testAiDebugUnitTest` + `assembleaiDebug` + `assembleappDebug` 全部 SUCCESS（ai/app 双 job）；产物 `ai-debug-apk`。
- 单测：`ai/model/AgentErrorTest`、`ai/runtime/OpenAIClientTest`、`ai/runtime/ApprovalBusTest`、`ai/tool/TextToolCallParserTest`、`ai/tool/SuggestionEngineTest`。

## 分支
- `master`（默认）：移植主线；`port-master`：同名备份分支。

## 已完成接线（2026-09-12 更新）
- AI Hub 入口：阅读页右下角 **AI 悬浮球**（activity_book_read.xml）+ 阅读菜单 **AI 项**（view_read_menu.xml / ReadMenu.openAiAssistant，携书名与章节预设）。
- MainActivity 处理 agent_select_tab（AI 导航跳书架；旧版 ViewPager 用 adapter.count）。
- AI 设置页 **测试连接** 按钮（OpenAIClient 最小 ping，显示 ✅/❌ 与模型名）。
- CI 优化：matrix [ai, app]；单测仅在 ai job 执行；纯文档提交（docs/**、**.md）不触发构建。

## 待办（剩余可选/需真机）
1. **真机冒烟**：按 docs/SMOKE_TEST.md 安装 ai-debug APK，走对话/工具/确认流。
2. app/schemas/io.legado.app.data.AppDatabase/76.json 入库（KSP 生成后提交）。
3. 正式签名包：配置 Secrets 后跑 release（R8 keep 已配）。
4. ~~阅读页悬浮球可关闭开关~~ 已完成（`ai_float_ball_enabled` + `ai_float_ball_scope` 显示范围）。

## 已知技术债
- 桥层对 2026 API 为「最小可用」适配：搜索/正文直接映射到 `WebBook.searchBookAwait/getContentAwait`，未复刻旧版的 scope 复用与全部超时策略细节。
- `AiKeyStore` 在 API<23 或解密失败时仍回退明文（沿用旧实现）。
## 第二批功能（2026-09）
- 主页悬浮球（`activity_main.xml` FrameLayout 包裹挂载）+ `ai_float_ball_enabled` 开关；主页/阅读页 `onResume` 即时生效。
- `ModelManager.fetchModels(/models)` + 设置页自动拉取模型列表（选择/刷新/预设回退）。
- `BookSourceHub`（解析 `yuedu://booksource/importonline` 深链→下载 JSON/TXT→`SourceHelp` 入库）+ `SourceHubUi`（我的页入口）+ AI 工具 `import_book_sources`。
- 新增工具：`list_settings`、`manage_replace_rule`、`list_replace_rules`、`delete_book_source`；`set_setting` 白名单扩展。工具总数 27。
## 第三批功能（2026-09）
- `BookSourceImportActivity` + `item_source_hub.xml`：预扫描（`BookSourceHub.scan`）→ 勾选 → 批量导入（逐项状态/进度）。
- `AiSetupWizardActivity`：首启向导（4 步）；`PreferKey.aiSetupDone/aiSetupShown`；MainActivity 首次引导。
- `pref_config_ai.xml` 重写为 5 组；`AiConfigFragment` 改为复用 XML 声明键（测试连接/刷新模型/工具说明/日志入口）。
- `SourceHealth` + `SourceHealthActivity`：批量检测（并发 4 / 12s / 上限 50）、选中失效、导出备份、确认删除。
- 工具：新增 `add_book_to_shelf`（写确认）→ 总计 28；`search_books` 描述引导「先询问再加入书架」。
## 第四批功能（P1 工具，2026-09）
- 桥层：`addToShelfBatch`、`importReplaceRules`（URL/JSON）、`resetSetting`（白名单默认值）。
- 工具：`batch_add_to_shelf`、`test_sources_batch`、`import_replace_rules`、`reset_setting`（工具总数 32）。

## 第五批功能（工具结果可操作化，2026-09）
- 新增 `ai/model/SuggestedAction.kt` + `ai/tool/SuggestionEngine.kt`：从「工具 id + 参数 + 结果 JSON」派生建议动作（≤3 条）；非法 JSON / 字段缺失 / 未知工具一律返回空列表，绝不抛异常影响工具流水线。
- 透传链路：`ToolResult → AgentRuntime.suggestionsFor() → ToolEvent.actions → ChatRow.ToolCard.actions → ai_item_tool.xml(sv_tool_actions/ll_tool_actions)`；写操作在 `onApproved` 后补发一次终态事件，卡片由「已确认，正在写入」推进到真实写入结果（耗时为提案+写入两段实测）。
- 安全红线：动作 kind 仅 `prompt/reader/shelf/sources/health/replace/settings/search`，**没有直接写库动作**；写操作统一 `KIND_PROMPT` 交回 Agent，再由 `manualConfirm` 工具弹确认卡——快捷按钮无法绕过两阶段确认（单测有专项断言）。
- 修复断链：`DefaultBookFetcher.search` 结果补全 `bookUrl/origin/originName/tocUrl/coverUrl/intro/kind/type`（此前只有 name/author/from，`add_book_to_shelf` 拿不到 bookUrl 必然失败）；`search_books` 工具描述同步说明字段用途。
- UI：工具卡片下方新增横向可滚动的建议按钮条（复用 `ai_bg_chip`），运行中/待确认/已拒绝/失败态不显示。
- 单测：`app/src/test/java/io/legado/app/ai/tool/SuggestionEngineTest.kt`（10 例）。

## 第六批功能（体验修复与 UI 统一，2026-09）
- 向导（`AiSetupWizardActivity`）：`skip` 只写 `aiSetupShown`（不再写 `aiSetupDone`）；设置页 `ai_rerun_setup` 可重开；`MainActivity` 触发点移到隐私协议同意之后；完成后进入 `AgentHubActivity`；布局改为 `AiTopBar` + 可滚动页面 + 主题化列表项 `ai_item_wizard_choice`，Manifest 加 `adjustResize`。
- 工具清单：`AiConfigFragment.showToolsDialog()` 按 `category` 分组列出注册表全部工具（数量动态取 `AiPlatform.registry.definitions()`），替换原先的 Toast；删除「连接测试按钮后续接入」过期注释。
- 统一顶栏 `ai/ui/AiTopBar.kt`：主题主色背景 + `systemBars` insets + 返回 + 标题/副标题 + 图标动作槽；Hub / 运行日志 / 向导三页接入，Hub 补上返回按钮，「会话」由文字 chip 改为 `ic_ai_sessions` 图标。
- 配色跟随主题：`values/colors_ai.xml` 中性色改为基座别名（`@color/background`/`primaryText`/`secondaryText`/`background_card`/`divider`/`disabled`），`values-night/colors_ai.xml` 只保留语义色；`ai_bg_chip`/`ai_bg_bubble_user`/`ai_bg_send_circle`/`ai_bg_avatar_ai`/`ai_bg_btn_send` 改用 `?attr/colorPrimary`；用户气泡与 AI 头像文字在代码里按主色明暗取对比色（`ColorUtils.isColorLight`）；删除 `ai_bg_header_glass`（含 night）与 `drawable-night/ai_bg_chip.xml`。
- 悬浮球：新增 `PreferKey.aiFloatBallScope`（both/reader/main）+ 设置项 + arrays；`refreshEnabled()` 按宿主页面过滤；宿主为 Activity 时不再加 `FLAG_ACTIVITY_NEW_TASK`。
- 我的页入口图标改为 `ic_ai_assistant`（星芒）；空态快捷 chip 修复右缘裁切（`clipToPadding=false`）；建议按钮统一走 `@style/AiSuggestionChip`。
- `MainActivity.handleRequestTab` 改为 `viewPagerMain.post {}` 后切页（修启动/返回瞬间 ViewPager 两页叠影）。
- **崩溃修复（重要）**：AI 层 4 个布局（Hub / 向导 / 书源检测 / 书源导入）里的 15 个 `com.google.android.material.button.MaterialButton` 全部替换为 `androidx.appcompat.widget.AppCompatButton`——本应用主题是 `Theme.AppCompat.DayNight.NoActionBar`（基座 0 处 MaterialButton），MaterialButton 在 inflate 时抛 `IllegalArgumentException: The style on this component requires your app theme to be Theme.MaterialComponents`，表现为「Unable to start activity」直接闪退。**这正是此前「新手引导不能正常显示」的真正原因**（向导一直崩，不是没弹）。`Widget.MaterialComponents.Button.TextButton` → `?android:attr/borderlessButtonStyle`，并补 `minWidth=0` 防止一行多按钮溢出。
- **主题色修复**：新增 `ai/ui/AiTheme.kt`，统一从 `ThemeStore`（用户自定义主题色）取色生成 drawable：发送按钮、悬浮球、用户气泡、AI 头像、chip、确认卡按钮全部运行时着色（此前用 `?attr/colorPrimary` 只能拿到静态默认色 `@color/primary`=md_light_blue_600，真机表现为「顶栏棕、悬浮球浅蓝」）。
- `ai_item_confirm.xml` 标题去掉 🔐 emoji。
- 验收：commit `d3d672aaa0` → AI Port Build [run 34734131100](https://github.com/Arisemoss/legado-ai-2026/actions/runs/34734131100) ai/app 双 job 全绿。

## 第七批功能（线程与生命周期修复，2026-09）
真机日志暴露的 3 个必修问题，外加 1 个必然复现的隐患：

- **设置页崩溃**：`onCreatePreferences` 阶段 Fragment 还没有 View，原实现在 `initModelPicker()` 末尾调用 `refreshModels()` 并访问 `viewLifecycleOwner`，抛 `IllegalStateException: Can't access the Fragment View's LifecycleOwner`（配置 Key 后才会走到该分支，故此前未暴露）。改为 `onViewCreated` 首次刷新 + `refreshModels()` 内 `view == null` 守卫。
- **一键获取书源 NetworkOnMainThreadException**：`BookSourceHub` 的 `fetchEntries/importUrl/downloadText/scan` 均为 okHttp 同步 `execute()`，却在主线程调用。全部切 `withContext(Dispatchers.IO)`（`fetchEntries` 改为 `suspend`），新增私有 `downloadTextBlocking` 供内部复用。
- **首次对话必然崩溃（隐患）**：`AgentTaskCenter` 作用域是 `Dispatchers.Main.immediate`，而 `AgentRuntime` 内部是阻塞式 HTTP（`OpenAIClient.execute()`），第一条消息就会 `NetworkOnMainThreadException`。现在 `runtime.execute` 整体包在 `withContext(Dispatchers.IO)` 中；事件回流走 StateFlow、`ApprovalBus` 本身线程安全，不受影响。
- **设置页「测试连接」同问题**：`OpenAIClient.complete` 在 `viewLifecycleOwner.lifecycleScope`（主线程）执行，异常被 `runCatching` 吞成「连接失败」。改 `withContext(Dispatchers.IO)`。
- **向导体验**：选择服务商后自动进入下一步；获取模型失败时回退到预设模型并放行到选模型步；完成时若模型名为空兜底写入服务商首个预设模型。
- 验收：commit `3e7fb508c6` → AI Port Build [run 34735570518](https://github.com/Arisemoss/legado-ai-2026/actions/runs/34735570518) ai/app 双 job 全绿。

## 第八批功能（两份审计报告修复，2026-09）
依据《legado-ai-2026 代码审计与全面分析报告》与《AI 层专项深度审计报告》（快照 master @ d37db9b）逐条修复。

### AI 层（P0/P1/P2）
- **A-1 Key 明文展示**：设置页不再显示 Key 的任何字符（尾 4 位是真实熵），只显示状态；新增 `AiKeyStore.isConfigured()/storageMode()`，`getApiKey()` 不再被 UI 调用。
- **A-2 日志脱敏**：`AiLog.log()` 内置 `scrub()` 兜底（sk-* / Bearer / api_key=…），把「调用方约定」升级为「机制」；`AiLogActivity` 分享前弹警示（日志含对话内容与工具参数）。
- **A-3 XML 实体解码**：经核实为**误报**——当前 HEAD 的 `unescapeXml()` 已是 `&lt;`/`&gt;` 正确实现（git 历史中从未出现恒等替换）。仍补了回归测试锁定行为。
- **A-4 确认单槽覆盖**：`ToolContext.onConfirmRequested` 由 `StateFlow` 单槽改为 `MutableSharedFlow(replay=0, extraBufferCapacity=8)` 队列语义；`ApprovalBus` 单槽改 `ConcurrentHashMap` 多槽；`AgentHubViewModel` 用 token 集合替代单值，支持多张确认卡并存。
- **A-5 明文回退可见化**：`AiKeyStore.storageMode()` 暴露真实存储方式，设置页在回退明文时显示「⚠️ 当前为明文存储」，不再谎称已加密。
- **A-6 下载三无**：`DefaultAppController.downloadText()` 与 `BookSourceHub.downloadTextBlocking()` 增加严格 scheme 白名单（仅 http/https）、独立超时 client（10/15/30s）、2MB 流式限长，防 SSRF 与 OOM。
- **B-1 token 预算**：`AiPlatform` 不再硬编码 16_000，改读配置；新增设置项「单轮 token 预算」（`ai_max_tokens`，默认 16000）；预算耗尽提示改为指向该设置项。
- **B-2 中文 token 估算**：新增 `estimateTokens()`（CJK 1 token/字，其余 1/4 字符），替代低估约 3 倍的 `len/3`。
- **B-3 重试分类**：`TOOL_FAILED` 改为不可重试；`ToolExecutor` 仅对 `IOException` 用 `NETWORK_UNAVAILABLE`（可重试），确定性异常不再空转。
- **B-4 seq 竞态**：`ConversationService.append()` 的 `maxSeq` 读 + `insert` 包进 `appDb.withTransaction {}`。

### 继承自基线的问题
- **H-1 签名密钥**：`.github/workflows/legado.jks`（上游正式密钥）已从仓库移除，`test.yml` 改用 Secrets（`RELEASE_KEY_STORE` 等，与 `release.yml` 同名）且未配置时跳过签名；`.gitignore` 补 `*.jks`/`*.keystore`/`key.jks`。**注意：密钥仍存在于 git 历史中，删除不足以撤销，需在 GitHub 侧轮换**。
- **H-2 明文流量**：保留书源所需的全局 `base-config`，但对 11 个 AI 服务商域名新增 `<domain-config cleartextTrafficPermitted="false">`，API Key/对话内容不允许走明文；本地推理（127.0.0.1）不受影响。
- **H-4 Provider 无鉴权**：`ReaderProvider` 增加访问开关（`allow_external_api`，**默认关闭**），未开启时抛 `SecurityException`；入口在「我的 → 其他设置 → 允许外部 App 访问数据」。这是行为变更：第三方集成需用户显式开启。
- **M-2**：`BookSourceHub.DEFAULT_PAGE` 改 HTTPS（2026-09 实测 https 返回 200）。
- **M-3**：`import_replace_rules` 的 URL 导入现受 scheme 白名单与 2MB 上限约束。
- **M-5**：搜索去重由「仅书名」改为「书名+来源」，避免同名不同源的书被误杀。
- **M-8**：`ModelManager` 兜底默认值由 OpenAI 改为 DeepSeek（与首启向导默认服务商一致）。

### 新增单测
- `ai/log/AiLogScrubTest`（5 例，脱敏机制）
- `TextToolCallParserTest` 增补 `&lt;`/`&gt;` 实体解码回归
- `ApprovalBusTest` 增补「多 token 互不覆盖」并发用例
- 验收：commit `d7eac17306` → AI Port Build [run 34732482928](https://github.com/Arisemoss/legado-ai-2026/actions/runs/34732482928) ai/app 双 job 全绿。
- 验收：commit `1a91244ede` → AI Port Build [run 34730836575](https://github.com/Arisemoss/legado-ai-2026/actions/runs/34730836575) 双 job 全绿。
