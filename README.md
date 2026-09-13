# [English](English.md) [中文](README.md)

[![icon_android](https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/icon_android.png)](https://play.google.com/store/apps/details?id=io.legado.play.release)
<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>

<div align="center">
<img width="125" height="125" src="https://github.com/gedoor/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>  
  
Legado / 开源阅读
<br>
<a href="https://gedoor.github.io" target="_blank">gedoor.github.io</a> / <a href="https://www.legado.top/" target="_blank">legado.top</a>
<br>
---

## 🔬 本仓库说明（2026-09 移植）

本仓库以官方 legado 2026 终点快照（hectorqin/legado，GPL-3.0）为基线，**移植自 Arisemoss/legado 的 AI 平台层**：
- AI 对话/工具层 `io.legado.app.ai`（44 文件）整体搬入；`ai` flavor（applicationId `io.legado.ai`）可与官方版共存安装。
- 数据库 v75→76 追加 `aiSessions/aiMessages`（迁移 `migration_75_76`）。
- 入口：AI Hub = `AgentHubActivity`（阅读相关入口/悬浮球仍在接入中）；配置 = `ConfigActivity`(configTag=aiConfig)。
- CI：`.github/workflows/ai-port.yml` 跑 `testAiDebugUnitTest` + `assembleAiDebug`/`assembleAppDebug`。
- 移植过程与清单：见旧仓库分支 `docs/map-report-2026-09-baseline-eec4139cd` 与 `.ai-port-2026/`（外部记录）。

> 状态：编译/单测/双变体构建绿；剩余接线项（我的页入口、阅读菜单悬浮球、AI 连接测试按钮）见代码内 TODO。

### 新增能力（2026-09 第二批）
- 🫧 **主页 + 阅读页 AI 悬浮球**：可拖拽贴边、位置记忆，设置中可关闭（`ai_float_ball_enabled`）。
- 🧠 **填入 API Key 自动获取模型**：设置页自动请求 `/models` 拉取模型列表，可下拉选择/刷新，失败回退服务商预设。
- 📥 **一键获取书源**：我的页 →「一键获取书源」，解析聚合页（默认喵公子 gx.html）深链并批量导入；AI 工具 `import_book_sources` 亦可触发（需确认）。
- ⚙️ **AI 可管理 App 设置**：新增 `list_settings` / `manage_replace_rule`（替换净化增删改启停）/ `delete_book_source`；`set_setting` 白名单扩展（主题、日夜、墨水屏、简繁、朗读语速、书架布局等）。
- 工具总数：**22 → 27**，所有写操作均为两阶段确认。

### 新增能力（2026-09 第三批）
- 📥 **书源导入页重做**：预扫描（含条数/是否已存在/可更新）→ 勾选/全选/仅新/仅可更新 → 逐项进度与状态 → 结果统计；不再是一个简陋弹窗。
- 🧭 **首启 AI 配置向导**：介绍 → 选服务商 → 填 API Key → 自动获取模型列表 → 选模型 → 完成（可跳过；仅首次进入引导一次）。
- 🎛 **AI 设置页分组重做**：服务商与凭据 / 模型 / 高级 / 外观与入口 / 工具与安全 五组，含测试连接、刷新模型、工具说明与日志入口。
- 🩺 **书源可用性检测与清理**：并发批量检测（可取消）→ 选中失效项 → 导出备份 → 确认删除；删除前自动提示备份。
- 工具总数：**28**（新增 `add_book_to_shelf`：搜书后经确认即可加入书架）。

### 新增能力（2026-09 第四批 · P1 工具增强）
- 🧰 工具总数 **28 → 32**：`batch_add_to_shelf`（批量加书架，确认后入库）、`test_sources_batch`（AI 批量测源，只读）、`import_replace_rules`（URL/JSON 导入净化规则）、`reset_setting`（单项恢复默认）。
- 所有写操作仍需在聊天里点「同意」；批量加书架会在确认卡里列出书名。

### 新增能力（2026-09 第五批 · 工具结果可操作化）
- 🖱 **工具卡片建议动作**：搜索/书架/测源/设置等工具跑完，卡片下方自动出现快捷按钮（「加书架《书名》」「阅读《书名》」「清理失效书源(3)」「打开书源管理」…）。只读动作直接跳页；**写操作只发指令给 AI，仍需再点确认卡才落库**。
- 🔎 **搜索结果字段补全**：`search_books` 现在返回 `bookUrl/origin/originName/tocUrl` 等字段，修复「搜到书却无法加入书架」的断链。
- 🧾 **写操作卡片回显终态**：确认/写入完成后卡片从「已确认，正在写入」推进到真实写入结果与耗时。
- 🧪 新增单测 `SuggestionEngineTest`（10 例，含「不产生绕过确认的写库动作」红线校验）。

### 新增能力（2026-09 第六批 · 体验修复与 UI 统一）
- 🧭 **新手引导修复**：向导改为「隐私同意后再弹」；「跳过」只记已展示（不再等于完成）；设置页新增「重新运行配置向导」；完成直接进入 AI Hub；向导页统一顶栏、可滚动、键盘不再顶掉按钮。
- 🧰 **工具清单可展开**：设置页「AI 工具清单」点开按分类列出全部工具（数量自动统计，写操作标注「需确认」），不再只弹一个 Toast。
- 🎨 **UI 统一**：AI Hub / 运行日志 / 配置向导统一为同一顶栏（主题主色 + 状态栏 insets + 返回 + 图标动作）；AI 页中性色、用户气泡、发送按钮、建议 chip 全部跟随 App 主题色与夜间模式（不再固定蓝紫 indigo）；我的页 AI 入口换星芒图标，去掉 emoji。
- 🫧 **悬浮球**：新增「显示范围」（主页与阅读页 / 仅阅读页 / 仅主页），不再在书架、我的页遮挡列表；点击不再另起任务栈。
- 🧩 其它：空态快捷 chip 末项不再被裁切；MainActivity 向导时机与 tab 切换时序修正（避免启动/返回瞬间两页叠影）。

### 修复（2026-09 第七批 · 真机崩溃与主线程网络）
- 💥 **设置页崩溃**：`AiConfigFragment` 在 `onCreatePreferences` 阶段访问 `viewLifecycleOwner` 抛 `IllegalStateException`（配好 Key 后必现）→ 首次刷新移到 `onViewCreated` 并加空视图守卫。
- 🌐 **主线程网络**：`BookSourceHub`（一键获取书源）与「测试连接」的阻塞式 okHttp 调用改到 IO 线程；并修掉**首次对话必然 `NetworkOnMainThreadException` 的隐患**（`AgentTaskCenter` 在主线程作用域内执行 Agent 循环）。
- 🧭 **向导**：选服务商后自动进入下一步；获取模型失败回退预设模型并放行；完成时兜底写入模型名。

### 修复（2026-09 第八批 · 两份审计报告）
- 🔐 **凭据与日志**：设置页不再显示 Key 片段（只显示存储方式，明文回退会明确警示）；AI 日志内置兜底脱敏，分享前弹警示。
- 🧩 **确认流与预算**：写操作确认由单槽改多槽队列（多张确认卡不再互相覆盖）；「单轮 token 预算」进入设置页；中文 token 估算修正约 3 倍。
- 🌐 **网络边界**：AI 服务商域名强制 HTTPS；工具 URL 下载增加 scheme 白名单、超时与 2MB 上限；默认书源聚合页改 HTTPS。
- 🔑 **仓库安全**：移除仓库内的签名密钥并改用 Secrets（未配置则跳过签名）；`ReaderProvider` 外部访问默认关闭（可在「其他设置」开启）。

