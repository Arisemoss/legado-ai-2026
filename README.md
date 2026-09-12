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

