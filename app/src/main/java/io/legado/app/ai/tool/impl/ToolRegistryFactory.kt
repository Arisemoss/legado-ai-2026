package io.legado.app.ai.tool.impl

import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.tool.ToolRegistry

/**
 * 装配全部工具并注入 bridge 领域桥。供 AiPlatform 初始化注册表用。
 */
fun buildRegistry(bridge: AiBridge): ToolRegistry {
    val r = ToolRegistry()
    // 选书
    r.register(SearchBooksTool(bridge.bookFetcher))
    r.register(RecommendBooksTool(bridge.bookFetcher))
    // 读书
    r.register(ReadChapterTool(bridge))
    r.register(SummarizeChapterTool(bridge))
    r.register(PlotRecapTool(bridge))
    // 懂书
    r.register(ExplainTextTool())
    r.register(AnalyzeCharactersTool(bridge))
    r.register(AnalyzeThemeTool(bridge))
    // 书源
    r.register(AnalyzeBookSourceTool(bridge.sourceAnalyzer))
    r.register(GetSourceRulesTool(bridge.sourceAnalyzer))
    r.register(ListBookSourcesTool(bridge.sourceAnalyzer))
    r.register(GetSourceStatsTool(bridge.sourceAnalyzer))
    r.register(TestBookSourceTool(bridge.sourceAnalyzer))
    r.register(SuggestSourceFixTool(bridge.sourceAnalyzer, bridge.sourceRuleWriter))
    // 书架
    r.register(ListShelfTool(bridge))
    r.register(OpenBookTool(bridge))
    r.register(RemoveBookTool(bridge))
    r.register(OpenSearchTool(bridge))
    r.register(ShowBookshelfTool(bridge))
    // 书源控制
    r.register(SetSourceEnabledTool(bridge))
    // 设置
    r.register(GetSettingTool(bridge))
    r.register(SetSettingTool(bridge))
    return r
}