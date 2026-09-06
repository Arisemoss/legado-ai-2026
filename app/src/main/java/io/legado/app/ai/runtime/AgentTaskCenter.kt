package io.legado.app.ai.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.legado.app.App
import io.legado.app.R
import io.legado.app.ai.AiPlatform
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.ui.AgentHubActivity
import io.legado.app.constant.PreferKey
import io.legado.app.ai.tool.ToolContext
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Agent 后台任务中心（RikkaHub 式「生成不依赖页面」）。
 *
 * - 任务运行在进程级 [scope] 上，用户离开 AgentHub 去看书/切页，任务照常执行
 * - 拥有共享 [ToolContext]：工具卡片/确认请求/流式增量经同一上下文回流，
 *   Hub 重新进入时自动重新绑定到进行中的任务
 * - 完成后落库（ConversationService），若耗时较长则发系统通知，点击回到助手页
 */
object AgentTaskCenter {

    enum class State { IDLE, RUNNING, DONE, ERROR, STOPPED }

    data class Snapshot(
        val sessionId: Long = -1L,
        val prompt: String = "",
        val state: State = State.IDLE,
        val partial: String? = null,
        val answer: String = "",
        val rounds: Int = 0,
        val tokensUsed: Long = 0,
        val startedAt: Long = 0L
    )

    interface FinishListener {
        fun onTaskFinished(sessionId: Long, prompt: String, result: AgentResult)
    }

    private const val CHANNEL_ID = "channel_ai_task"
    private const val NOTIFY_ID = 2001

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    @Volatile
    var snapshot = Snapshot()
        private set

    /** 共享工具上下文：Hub 与后台任务共用同一回流通道 */
    val sharedCtx: ToolContext = ToolContext(sessionId = -1L)

    private val conversation by lazy { ConversationService(window = windowSize()) }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<FinishListener>()

    private fun windowSize(): Int =
        App.INSTANCE.getPrefString(PreferKey.aiSessionWindow)?.toIntOrNull() ?: 50

    fun isBusy(): Boolean = job?.isActive == true

    fun addFinishListener(l: FinishListener) { listeners.add(l) }
    fun removeFinishListener(l: FinishListener) { listeners.remove(l) }

    /**
     * 启动一轮任务；已有任务运行中则拒绝（返回 false）。
     * 用户消息先落库，回答完成后与结果一并落库并回调监听者。
     */
    fun start(
        sessionId: Long,
        prompt: String,
        history: List<ChatMessage>,
        systemPrompt: String,
        preset: io.legado.app.ai.tool.AiPreset = io.legado.app.ai.tool.AiPreset()
    ): Boolean {
        if (isBusy()) return false
        sharedCtx.sessionId = sessionId
        sharedCtx.preset = preset
        sharedCtx.stopRequested.value = false
        sharedCtx.onConfirmRequested.value = null
        sharedCtx.onToolEvent.value = null
        sharedCtx.onPartialText.value = null

        snapshot = Snapshot(
            sessionId = sessionId,
            prompt = prompt,
            state = State.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        job = scope.launch {
            runCatching { conversation.appendText(sessionId, "user", prompt) }

            val result = runCatching {
                AiPlatform.runtime.execute(prompt, history, sharedCtx, systemPrompt)
            }.getOrElse {
                AiLog.e("Task", "execute 异常", it)
                AgentResult(it.localizedMessage ?: "执行出错", AgentResultState.ERROR)
            }

            sharedCtx.onPartialText.value = null
            val answer = result.answer.ifBlank {
                when (result.state) {
                    AgentResultState.STOPPED -> "已停止。"
                    else -> "（无回复）"
                }
            }
            runCatching { conversation.appendText(sessionId, "assistant", answer) }

            snapshot = snapshot.copy(
                state = result.state.toCenterState(),
                answer = result.answer,
                rounds = result.rounds,
                tokensUsed = result.tokensUsed
            )
            AiLog.i("Task", "任务结束 state=${result.state} rounds=${result.rounds} tokens=${result.tokensUsed}")
            notifyCompletionIfNeeded(prompt, result)
            listeners.forEach { l ->
                runCatching { l.onTaskFinished(sessionId, prompt, result) }
            }
        }
        return true
    }

    /** 用户主动停止当前任务 */
    fun stop() {
        sharedCtx.stopRequested.value = true
    }

    private fun AgentResultState.toCenterState(): State = when (this) {
        AgentResultState.DONE -> State.DONE
        AgentResultState.ERROR -> State.ERROR
        else -> State.STOPPED
    }

    /** 耗时 >3s 的任务完成时发系统通知，点击回到助手页 */
    private fun notifyCompletionIfNeeded(prompt: String, result: AgentResult) {
        val elapsed = System.currentTimeMillis() - snapshot.startedAt
        if (elapsed < 3_000L) return
        runCatching {
            val ctx = App.INSTANCE
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "AI 任务",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, NOTIFY_ID,
                Intent(ctx, AgentHubActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val text = result.answer.ifBlank { prompt }.replace('\n', ' ').take(80)
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_search)
                .setContentTitle("AI 任务已完成")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, notification)
        }.onFailure { AiLog.e("Task", "完成通知发送失败", it) }
    }
}
