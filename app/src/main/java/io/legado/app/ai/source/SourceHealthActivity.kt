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
                "共 ${rows.size} 个启用书源。点「开始检测」逐个测试（并发 4、单源 12s、最多 50 个）。" +
                    "\n检测会发起真实网络请求，可能较慢；可随时返回取消。"
            updateSummary()
        }
    }

    private fun startTest() {
        job?.cancel()
        job = lifecycleScope.launch {
            binding.progress.max = rows.size.coerceAtMost(50)
            binding.progress.progress = 0
            binding.tvProgress.visibility = View.VISIBLE
            rows.forEach { it.st = St.TESTING }
            adapter.notifyDataSetChanged()
            val results = SourceHealth.testAll(rows.map { it.source }) { done, total ->
                binding.progress.progress = done
                binding.tvProgress.text = "检测中 $done / $total"
            }
            results.forEachIndexed { i, r ->
                rows.getOrNull(i)?.let { row ->
                    row.st = if (r.ok) St.OK else St.BAD
                    row.note = if (r.ok) "${r.latencyMs}ms" else r.reason
                }
            }
            adapter.notifyDataSetChanged()
            binding.tvProgress.visibility = View.GONE
            updateSummary()
        }
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
