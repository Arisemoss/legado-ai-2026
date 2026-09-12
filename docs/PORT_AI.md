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
- 最新绿：commit `f08c1c0da` → `testAiDebugUnitTest` + `assembleAiDebug` + `assembleAppDebug` 全部 SUCCESS；产物 `ai-debug-apk`（≈30MB）。
- 单测：`ai/model/AgentErrorTest`、`ai/runtime/OpenAIClientTest`、`ai/runtime/ApprovalBusTest`、`ai/tool/TextToolCallParserTest`。

## 分支
- `master`（默认）：移植主线；`port-master`：同名备份分支。

## 待办（未完成项）
1. 阅读器内入口：`ReadMenu` 的 AI 助手按钮与阅读页悬浮球（`AIFloatBallView` 挂载）尚未接线。
2. `MainActivity` 对 `EXTRA_SELECT_TAB`（AI 导航跳书架）尚未处理。
3. AI 设置页「测试连接」按钮为占位（原实现依赖旧 ModelManager.chatCompletion）。
4. `exportSchema` 已置 true；如需提交 Room schema 快照，请在本地/CI 生成 `app/schemas/io.legado.app.data.AppDatabase/76.json` 后入库。
5. 正式签名包：配置 Secrets 后跑 release（沿用 base `RELEASE_KEY_*` 模式）。

## 已知技术债
- 桥层对 2026 API 为「最小可用」适配：搜索/正文直接映射到 `WebBook.searchBookAwait/getContentAwait`，未复刻旧版的 scope 复用与全部超时策略细节。
- `AiKeyStore` 在 API<23 或解密失败时仍回退明文（沿用旧实现）。
