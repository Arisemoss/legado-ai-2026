package io.legado.app.ai.source

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivitySourceHealthBinding
import io.legado.app.databinding.ItemSourceHubBinding
import io.legado.app.utils.GSON
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书源可用性检测与清理：批量测试 → 勾选失效项 → 导出备份 → 确认删除。
 */
class SourceHealthActivity : BaseActivity<ActivitySourceHealthBinding>() {

    override val binding by viewBinding(ActivitySourceHealthBinding::inflate)

    private enum class St { IDLE, TESTING, OK, BAD, DELETED }

    private class Row(val source: BookSource) {
        var selected = false
        var st = St.IDLE
        var note: String? = null
    }

    private val rows = ArrayList<Row>()
    private lateinit var adapter: Adapter
    private var job: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setOnClickListener { finish() }
        adapter = Adapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnTest.setOnClickListener { startTest() }
        binding.btnSelectBad.setOnClickListener {
            rows.forEach { it.selected = it.st == St.BAD }
            adapter.notifyDataSetChanged()
            updateSummary()
        }
        binding.btnBackup.setOnClickListener { exportBackup(rows.filter { it.selected }) }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        load()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { SourceHealth.allSources() }
            rows.clear()
            rows.addAll(list.map { Row(it) })
            adapter.notifyDataSetChanged()
            binding.tvIntro.text =
                "共 ${rows.size} 个启用书源。「开始检测」会分批检测全部（并发 4、单源 12s），" +
                    "失败项自动重试一次；检测中可点「停止检测」。\n会发起真实网络请求，源较多时需要几分钟。"
            updateSummary()
        }
    }

    /**
     * 检测全部书源（真机反馈：原先固定只测前 50 个）。
     * 流程：全部检测 → 失败项自动重试一次 → 汇总；运行中按钮变为「停止检测」。
     */
    private fun startTest() {
        if (job?.isActive == true) {
            job?.cancel()
            binding.btnTest.text = "开始检测"
            binding.tvProgress.text = "已停止（可再次点「开始检测」继续全量检测）"
            return
        }
        job = lifecycleScope.launch {
            binding.btnTest.text = "停止检测"
            binding.progress.max = rows.size.coerceAtLeast(1)
            binding.progress.progress = 0
            binding.tvProgress.visibility = View.VISIBLE
            rows.forEach { it.st = St.TESTING; it.note = null }
            adapter.notifyDataSetChanged()
            updateSummary()

            // 第一遍：全部
            val targets = rows.map { it.source }
            val pass1 = SourceHealth.testAll(targets) { done, total ->
                binding.progress.progress = done
                binding.tvProgress.text = "检测中 $done / $total"
            }
            applyResults(rows, pass1)

            // 第二遍：失败项自动重试一次（网络抖动导致的失败很常见）
            val failed = rows.filter { it.st == St.BAD }
            if (failed.isNotEmpty() && isActive) {
                failed.forEach { it.st = St.TESTING }
                adapter.notifyDataSetChanged()
                binding.progress.progress = 0
                val pass2 = SourceHealth.testAll(failed.map { it.source }) { done, total ->
                    binding.progress.progress = done
                    binding.tvProgress.text = "重试失败项 $done / $total"
                }
                applyResults(failed, pass2)
            }

            adapter.notifyDataSetChanged()
            binding.btnTest.text = "开始检测"
            val ok = rows.count { it.st == St.OK }
            val bad = rows.count { it.st == St.BAD }
            binding.tvProgress.text = "检测完成：可用 $ok · 失效 $bad（共 ${rows.size}）"
            updateSummary()
        }
    }

    /** 把一批检测结果回填到对应行（索引对齐） */
    private fun applyResults(targets: List<Row>, results: List<SourceHealth.Result>) {
        results.forEachIndexed { i, r ->
            targets.getOrNull(i)?.let { row ->
                row.st = if (r.ok) St.OK else St.BAD
                row.note = if (r.ok) "${r.latencyMs}ms" else r.reason
            }
        }
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun exportBackup(targets: List<Row>) {
        if (targets.isEmpty()) { toast("请先勾选要备份的书源"); return }
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = getExternalFilesDir("backup") ?: filesDir
                    val f = File(dir, "legado-ai-sources-${System.currentTimeMillis()}.json")
                    f.writeText(GSON.toJson(targets.map { it.source }))
                    f.absolutePath
                }.getOrNull()
            }
            toast(if (path != null) "已备份到：$path" else "备份失败")
        }
    }

    private fun confirmDelete() {
        val targets = rows.filter { it.selected }
        if (targets.isEmpty()) { toast("请先勾选要删除的书源"); return }
        AlertDialog.Builder(this)
            .setTitle("删除 ${targets.size} 个书源？")
            .setMessage("此操作会从书源列表移除选中项，建议先「导出备份」。\n\n" +
                targets.take(8).joinToString("\n") { "· " + it.source.bookSourceName } +
                if (targets.size > 8) "\n… 等 ${targets.size} 个" else "")
            .setPositiveButton("确认删除") { _, _ -> doDelete(targets) }
            .setNeutralButton("先备份再删") { _, _ ->
                exportBackup(targets)
                doDelete(targets)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDelete(targets: List<Row>) {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    targets.forEach { appDb.bookSourceDao.delete(it.source) }
                    targets.size
                }.getOrDefault(0)
            }
            targets.forEach { it.st = St.DELETED; it.selected = false }
            adapter.notifyDataSetChanged()
            updateSummary()
            toast("已删除 $n 个书源")
        }
    }

    private fun updateSummary() {
        val bad = rows.count { it.st == St.BAD }
        val ok = rows.count { it.st == St.OK }
        val picked = rows.count { it.selected }
        binding.tvSummary.text = "可用 $ok · 失效 $bad · 已选 $picked"
        binding.btnDelete.isEnabled = picked > 0
        binding.btnBackup.isEnabled = picked > 0
    }

    private fun statusText(r: Row): String = when (r.st) {
        St.IDLE -> "未检测"
        St.TESTING -> "检测中…"
        St.OK -> "✅ 可用"
        St.BAD -> "❌ 失效"
        St.DELETED -> "已删除"
    }

    private fun statusColor(r: Row): Int = ContextCompat.getColor(
        this,
        when (r.st) {
            St.OK -> R.color.ai_ok_text
            St.TESTING -> R.color.ai_warn_text
            St.BAD -> R.color.ai_error_text
            else -> R.color.ai_text_sub
        }
    )

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val b: ItemSourceHubBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSourceHubBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = rows[position]
            val b = holder.b
            b.cbItem.isChecked = r.selected
            b.tvTitle.text = r.source.bookSourceName
            b.tvSubtitle.text = listOfNotNull(
                r.source.bookSourceUrl.take(60),
                r.note
            ).joinToString(" · ")
            b.tvStatus.text = statusText(r)
            b.tvStatus.setTextColor(statusColor(r))
            b.root.setOnClickListener {
                if (r.st == St.TESTING || r.st == St.DELETED) return@setOnClickListener
                r.selected = !r.selected
                notifyItemChanged(position)
                updateSummary()
            }
        }
    }
}
