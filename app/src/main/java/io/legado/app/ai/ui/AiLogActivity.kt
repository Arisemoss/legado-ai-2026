package io.legado.app.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.ai.log.AiLog
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiLogBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 运行日志页（移植：ViewBinding 改写）
 * 实时展示当次会话日志（模型请求/流式/工具/错误），支持复制、清空、分享完整日志文件。
 */
class AiLogActivity : BaseActivity<ActivityAiLogBinding>() {

    override val binding by viewBinding(ActivityAiLogBinding::inflate)

    companion object {
        /** 渲染上限，避免 TextView 过大卡顿；完整内容走「分享」 */
        private const val MAX_RENDER_LINES = 500
    }

    private val jobs = ArrayList<Job>()
    private var lastText: String? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("清空全部 AI 日志？（内存与文件都会清除）")
                .setPositiveButton("清空") { _, _ ->
                    AiLog.clear()
                    lastText = null
                    binding.tvLog.text = ""
                    showToast("已清空")
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.btnCopy.setOnClickListener {
            val text = binding.tvLog.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai_log", text))
            showToast("已复制 ${text.lines().size} 行")
        }
        binding.btnShare.setOnClickListener { shareFullFile() }

        // 实时刷新（1s 轮询内存缓冲；内容未变化时不重设文本，保持可选中/滚动位置）
        jobs += lifecycleScope.launch {
            while (isActive) {
                render()
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    private fun render() {
        val entries = AiLog.snapshot()
        if (entries.isEmpty()) {
            if (lastText != "") {
                lastText = ""
                binding.tvLog.text = "暂无日志"
            }
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val shown = entries.takeLast(MAX_RENDER_LINES)
        val sb = SpannableStringBuilder()
        if (entries.size > shown.size) {
            sb.append("…（仅显示最近 ${shown.size} 条，完整日志请点右上角分享）\n\n")
        }
        for (e in shown) {
            val color = when (e.level) {
                AiLog.L_D -> ContextCompat.getColor(this, R.color.ai_text_sub)
                AiLog.L_W -> ContextCompat.getColor(this, R.color.ai_warn_text)
                AiLog.L_E -> ContextCompat.getColor(this, R.color.ai_error_text)
                else -> ContextCompat.getColor(this, R.color.ai_text_main)
            }
            val line = "${fmt.format(Date(e.time))} ${e.level}/${e.tag}: ${e.message}\n"
            val start = sb.length
            sb.append(line)
            sb.setSpan(
                ForegroundColorSpan(color), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val text = sb.toString()
        if (text == lastText) return
        // 用户是否停在底部附近：是则跟随滚动，否则保持阅读位置
        val atBottom = binding.svLog.scrollY + binding.svLog.height >= binding.tvLog.height - 80
        lastText = text
        binding.tvLog.text = sb
        if (atBottom) binding.svLog.post { binding.svLog.fullScroll(View.FOCUS_DOWN) }
    }

    /** 分享完整日志文件内容（截取尾部，规避 Binder 1MB 限制） */
    private fun shareFullFile() {
        var text = AiLog.fileText()
        if (text.isBlank()) {
            showToast("日志文件为空")
            return
        }
        val lines = text.lines()
        if (lines.size > 1200) {
            text = "…（前段省略，共${lines.size}行）\n" + lines.takeLast(1200).joinToString("\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "legado AI 运行日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享日志"))
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}