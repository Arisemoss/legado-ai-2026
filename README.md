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

