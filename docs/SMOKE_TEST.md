# 真机冒烟清单（AI 增强版 · 2026 基线移植）

## 安装
1. 打开 Actions 最近一次成功的 `AI Port Build` → Artifacts → 下载 `ai-debug-apk`。
2. 解压得到 `legado_ai_<版本>.apk`（debug 未签名/或 CI 签名取决于 Secrets），执行：
   `adb install -r legado_ai_*.apk`（包名 `io.legado.ai.debug`，可与官方版共存）。

## 配置（首次）
1. 打开 App → 底部「我的」→ **AI 智能助手** → 进入 AI 设置页。
2. 选服务商预设（如 DeepSeek）→ 自动填 Base URL/模型；粘贴 API Key；点 **测试连接** 应显示 ✅。
3. 返回 → 阅读页（任意打开一本书）→ 点右下角 **悬浮球**，或底部菜单 **AI** 项 → 进入 AI Hub。

## 用例
| # | 操作 | 期望 |
|---|------|------|
| 1 | 发送「你好」 | 流式打字机输出（若开启流式），无报错 |
| 2 | 发送「帮我在书源里找《诡秘之主》，并加入书架」 | 触发 `search_books` 工具卡（执行中→完成），返回书名/来源 |
| 3 | 「总结当前正在读的这一章」 | `summarize_chapter`/`read_chapter` 工具卡 + 章节摘要 |
| 4 | 「检测我的书源哪些失效了」 | `test_book_source` 工具卡 + 诊断结果 |
| 5 | 让 AI 移除书架某本书（写操作） | 出现**确认卡**（🔐 待确认）→ 点「同意」才真正移除；点「拒绝」则回填 NO_PERMISSION |
| 6 | Hub 顶栏 🐛 | 打开运行日志页，可见请求/流式/工具/错误记录，支持复制/分享 |
| 7 | 会话管理（顶栏会话按钮） | 新建/切换/删除会话，重启 App 后会话仍在 |

## 故障排查
- 无悬浮球：确认设置里未关闭该功能（当前实现为默认显示；位置记忆键 ai_float_ball_side/ai_float_ball_y_ratio）。
- 工具不触发：检查「工具调用协议」设为 auto（默认）；不支持函数调用的服务商可切 text。
- 正文抓取失败：确认书源可用；桥层对 2026 API 为最小适配，正文走 `WebBook.getContentAwait`。

## 已知限制（本轮）
- AI Hub 入口仅：阅读页悬浮球 / 阅读菜单 AI 项 / 我的页设置入口（无独立桌面图标）。
- 设置页测试连接仅验证 /chat/completions 连通性。
- 写操作白名单：remove_book / suggest_source_fix / set_source_enabled / set_setting。
