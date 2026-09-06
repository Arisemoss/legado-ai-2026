package io.legado.app.ai.skill

/**
 * 声明式小说技能。每个技能绑定一组工具 id，便于按开关整体启用/禁用。
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val category: String,     // 选书 | 读书 | 懂书 | 书源
    val description: String,
    val toolIds: List<String>
)

object NovelSkills {
    val all = listOf(
        SkillDefinition("xs", "选书", "选书", "跨书源搜索、按作者/类型/关键词找书、相似书/同作者推荐", listOf("search_books", "recommend_books")),
        SkillDefinition("ds", "读书", "读书", "章节正文读取、当前章节总结、情节梳理回顾", listOf("read_chapter", "summarize_chapter", "plot_recap")),
        SkillDefinition("dz", "懂书", "懂书", "人物关系与性格、背景设定、专有名词用典、主题伏笔", listOf("analyze_characters", "explain_text", "analyze_theme")),
        SkillDefinition("sy", "书源", "书源", "连通测试、规则诊断、规则修复建议(需确认)", listOf("test_book_source", "analyze_book_source", "suggest_source_fix"))
    )
}