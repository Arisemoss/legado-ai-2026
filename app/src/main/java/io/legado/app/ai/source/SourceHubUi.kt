package io.legado.app.ai.source

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「一键获取书源」UI：抓聚合页 → 选择条目 → 批量导入（带进度与结果提示）。 */
object SourceHubUi {

    fun launch(host: Fragment) {
        val ctx = host.requireContext()
        val scope = host.viewLifecycleOwner.lifecycleScope
        val loading = AlertDialog.Builder(ctx)
            .setMessage("正在获取书源列表…")
            .setCancelable(false)
            .create()
        loading.show()
        scope.launch {
            val res = withContext(Dispatchers.IO) { BookSourceHub.fetchEntries() }
            loading.dismiss()
            res.fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        toast(ctx, "未在页面中发现可导入的书源")
                        return@fold
                    }
                    val labels = arrayOf("全部导入（${entries.size} 条）") +
                        entries.map { it.title }.toTypedArray()
                    AlertDialog.Builder(ctx)
                        .setTitle("选择要导入的书源")
                        .setItems(labels) { _, which ->
                            val chosen = if (which == 0) entries else listOf(entries[which - 1])
                            doImport(ctx, scope, chosen)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
                onFailure = { e ->
                    toast(ctx, "获取失败：${e.localizedMessage ?: e.javaClass.simpleName}")
                }
            )
        }
    }

    private fun doImport(
        ctx: Context,
        scope: CoroutineScope,
        entries: List<BookSourceHub.Entry>
    ) {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("正在导入书源")
            .setMessage("0 / ${entries.size}")
            .setCancelable(false)
            .create()
        dialog.show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                BookSourceHub.importAll(entries) { done, total ->
                    dialog.setMessage("$done / $total")
                }
            }
            dialog.dismiss()
            toast(
                ctx,
                "导入完成：成功 ${result.inserted} / 共 ${result.total}，失败 ${result.failed}"
            )
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}
