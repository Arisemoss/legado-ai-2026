package io.legado.app.ai.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.ai.bridge.AppNav
import io.legado.app.ai.model.ToolEvent
import io.legado.app.ai.tool.AiPreset
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityAgentHubBinding
import io.legado.app.databinding.AiItemConfirmBinding
import io.legado.app.databinding.AiItemErrorBinding
import io.legado.app.databinding.AiItemMsgAiBinding
import io.legado.app.databinding.AiItemMsgUserBinding
import io.legado.app.databinding.AiItemProcessBinding
import io.legado.app.databinding.AiItemSessionBinding
import io.legado.app.databinding.AiItemToolBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Agent Hub 中心页（移植：ViewBinding 改写，自包含 adapter）。
 * 气泡对话 + 实时工具卡片 + 写操作内联二次确认 + 多会话管理 + 上下文预设注入。
 */
class AgentHubActivity : BaseActivity<ActivityAgentHubBinding>() {

    override val binding by viewBinding(ActivityAgentHubBinding::inflate)

    companion object {
        const val EXTRA_SELECT_TAB = "agent_select_tab"
        private const val ROW_STREAMING = "__streaming__"
        private const val VT_USER = 0
        private const val VT_AI = 1
        private const val VT_TOOL = 2
        private const val VT_ERROR = 3
        private const val VT_CONFIRM = 4
        private const val VT_PROCESS = 5
    }

    private lateinit var adapter: ChatAdapter
    private lateinit var vm: AgentHubViewModel
    private val uiJobs = ArrayList<Job>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var lastBgPath: String? = null
    private var typingTick = 0
    private var streamingStartMs = 0L

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        vm = AgentHubViewModel(readPreset())
        adapter = ChatAdapter(this)
        initView()
        initVm()
        uiJobs += lifecycleScope.launch { vm.init() }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStatusLine()
        applyChatBackground()
    }

    /** 应用聊天背景：极光渐变 > 自定义图片(不透明度+渐变遮罩) > 默认纯色。 */
    private fun applyChatBackground() {
        val gradient = getPrefBoolean(PreferKey.aiChatBgGradient, false)
        if (gradient) {
            binding.auroraView.setDarkMode(AppConfig.isNightTheme)
            binding.auroraView.visibility = View.VISIBLE
            binding.ivChatBg.visibility = View.GONE
            binding.viewScrim.visibility = View.GONE
            return
        }
        binding.auroraView.visibility = View.GONE
        val path = getPrefString(PreferKey.aiChatBgPath)
        if (!path.isNullOrBlank() && File(path).exists()) {
            if (path != lastBgPath) {
                lastBgPath = path
                runCatching {
                    binding.ivChatBg.setImageBitmap(GlassEffect.frosted(decodeSampled(File(path))))
                }
            }
            // ListPreference 持久化为 String，必须按 String 读取再转 Int
            val opacity = getPrefString(PreferKey.aiChatBgOpacity)?.toIntOrNull() ?: 60
            binding.ivChatBg.alpha = opacity.coerceIn(10, 100) / 100f
            binding.ivChatBg.visibility = View.VISIBLE
            binding.viewScrim.visibility = View.VISIBLE
        } else {
            binding.ivChatBg.visibility = View.GONE
            binding.viewScrim.visibility = View.GONE
        }
    }

    /** 按屏幕尺寸采样解码，避免整图加载 OOM */
    private fun decodeSampled(f: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 1920 || bounds.outWidth / (sample * 2) >= 1080) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    override fun onDestroy() {
        uiJobs.forEach { it.cancel() }
        uiJobs.clear()
        runCatching { vm.dispose() }
        super.onDestroy()
    }

    private fun readPreset(): AiPreset = intent?.let {
        AiPreset(
            bookName = it.getStringExtra("preset_book"),
            chapterTitle = it.getStringExtra("preset_chapter"),
            content = it.getStringExtra("preset_content"),
            sourceUrl = it.getStringExtra("preset_source_url"),
            searchKeyword = it.getStringExtra("preset_search")
        )
    } ?: AiPreset()

    // ---------- 视图 ----------

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            binding.etInput.setText("")
            streamingStartMs = System.currentTimeMillis()
            vm.send(text)
        }
        binding.btnStop.setOnClickListener { vm.stop() }
        binding.btnConfig.setOnClickListener {
            // TODO(移植): 接入 AI 设置页（ConfigTag.AI_CONFIG / AiConfigFragment 随配置移植）
            toast("AI 设置页移植中（可编辑 gradle 或待配置页接入）")
        }
        binding.btnNewSession.setOnClickListener {
            uiJobs += lifecycleScope.launch { runCatching { vm.newSession() } }
            toast("已新建会话")
        }
        binding.btnSessions.setOnClickListener { showSessionDialog() }
        binding.btnLogs.setOnClickListener { startActivity(Intent(this, AiLogActivity::class.java)) }

        binding.chipSummarize.setOnClickListener { fillInput("帮我总结当前正在读的这一章") }
        binding.chipFindBook.setOnClickListener { fillInput("帮我在书源里找《诡秘之主》，并加入书架") }
        binding.chipCharacters.setOnClickListener { fillInput("分析一下当前这本书的主要人物关系") }
        binding.chipSource.setOnClickListener { fillInput("检测我的书源哪些失效了，给出诊断") }
        binding.chipShelf.setOnClickListener { fillInput("看看我书架里有哪些书？") }
    }

    private fun fillInput(text: String) {
        binding.etInput.setText(text)
        binding.etInput.setSelection(text.length)
        binding.etInput.requestFocus()
    }

    // ---------- 状态订阅 ----------

    private fun initVm() {
        vm.attach(this)
        uiJobs += lifecycleScope.launch {
            var lastRendered: List<ChatRow>? = null
            var lastBusy: Boolean? = null
            while (isActive) {
                val partial = vm.currentPartial()
                val list = run {
                    val folded = foldProcess(vm.messages.value)
                    if (partial != null) {
                        folded + ChatRow.Msg(
                            ROW_STREAMING, "assistant", partial,
                            if (streamingStartMs > 0) streamingStartMs else System.currentTimeMillis()
                        )
                    } else folded
                }
                val isEmpty = list.isEmpty()
                binding.boxEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
                val busyNow = vm.isBusy()
                binding.chipsScroll.visibility = if (!busyNow || isEmpty) View.VISIBLE else View.GONE
                if (vm.isTyping()) {
                    typingTick++
                    binding.tvTyping.text = "思考中" + "·".repeat(typingTick % 4)
                    binding.typingBar.visibility = View.VISIBLE
                } else {
                    binding.typingBar.visibility = View.GONE
                }
                if (busyNow != lastBusy) {
                    binding.btnSend.visibility = if (busyNow) View.GONE else View.VISIBLE
                    binding.btnStop.visibility = if (busyNow) View.VISIBLE else View.GONE
                    lastBusy = busyNow
                }
                binding.tvSubtitle.text = vm.statusLine.value
                renderIfChanged(list, lastRendered)
                lastRendered = list
                delay(if (partial != null) 90L else 150L)
            }
        }
        uiJobs += lifecycleScope.launch {
            while (isActive) {
                val nav = vm.navigation.value
                if (nav != null) {
                    vm.navigation.value = null
                    when (nav) {
                        is AppNav.OpenBook -> openReader(nav)
                        is AppNav.GlobalSearch -> openSearch(nav)
                        AppNav.ToBookshelf -> openBookshelf()
                    }
                }
                delay(150)
            }
        }
    }

    private fun renderIfChanged(list: List<ChatRow>, last: List<ChatRow>?) {
        if (list === last) return
        if (last != null && list.size == last.size) {
            var diffIdx = -1
            for (i in list.indices) {
                if (list[i] != last[i]) {
                    if (diffIdx >= 0) { diffIdx = Int.MIN_VALUE; break }
                    diffIdx = i
                }
            }
            if (diffIdx == -1) return
            if (diffIdx != Int.MIN_VALUE) {
                adapter.setItem(diffIdx, list[diffIdx])
                if (diffIdx >= list.size - 2 && shouldPinBottom()) {
                    binding.recyclerView.scrollToPosition(list.size - 1)
                }
                return
            }
        }
        adapter.setItems(list)
        if (list.isNotEmpty()) binding.recyclerView.scrollToPosition(list.size - 1)
    }

    private fun shouldPinBottom(): Boolean {
        if (adapter.itemCount == 0) return true
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastCompletelyVisibleItemPosition()
        return lastVisible == RecyclerView.NO_POSITION || lastVisible >= adapter.itemCount - 2
    }

    // ---------- 思考过程折叠 ----------

    private val collapsedTurns = HashSet<String>()

    private fun foldProcess(rows: List<ChatRow>): List<ChatRow> {
        if (rows.none { it is ChatRow.ToolCard || it is ChatRow.ErrorRow }) return rows
        val out = ArrayList<ChatRow>(rows.size + 4)
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            out.add(r)
            if (r is ChatRow.Msg && r.role == "user") {
                val proc = ArrayList<ChatRow>()
                var j = i + 1
                while (j < rows.size && rows[j] !is ChatRow.Msg) {
                    when (rows[j]) {
                        is ChatRow.ToolCard, is ChatRow.ErrorRow -> proc.add(rows[j])
                        else -> out.add(rows[j])
                    }
                    j++
                }
                if (proc.isNotEmpty()) {
                    val expanded = r.key !in collapsedTurns
                    out.add(ChatRow.Process("proc_${r.key}", proc.size, expanded))
                    if (expanded) out.addAll(proc)
                }
                i = j
                continue
            }
            i++
        }
        return out
    }

    private fun toggleProcess(turnKey: String) {
        if (!collapsedTurns.remove(turnKey)) collapsedTurns.add(turnKey)
        renderNow()
    }

    private fun renderNow() {
        val partial = vm.currentPartial()
        val folded = foldProcess(vm.messages.value)
        val list = if (partial != null) {
            folded + ChatRow.Msg(
                ROW_STREAMING, "assistant", partial,
                if (streamingStartMs > 0) streamingStartMs else System.currentTimeMillis()
            )
        } else folded
        adapter.setItems(list)
        if (list.isNotEmpty()) binding.recyclerView.scrollToPosition(list.size - 1)
    }

    private fun openReader(nav: AppNav.OpenBook) {
        val url = nav.bookUrl
        if (url.isNullOrBlank()) {
            toast("未定位到《${nav.bookName}》，可能未加入书架")
            return
        }
        startActivity(
            Intent(this, ReadBookActivity::class.java)
                .putExtra("bookUrl", url)
                .putExtra("inBookshelf", true)
        )
    }

    private fun openBookshelf() {
        Intent(this, MainActivity::class.java).let {
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            it.putExtra(EXTRA_SELECT_TAB, 0)
            startActivity(it)
        }
    }

    private fun openSearch(nav: AppNav.GlobalSearch) {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("key", nav.keyword))
    }

    // ---------- 会话管理弹窗 ----------

    private fun showSessionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_sessions, null)
        val listView = dialogView.findViewById<ListView>(R.id.list_sessions)
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = vm.sessions.value.size
            override fun getItem(position: Int) = vm.sessions.value[position]
            override fun getItemId(position: Int) = getItem(position).id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder = if (convertView == null) {
                    AiItemSessionBinding.inflate(layoutInflater, parent, false)
                } else AiItemSessionBinding.bind(convertView)
                val s = getItem(position)
                holder.tvSessionTitle.text = if (s.id == vm.sessionId.value) "● ${s.title}" else s.title
                holder.tvSessionTime.text = vm.formatTime(s.updatedAt)
                holder.btnSessionDelete.setOnClickListener {
                    AlertDialog.Builder(this@AgentHubActivity)
                        .setMessage("确定删除会话「${s.title}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            uiJobs += lifecycleScope.launch { runCatching { vm.deleteSession(s.id) } }
                            toast("会话已删除")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                return holder.root
            }
        }
        listView.adapter = listAdapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("会话记录")
            .setView(dialogView)
            .setPositiveButton("＋ 新建") { _, _ ->
                uiJobs += lifecycleScope.launch { runCatching { vm.newSession() } }
            }
            .setNeutralButton("清空当前消息") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("确定清空当前会话的全部消息吗？")
                    .setPositiveButton("清空") { _, _ ->
                        uiJobs += lifecycleScope.launch { runCatching { vm.clearCurrentMessages() } }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
        listView.setOnItemClickListener { _, _, position, _ ->
            val target = vm.sessions.value.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            uiJobs += lifecycleScope.launch { runCatching { vm.switchTo(target.id) } }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

    // ---------- 消息多类型 Adapter（自包含） ----------

    inner class ChatAdapter(context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val list = ArrayList<ChatRow>()
        private val layoutInflater = LayoutInflater.from(context)

        @Synchronized fun setItems(items: List<ChatRow>) {
            list.clear(); list.addAll(items); notifyDataSetChanged()
        }

        @Synchronized fun setItem(position: Int, item: ChatRow) {
            if (position in 0 until list.size) { list[position] = item; notifyItemChanged(position) }
        }

        override fun getItemCount(): Int = list.size

        override fun getItemViewType(position: Int): Int = when (val item = list[position]) {
            is ChatRow.Msg -> if (item.role == "user") VT_USER else VT_AI
            is ChatRow.ToolCard -> VT_TOOL
            is ChatRow.ErrorRow -> VT_ERROR
            is ChatRow.Confirm -> VT_CONFIRM
            is ChatRow.Process -> VT_PROCESS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VT_USER -> ItemVH(AiItemMsgUserBinding.inflate(layoutInflater, parent, false), viewType)
                VT_AI -> ItemVH(AiItemMsgAiBinding.inflate(layoutInflater, parent, false), viewType)
                VT_TOOL -> ItemVH(AiItemToolBinding.inflate(layoutInflater, parent, false), viewType)
                VT_ERROR -> ItemVH(AiItemErrorBinding.inflate(layoutInflater, parent, false), viewType)
                VT_PROCESS -> ItemVH(AiItemProcessBinding.inflate(layoutInflater, parent, false), viewType)
                VT_CONFIRM -> ItemVH(AiItemConfirmBinding.inflate(layoutInflater, parent, false), viewType)
                else -> ItemVH(AiItemMsgAiBinding.inflate(layoutInflater, parent, false), viewType)
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = list[position]
            val vh = holder as ItemVH
            when (vh.type) {
                VT_USER -> {
                    val b = vh.binding as AiItemMsgUserBinding
                    val msg = item as ChatRow.Msg
                    b.tvUserText.text = msg.content
                    b.tvUserTime.text = timeFmt.format(Date(msg.time))
                }
                VT_AI -> {
                    val b = vh.binding as AiItemMsgAiBinding
                    val msg = item as ChatRow.Msg
                    b.tvAiText.text = msg.content
                    b.tvAiTime.text = timeFmt.format(Date(msg.time))
                }
                VT_TOOL -> bindTool(vh.binding as AiItemToolBinding, item as ChatRow.ToolCard)
                VT_ERROR -> {
                    val b = vh.binding as AiItemErrorBinding
                    b.tvError.text = (item as ChatRow.ErrorRow).message
                }
                VT_PROCESS -> {
                    val b = vh.binding as AiItemProcessBinding
                    val p = item as ChatRow.Process
                    b.tvProcLabel.text = (if (p.expanded) "▾" else "▸") + " 工作过程 · ${p.steps} 步"
                    b.tvProcLabel.setOnClickListener {
                        val row = list.getOrNull(position)
                        if (row is ChatRow.Process) toggleProcess(row.key.removePrefix("proc_"))
                    }
                }
                VT_CONFIRM -> bindConfirm(vh.binding as AiItemConfirmBinding, item as ChatRow.Confirm, position)
            }
        }

        private fun bindTool(b: AiItemToolBinding, card: ChatRow.ToolCard) {
            b.tvToolName.text = card.name
            b.tvToolArgs.text = card.argsPreview
            b.tvToolArgs.visibility = if (card.argsPreview.isBlank()) View.GONE else View.VISIBLE
            if (card.detail.isNullOrBlank()) {
                b.tvToolDetail.visibility = View.GONE
            } else {
                b.tvToolDetail.visibility = View.VISIBLE
                b.tvToolDetail.text = card.detail
            }
            when (card.phase) {
                ToolEvent.PHASE_RUNNING -> {
                    b.tvToolStateIcon.text = "⏳"; b.tvToolState.text = "执行中"
                    b.tvToolState.setTextColor(color(R.color.ai_text_sub))
                }
                ToolEvent.PHASE_RESULT -> {
                    b.tvToolStateIcon.text = "✅"; b.tvToolState.text = formatElapsed(card.elapsedMs)
                    b.tvToolState.setTextColor(color(R.color.ai_ok_text))
                }
                ToolEvent.PHASE_CONFIRM -> {
                    b.tvToolStateIcon.text = "🔐"; b.tvToolState.text = "待确认"
                    b.tvToolState.setTextColor(color(R.color.ai_warn_text))
                }
                ToolEvent.PHASE_APPROVED -> {
                    b.tvToolStateIcon.text = "✍️"; b.tvToolState.text = formatElapsed(card.elapsedMs)
                    b.tvToolState.setTextColor(color(R.color.ai_ok_text))
                }
                ToolEvent.PHASE_DENIED -> {
                    b.tvToolStateIcon.text = "🚫"; b.tvToolState.text = "已拒绝"
                    b.tvToolState.setTextColor(color(R.color.ai_error_text))
                }
                else -> {
                    b.tvToolStateIcon.text = "❌"; b.tvToolState.text = "出错"
                    b.tvToolState.setTextColor(color(R.color.ai_error_text))
                }
            }
        }

        private fun bindConfirm(b: AiItemConfirmBinding, c: ChatRow.Confirm, position: Int) {
            b.tvProposal.text = c.proposalText
            if (c.decided == null) {
                b.confirmActions.visibility = View.VISIBLE
                b.tvDecided.visibility = View.GONE
            } else {
                b.confirmActions.visibility = View.GONE
                b.tvDecided.visibility = View.VISIBLE
                if (c.decided == true) {
                    b.tvDecided.text = "✔ 已同意执行"
                    b.tvDecided.setTextColor(color(R.color.ai_ok_text))
                } else {
                    b.tvDecided.text = "✖ 已拒绝"
                    b.tvDecided.setTextColor(color(R.color.ai_error_text))
                }
            }
            b.btnApprove.setOnClickListener {
                val row = list.getOrNull(position)
                if (row is ChatRow.Confirm && row.decided == null) {
                    vm.approve(row.token, true)
                    toast("已同意，正在执行写操作")
                }
            }
            b.btnDeny.setOnClickListener {
                val row = list.getOrNull(position)
                if (row is ChatRow.Confirm && row.decided == null) vm.approve(row.token, false)
            }
        }

        private fun formatElapsed(ms: Long): String = if (ms <= 0) "完成" else "完成 · ${ms / 1000.0}s"
    }

    inner class ItemVH(val binding: androidx.viewbinding.ViewBinding, val type: Int) :
        RecyclerView.ViewHolder(binding.root)
}