package io.legado.app.ai.skill

/**
 * 技能注册表。按开关过滤技能集合，供 SystemPrompt 展示与工具装配。
 * MVP 阶段全量启用；阶段2后改为读取配置开关。
 */
class SkillRegistry {
    fun all(): List<SkillDefinition> = NovelSkills.all

    fun enabled(): List<SkillDefinition> = NovelSkills.all

    fun toolIds(): List<String> = enabled().flatMap { it.toolIds }.distinct()
}