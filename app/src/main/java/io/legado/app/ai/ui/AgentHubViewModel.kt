package io.legado.app.ai.ui

import io.legado.app.App
import io.legado.app.ai.AiPlatform
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.AiProviderPresets
import io.legado.app.ai.model.AiModelConfig
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.ToolEvent
import io.legado.app.ai.runtime.AgentResult
import io.legado.app.ai.runtime.AgentResultState
import io.legado.app.ai.runtime.AgentTaskCenter
import io.legado.app.ai.runtime.ConversationService
import io.legado.app.ai.runtime.SystemPromptBuilder
import io.legado.app.ai.skill.SkillRegistry
import io.legado.app.ai.tool.AiPreset
import io.legado.app.ai.tool.ConfirmRequest
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.AiSession
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hub 中的一条消息行。key 用于就地更新（工具卡片按 callId 合并）。 */
sealed class ChatRow {
    abstract val key: String

    /** 普通文本：role = user | assistant */
    data class Msg(
        override val key: String,
        val role: String,
        val content: String,
        val time: Long = System.currentTimeMillis()
    ) : ChatRow()

    /** 工具调用卡片 */
    data class ToolCard(
        override val key: String,
        val name: String,
        val argsPreview: String,
        val phase: String,
        val detail: String?,
        val elapsedMs: Long
    ) : ChatRow()

    /** 错误/状态提示条 */
    data class ErrorRow(override val key: String, val message: String) : ChatRow()

    /** 写操作二次确认卡 */
    data class Confirm(
        override val key: String,
        val token: String,
        val proposalText: String,
        val decided: Boolean?
    ) : ChatRow()

    /** 思考过程折叠头（RikkaHub ChainOfThought 风格）：点击展开/收起本回合工具步骤 */
    data class Process(
        override val key: String,
        val steps: Int,
        val expanded: Boolean
    ) : ChatRow()
}

/**
 * Agent Hub 会话状态：拉通「对话 → [AgentTaskCenter] 后台任务 → 回流消息/工具卡片/确认」。
 *
 * 任务运行在进程级任务中心上——用户离开 Hub 去看书，任务照常执行并在完成后
 * 落库/发通知；重新进入 Hub 自动重新绑定到进行中任务的回流（工具卡/打字机）。
 * 会话经 [ConversationService] 持久化，重开 App 可恢复历史并续聊。
 */
class AgentHubViewModel(
    private val preset: AiPreset = AiPreset()
) {

    private fun windowSize(): Int =
        App.INSTANCE.getPrefString(PreferKey.aiSessionWindow)?.toIntOrNull() ?: 50

    private val conversation by lazy { ConversationService(window = windowSize()) }
    private val runtime get() = AiPlatform.runtime

    /** 共享工具上下文：与后台任务中心同源，保证离开页面后事件仍回流到本 VM 的轮询 */
    private val ctx get() = AgentTaskCenter.sharedCtx

    /**
     * 系统提示：native 模式只走原生函数调用；auto/text 模式额外注入
     * Operit 式文本工具协议说明，让不支持 tools 参数的模型也能调用工具。
     */
    private val systemPrompt by lazy {
        val defaultProtocol = AiModelConfig.PROTOCOL_AUTO
        val protocol =
            App.INSTANCE.getPrefString(PreferKey.aiToolProtocol, defaultProtocol) ?: defaultProtocol
        SystemPromptBuilder(
            SkillRegistry(),
            if (protocol == AiModelConfig.PROTOCOL_NATIVE) null else AiPlatform.registry
        ).build()
    }

    private var scope: CoroutineScope? = null
    private val vmJobs = ArrayList<Job>()

    val sessionId = MutableStateFlow(-1L)
    val messages = MutableStateFlow<List<ChatRow>>(emptyList())
    val statusLine = MutableStateFlow("")
    val sessions = MutableStateFlow<List<AiSession>>(emptyList())

    /** 导航通道：工具发出 AppNav 后，宿主 Activity 消费并真实跳转 */
    val navigation get() = ctx.onNavigate

    private var turns = ArrayList<Pair<String, String>>()
    private var rowSeq = 0L
    private var currentTitle = "新会话"
    private var pendingConfirmToken: String? = null

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // ---------- 状态查询（替代原 busy/typing StateFlow，与后台任务中心对齐） ----------

    fun isBusy(): Boolean = AgentTaskCenter.isBusy()

    /** 「思考中」：任务运行但尚未收到首个流式 token */
    fun isTyping(): Boolean = isBusy() && currentPartialRaw() == null

    private fun currentPartialRaw(): String? =
        ctx.onPartialText.value?.takeIf { it.isNotBlank() }

    /** 当前流式输出的累积文本；null 表示没有进行中的打字机气泡 */
    fun currentPartial(): String? = if (isBusy()) currentPartialRaw() else null

    // ---------- 生命周期 ----------

    private val taskListener = object : AgentTaskCenter.FinishListener {
        override fun onTaskFinished(sessionId: Long, prompt: String, result: AgentResult) {
            if (sessionId == this@AgentHubViewModel.sessionId.value) {
                appendAssistantResult(sessionId, prompt, result)
            }
            scope?.launch { refreshSessions() }
            ctx.onConfirmRequested.value = null
            pendingConfirmToken = null
        }
    }

    /** 绑定 UI 协程作用域，启动内部收集器；由 Activity onCreate 调用一次 */
    fun attach(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        AgentTaskCenter.addFinishListener(taskListener)

        vmJobs += scope.launch {
            while (isActive) {
                val ev = ctx.onToolEvent.value
                if (ev == null) {
                    kotlinx.coroutines.delay(80)
                    continue
                }
                mergeToolCard(ev)
                ctx.onToolEvent.value = null
            }
        }
        vmJobs += scope.launch {
            while (isActive) {
                val req = ctx.onConfirmRequested.value
                if (req != null && req.confirmToken != pendingConfirmToken) {
                    pendingConfirmToken = req.confirmToken
                    appendRow(ChatRow.Confirm(confirmKey(req), req.confirmToken, renderProposal(req), null))
                }
                kotlinx.coroutines.delay(120)
            }
        }
    }

    fun dispose() {
        // 注意：不停止后台任务——离开页面后任务继续执行（RikkaHub 式后台生成）
        AgentTaskCenter.removeFinishListener(taskListener)
        vmJobs.forEach { it.cancel() }
        vmJobs.clear()
    }

    /** 初始化：优先续接最近会话（实现跨次打开的连续对话），否则新建 */
    suspend fun init() {
        refreshStatusLine()
        refreshSessions()
        val latest = runCatching {
            App.db.aiSessionDao().getAll()
        }.getOrNull() ?: emptyList()

        val target = latest.firstOrNull()?.id ?: conversation.create()
        loadInto(target)
        refreshSessions()
    }

    /** 拉取会话列表（事件驱动：在会话增删/切换/回答完成后调用，替代高频轮询） */
    suspend fun refreshSessions() {
        runCatching { App.db.aiSessionDao().getAll() }.getOrNull()?.let { sessions.value = it }
    }

    // ---------- 会话管理 ----------

    suspend fun newSession() {
        ensureIdle()
        loadInto(conversation.create(title = "新会话"))
        refreshSessions()
    }

    suspend fun switchTo(id: Long) {
        if (id == -2L) { // 无可切换会话
            loadInto(conversation.create())
            return
        }
        ensureIdle()
        loadInto(id)
        refreshSessions()
    }

    suspend fun deleteSession(id: Long) {
        conversation.delete(id)
        if (id == sessionId.value) {
            val next = sessions.value.filter { it.id != id }.firstOrNull()?.id
                ?: conversation.create()
            loadInto(next)
        }
        refreshSessions()
    }

    suspend fun clearCurrentMessages() {
        conversation.delete(sessionId.value)
        loadInto(conversation.create(title = currentTitle))
        refreshSessions()
    }

    private suspend fun loadInto(sid: Long) {
        sessionId.value = sid
        ctx.sessionId = sid
        ctx.stopRequested.value = false
        ctx.onConfirmRequested.value = null
        ctx.onToolEvent.value = null
        ctx.onPartialText.value = null
        pendingConfirmToken = null
        currentTitle = sessions.value.find { it.id == sid }?.title ?: "新会话"

        val loaded = conversation.loadChat(sid)
        // 重建 (user, assistant) 轮次对，跳过工具内部消息
        val t = ArrayList<Pair<String, String>>()
        var pendingUser: String? = null
        for (m in loaded) {
            if (m.role == "user" && !m.content.isNullOrBlank()) {
                pendingUser?.let { t.add(it to "") }
                pendingUser = m.content
            } else if (m.role == "assistant" && m.toolCalls.isNullOrEmpty() && !m.content.isNullOrBlank()) {
                t.add((pendingUser ?: "") to m.content!!)
                pendingUser = null
            }
        }
        pendingUser?.let { t.add(it to "") }
        turns = t

        val rows = loaded.mapIndexedNotNull { idx, m ->
            when {
                m.role == "user" && !m.content.isNullOrBlank() ->
                    ChatRow.Msg("m$idx", "user", m.content!!, m.createdAt)
                m.role == "assistant" && m.toolCalls.isNullOrEmpty() && !m.content.isNullOrBlank() ->
                    ChatRow.Msg("m$idx", "assistant", m.content!!, m.createdAt)
                else -> null
            }
        }
        messages.value = rows
    }

    // ---------- 发送与停止 ----------

    fun send(text: String) {
        if (text.isBlank() || AgentTaskCenter.isBusy()) return
        val sid = sessionId.value
        if (sid <= 0) return
        AiLog.i("Hub", "用户发送: \"${text.take(100)}\" (session=$sid)")
        appendRow(ChatRow.Msg(nextKey(), "user", text))
        maybeRename(sid, text)

        val history = turns.flatMap { (u, a) ->
            listOf(ChatMessage("user", u), ChatMessage("assistant", a))
        }
        val started = AgentTaskCenter.start(sid, text, history, systemPrompt, preset)
        if (!started) {
            appendRow(ChatRow.ErrorRow(nextKey(), "已有任务进行中"))
        }
    }

    private fun appendAssistantResult(sid: Long, prompt: String, result: AgentResult) {
        // 结果已由任务中心落库，这里仅做 UI 行与轮次记忆
        val answer = result.answer.ifBlank {
            when (result.state) {
                AgentResultState.STOPPED -> "已停止。"
                else -> "（无回复）"
            }
        }
        appendRow(ChatRow.Msg(nextKey(), "assistant", answer))
        turns.add(prompt to answer)

        when (result.state) {
            AgentResultState.STOPPED -> appendRow(ChatRow.ErrorRow(nextKey(), "⏹ 已手动停止本轮回答"))
            AgentResultState.BUDGET_EXCEEDED ->
                appendRow(ChatRow.ErrorRow(nextKey(), "⚠️ 达到预算上限（轮数/token），回答被截断。可在设置中调大最大轮数"))
            AgentResultState.ERROR ->
                appendRow(ChatRow.ErrorRow(nextKey(), result.answer))
            AgentResultState.DONE -> Unit
        }
    }

    fun stop() {
        AgentTaskCenter.stop()
    }

    /** 确认卡的同意/拒绝入口 */
    fun approve(token: String, approved: Boolean) {
        runtime.approve(token, approved)
        // 更新确认卡为已决策态
        messages.value = messages.value.map {
            if (it is ChatRow.Confirm && it.token == token) it.copy(decided = approved) else it
        }
        ctx.onConfirmRequested.value = null
        pendingConfirmToken = null
    }

    private fun ensureIdle() {
        // 切换/删除会话前必须终止进行中的任务，防止写入目标之外的会话
        if (AgentTaskCenter.isBusy()) {
            stop()
        }
    }

    // ---------- 辅助 ----------

    fun refreshStatusLine() {
        AiPlatform.syncConfig()
        val cfg = AiPlatform.config
        statusLine.value = if (cfg == null || cfg.apiKey.isBlank() && needsKey(cfg.baseUrl)) {
            "未配置模型 · 点右上角 ⚙ 设置"
        } else {
            val provider = AiProviderPresets.byBaseUrl(cfg.baseUrl)?.label ?: "自定义接口"
            "${cfg.name} · $provider"
        }
    }

    private fun needsKey(baseUrl: String): Boolean =
        AiProviderPresets.byBaseUrl(baseUrl)?.needsKey ?: true

    private fun maybeRename(sid: Long, text: String) {
        if (currentTitle != "新会话") return
        currentTitle = text.take(16)
        scope?.launch {
            runCatching { conversation.rename(sid, currentTitle) }
            refreshSessions()
        }
    }

    private fun nextKey(): String = "r${rowSeq++}"

    private fun confirmKey(req: ConfirmRequest) = "cf_${req.confirmToken.takeLast(8)}"

    private fun renderProposal(req: ConfirmRequest): String =
        req.proposal.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            .take(600)

    private fun appendRow(row: ChatRow) {
        messages.value = messages.value + row
    }

    /** 同一 callId 的工具事件合并为一张卡片，仅推进状态 */
    private fun mergeToolCard(ev: ToolEvent) {
        val list = messages.value.toMutableList()
        val existingIdx = list.indexOfLast { it.key == ev.callId && it is ChatRow.ToolCard }
        val card = ChatRow.ToolCard(
            key = ev.callId,
            name = ev.toolName,
            argsPreview = ev.argsPreview,
            phase = ev.phase,
            detail = ev.detail,
            elapsedMs = ev.elapsedMs
        )
        if (existingIdx >= 0) list[existingIdx] = card else list.add(card)
        messages.value = list
    }

    fun formatTime(ts: Long): String = timeFmt.format(Date(ts))
}
