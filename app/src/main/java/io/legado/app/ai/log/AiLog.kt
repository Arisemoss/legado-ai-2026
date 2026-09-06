package io.legado.app.ai.log

import android.content.Context
import android.util.Log
import io.legado.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * AI 模块运行日志：内存环形缓冲（当次会话实时查看）+ 文件持久化（跨启动排障/导出分享），
 * 同时镜像到 logcat（tag 前缀 "AI/"）。
 *
 * - 页面入口：AI 助手顶栏 🐛 → [io.legado.app.ai.ui.AiLogActivity]
 * - 文件位置：filesDir/logs/ai.log（超 512KB 轮转为 ai.log.1）
 * - 敏感信息约定：API Key 等必须经 [mask] 脱敏后再记录
 */
object AiLog {

    const val L_D = "D"
    const val L_I = "I"
    const val L_W = "W"
    const val L_E = "E"

    class Entry(val time: Long, val level: String, val tag: String, val message: String)

    private const val MAX_MEMORY_ENTRIES = 800
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-log").apply { isDaemon = true }
    }

    /** App.onCreate 时调用一次：初始化日志文件并写入会话头 */
    fun attach(context: Context) {
        io.execute {
            runCatching {
                val dir = File(context.filesDir, "logs").apply { mkdirs() }
                val f = File(dir, "ai.log")
                if (f.length() > MAX_FILE_BYTES) {
                    File(dir, "ai.log.1").delete()
                    f.renameTo(File(dir, "ai.log.1"))
                }
                logFile = f
                f.appendText("\n===== 会话开始 ${fmt.format(Date())} · v${BuildConfig.VERSION_NAME} =====\n")
            }
        }
    }

    fun d(tag: String, msg: String) = log(L_D, tag, msg)
    fun i(tag: String, msg: String) = log(L_I, tag, msg)
    fun w(tag: String, msg: String) = log(L_W, tag, msg)

    fun e(tag: String, msg: String, tr: Throwable? = null) =
        log(L_E, tag, if (tr == null) msg else "$msg · ${tr.javaClass.simpleName}: ${tr.message}")

    private fun log(level: String, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, msg.take(2000))
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_MEMORY_ENTRIES) buffer.pollFirst()
        }
        val fullTag = "AI/$tag"
        when (level) {
            L_D -> Log.d(fullTag, msg)
            L_W -> Log.w(fullTag, msg)
            L_E -> Log.e(fullTag, msg)
            else -> Log.i(fullTag, msg)
        }
        val f = logFile ?: return
        io.execute {
            runCatching {
                f.appendText("${fmt.format(Date(entry.time))} $level/$tag: ${entry.message}\n")
            }
        }
    }

    /** 当次会话日志快照（旧→新） */
    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    /** 清空内存缓冲与日志文件 */
    fun clear() {
        synchronized(lock) { buffer.clear() }
        io.execute { runCatching { logFile?.writeText("") } }
    }

    /** 完整文件内容（含历史会话），用于导出 */
    fun fileText(): String = runCatching { logFile?.readText() }.getOrNull().orEmpty()

    /** 敏感信息脱敏：保留首尾各4字符 */
    fun mask(secret: String?): String {
        if (secret.isNullOrBlank()) return "(空)"
        return if (secret.length <= 8) "***"
        else secret.take(4) + "***" + secret.takeLast(4)
    }
}
