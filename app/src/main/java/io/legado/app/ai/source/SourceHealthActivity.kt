package io.legado.app.ai.source

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.ui.AiLogActivity
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

    /** 右上角菜单：运行日志（检测异常/失效原因都在这里，便于排障与反馈） */
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_health, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_ai_logs) {
            startActivity(Intent(this, AiLogActivity::class.java))
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun load() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { SourceHealth.allSources() }
            rows.clear()
            rows.addAll(list.map { Row(it) })
            adapter.notifyDataSetChanged()
            binding.tvIntro.text =
                "共 ${rows.size} 个启用书源。「开始检测」分批检测全部（并发 ${SourceHealth.DEFAULT_CONCURRENCY}、" +
                    "单源 10s），结果实时回填，失败项自动重试一次；检测中可点「停止检测」。\n" +
                    "检测在后台线程执行，界面可正常滚动/停止；右上角可查看运行日志。"
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
            // 把还停在「检测中」的行复位，避免看起来像卡死
            rows.filter { it.st == St.TESTING }.forEach { it.st = St.IDLE; it.note = "已取消" }
            adapter.notifyDataSetChanged()
            updateSummary()
            val done = rows.count { it.st == St.OK || it.st == St.BAD }
            binding.tvProgress.text = "已停止：已检测 $done / ${rows.size}（可再次点「开始检测」继续）"
            AiLog.w("SourceHealth", "用户停止检测：已检测 $done/${rows.size}")
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

            AiLog.i("SourceHealth", "开始检测 ${rows.size} 个书源（并发 ${SourceHealth.DEFAULT_CONCURRENCY}）")

            // 第一遍：全部（结果逐条实时回填，不再等全部跑完）
            val targets = rows.map { it.source }
            SourceHealth.testAll(
                sources = targets,
                concurrency = SourceHealth.DEFAULT_CONCURRENCY,
                onProgress = { done, total ->
                    binding.progress.progress = done
                    binding.tvProgress.text = "检测中 $done / $total · ${liveStat()}"
                },
                onResult = { index, r -> bindResult(rows, index, r) }
            )

            // 第二遍：失败项自动重试一次（网络抖动导致的失败很常见）
            val failed = rows.filter { it.st == St.BAD }
            if (failed.isNotEmpty() && isActive) {
                AiLog.i("SourceHealth", "失败项重试 ${failed.size} 个")
                failed.forEach { it.st = St.TESTING }
                adapter.notifyDataSetChanged()
                binding.progress.progress = 0
                SourceHealth.testAll(
                    sources = failed.map { it.source },
                    concurrency = SourceHealth.DEFAULT_CONCURRENCY,
                    onProgress = { done, total ->
                        binding.progress.progress = done
                        binding.tvProgress.text = "重试失败项 $done / $total"
                    },
                    onResult = { index, r -> bindResult(failed, index, r) }
                )
            }

            adapter.notifyDataSetChanged()
            binding.btnTest.text = "开始检测"
            val ok = rows.count { it.st == St.OK }
            val bad = rows.count { it.st == St.BAD }
            binding.tvProgress.text = "检测完成：可用 $ok · 失效 $bad（共 ${rows.size}）"
            updateSummary()
            AiLog.i("SourceHealth", "检测完成：可用 $ok · 失效 $bad（共 ${rows.size}）")
        }
    }

    /** 单条结果实时回填（索引对齐）；失败原因写日志便于排障 */
    private fun bindResult(targets: List<Row>, index: Int, r: SourceHealth.Result) {
        val row = targets.getOrNull(index) ?: return
        row.st = if (r.ok) St.OK else St.BAD
        row.note = if (r.ok) "${r.latencyMs}ms" else r.reason
        if (!r.ok) {
            AiLog.w("SourceHealth", "失效《${row.source.bookSourceName}》: ${r.reason.orEmpty().take(120)}")
        }
        adapter.notifyItemChanged(index)
        updateSummary()
    }

    /** 进度行里的实时统计 */
    private fun liveStat(): String {
        val ok = rows.count { it.st == St.OK }
        val bad = rows.count { it.st == St.BAD }
        return "可用 $ok · 失效 $bad"
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
