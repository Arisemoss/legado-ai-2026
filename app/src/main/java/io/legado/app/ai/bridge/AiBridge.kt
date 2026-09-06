package io.legado.app.ai.bridge

/**
 * 领域桥统一装配点。
 * 向 Agent 暴露只读读能力；写操作另行注入 [SourceRuleWriter]（须经确认）。
 */
class AiBridge(
    val bookFetcher: BookFetcher,
    val chapterReader: ChapterReader,
    val sourceAnalyzer: BookSourceAnalyzer,
    val appController: AppController,
    val sourceRuleWriter: SourceRuleWriter
)