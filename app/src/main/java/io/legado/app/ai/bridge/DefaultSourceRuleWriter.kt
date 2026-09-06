package io.legado.app.ai.bridge

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.legado.app.App
import io.legado.app.ai.log.AiLog
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SourceRuleWriter] 默认实现：把用户确认后的变更合并进书源并写库。
 *
 * 安全边界：
 * - 仅允许白名单字段——顶层请求入口与五组规则的子字段（`ruleSearch.bookList` 等点号路径），
 *   防止越权改动 bookSourceUrl/enabled 等敏感属性；
 * - 只做字段级覆盖，不做删除/新增来源；
 * - 本类只能由写工具在 pending_confirm 获批后调用。
 */
class DefaultSourceRuleWriter : SourceRuleWriter {

    companion object {
        /** 可写的顶层字段 */
        private val TOP_FIELDS = setOf(
            "bookSourceName", "bookSourceGroup", "bookUrlPattern",
            "searchUrl", "exploreUrl", "header", "loginUrl"
        )

        /** 可写的规则组前缀 */
        private val RULE_GROUPS = setOf(
            "ruleExplore", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent"
        )
    }

    override suspend fun apply(url: String, changes: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = App.db.bookSourceDao()
                val source = dao.getBookSource(url) ?: return@withContext false
                val gson = Gson()
                val root = JsonParser.parseString(gson.toJson(source)).asJsonObject
                var applied = 0
                for ((key, value) in changes) {
                    val dot = key.indexOf('.')
                    val group = if (dot > 0) key.substring(0, dot) else key
                    val field = if (dot > 0) key.substring(dot + 1) else null
                    when {
                        field == null && group in TOP_FIELDS -> {
                            root.addProperty(group, value)
                            applied++
                        }
                        field != null && group in RULE_GROUPS &&
                            root.has(group) && !root.get(group).isJsonNull -> {
                            root.getAsJsonObject(group).addProperty(field, value)
                            applied++
                        }
                        else -> AiLog.w("SourceWriter", "忽略越权/未知变更项: $key")
                    }
                }
                if (applied == 0) return@withContext false
                val merged = gson.fromJson(root, BookSource::class.java)
                // 书源 URL 是主键锚点，合并后强制保持不变
                merged.bookSourceUrl = source.bookSourceUrl
                dao.update(merged)
                AiLog.i("SourceWriter", "已应用 ${applied}/${changes.size} 项变更到 $url")
                true
            }.getOrElse { e ->
                AiLog.e("SourceWriter", "写回失败: ${e.localizedMessage}", e)
                false
            }
        }
}
