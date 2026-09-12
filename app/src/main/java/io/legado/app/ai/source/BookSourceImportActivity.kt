package io.legado.app.ai.source

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivitySourceImportBinding
import io.legado.app.databinding.ItemSourceHubBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 一键获取书源（页面版）：
 * 读取聚合页 → 预扫描（数量/更新时间/是否已存在）→ 勾选 → 批量导入（进度/结果）。
 * 书源为第三方内容，请自行确认来源合法性。
 */
class BookSourceImportActivity : BaseActivity<ActivitySourceImportBinding>() {

    override val binding by viewBinding(ActivitySourceImportBinding::inflate)

    private enum class St { SCANNING, SCAN_FAIL, NEW, UPDATE, EXISTS, IMPORTING, IMPORTED, IMPORT_FAIL }

    private class Row(var item: BookSourceHub.Entry) {
        var selected = false
        var total = 0
        var newest = 0L
        var exists = false
        var canUpdate = false
        var st = St.SCANNING
        var note: String? = null
    }

    private val rows = ArrayList<Row>()
    private lateinit var adapter: Adapter
    private var scanJob: Job? = null
    private var importJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setOnClickListener { finish() }
        adapter = Adapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnSelectAll.setOnClickListener { setSelection { true } }
        binding.btnClear.setOnClickListener { setSelection { false } }
        binding.btnSelectNew.setOnClickListener { setSelection { it.st == St.NEW } }
        binding.btnSelectUpdate.setOnClickListener { setSelection { it.st == St.UPDATE } }
        binding.btnImport.setOnClickListener { startImport() }
        binding.btnHealth.setOnClickListener {
            startActivity(android.content.Intent(this, SourceHealthActivity::class.java))
        }
        load()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        importJob?.cancel()
        super.onDestroy()
    }

    private fun load() {
        scanJob?.cancel()
        rows.clear()
        binding.tvIntro.text = "正在读取聚合页…"
        scanJob = lifecycleScope.launch {
            val entriesRes = BookSourceHub.fetchEntries()
            entriesRes.fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        binding.tvIntro.text = "未在聚合页中发现可导入书源"
                        return@fold
                    }
                    binding.tvIntro.text =
                        "来源：${BookSourceHub.DEFAULT_PAGE}\n共发现 ${entries.size} 个书源集合；" +
                            "勾选后点右下角「导入选中」。书源为第三方内容，请自行确认合法性。"
                    rows.addAll(entries.map { Row(it) })
                    adapter.notifyDataSetChanged()
                    scan(entries)
                },
                onFailure = { e ->
                    binding.tvIntro.text = "读取失败：${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            )
        }
    }

    private suspend fun scan(entries: List<BookSourceHub.Entry>) = coroutineScope {
        val sem = Semaphore(4)
        var done = 0
        binding.progressScan.max = entries.size
        binding.progressScan.progress = 0
        binding.tvProgress.visibility = View.VISIBLE
        entries.mapIndexed { index, entry ->
            async {
                sem.withPermit {
                    val r = BookSourceHub.scan(entry)
                    val row = rows.getOrNull(index) ?: return@withPermit
                    row.total = r.total
                    row.newest = r.newestUpdate
                    row.exists = r.existsLocal
                    row.canUpdate = r.canUpdate
                    row.note = r.error
                    row.st = when {
                        r.error != null -> St.SCAN_FAIL
                        r.canUpdate -> St.UPDATE
                        r.existsLocal -> St.EXISTS
                        else -> St.NEW
                    }
                    done++
                    binding.progressScan.progress = done
                    binding.tvProgress.text = "扫描中 $done / ${entries.size}"
                    adapter.notifyItemChanged(index)
                }
            }
        }.awaitAll()
        binding.tvProgress.visibility = View.GONE
        updateSummary()
    }

    private fun setSelection(pred: (Row) -> Boolean) {
        rows.forEach { if (it.st != St.IMPORTING) it.selected = pred(it) }
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateSummary() {
        val picked = rows.count { it.selected }
        val newCount = rows.count { it.st == St.NEW }
        val upd = rows.count { it.st == St.UPDATE }
        val fail = rows.count { it.st == St.SCAN_FAIL }
        binding.tvSummary.text = "已选 $picked / 共 ${rows.size}（新 $newCount · 可更新 $upd · 扫描失败 $fail）"
        binding.btnImport.isEnabled = picked > 0 && importJob?.isActive != true
        binding.btnImport.text = "导入选中（$picked）"
    }

    private fun startImport() {
        val targets = rows.filter { it.selected }
        if (targets.isEmpty()) return
        importJob = lifecycleScope.launch {
            binding.progressScan.max = targets.size
            binding.progressScan.progress = 0
            binding.tvProgress.visibility = View.VISIBLE
            var ok = 0
            var failed = 0
            targets.forEachIndexed { i, row ->
                row.st = St.IMPORTING
                adapter.notifyItemChanged(rows.indexOf(row))
                val r = BookSourceHub.importUrl(row.item.src)
                r.fold(
                    onSuccess = { n -> row.st = St.IMPORTED; row.note = "已导入 $n 条"; ok++ },
                    onFailure = { e -> row.st = St.IMPORT_FAIL; row.note = e.localizedMessage; failed++ }
                )
                row.selected = false
                adapter.notifyItemChanged(rows.indexOf(row))
                binding.progressScan.progress = i + 1
                binding.tvProgress.text = "导入中 ${i + 1} / ${targets.size}"
            }
            binding.tvProgress.visibility = View.GONE
            binding.tvSummary.text = "导入完成：成功 $ok · 失败 $failed（可在列表查看每项状态）"
            updateSummary()
        }
    }

    private fun statusText(r: Row): String = when (r.st) {
        St.SCANNING -> "扫描中…"
        St.SCAN_FAIL -> "扫描失败"
        St.NEW -> "新书源"
        St.UPDATE -> "可更新"
        St.EXISTS -> "已存在"
        St.IMPORTING -> "导入中…"
        St.IMPORTED -> "✅ 已导入"
        St.IMPORT_FAIL -> "❌ 失败"
    }

    private fun statusColor(r: Row): Int {
        val res = when (r.st) {
            St.IMPORTED, St.NEW -> R.color.ai_ok_text
            St.UPDATE, St.IMPORTING, St.SCANNING -> R.color.ai_warn_text
            St.SCAN_FAIL, St.IMPORT_FAIL -> R.color.ai_error_text
            else -> R.color.ai_text_sub
        }
        return ContextCompat.getColor(this, res)
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
            b.tvTitle.text = r.item.title
            val bits = ArrayList<String>()
            if (r.total > 0) bits.add("含 ${r.total} 条")
            r.item.src.substringAfter("://").substringBefore('/').let { bits.add(it) }
            r.note?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
            b.tvSubtitle.text = bits.joinToString(" · ")
            b.tvStatus.text = statusText(r)
            b.tvStatus.setTextColor(statusColor(r))
            b.root.setOnClickListener {
                if (r.st == St.IMPORTING) return@setOnClickListener
                r.selected = !r.selected
                notifyItemChanged(position)
                updateSummary()
            }
        }
    }
}
