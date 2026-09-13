package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.ai.AiPlatform
import io.legado.app.ai.ModelManager
import io.legado.app.ai.ui.AiLogActivity
import io.legado.app.ai.ui.AiSetupWizardActivity
import io.legado.app.ai.model.AiModelConfig
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.runtime.OpenAIClient
import io.legado.app.ai.model.AiProviderPresets
import io.legado.app.ai.runtime.AiKeyStore
import io.legado.app.constant.PreferKey
import androidx.lifecycle.lifecycleScope
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 平台配置（移植自 Arisemoss/legado，2026 基线 PreferenceFragment 版）。
 * 说明：服务商预设自动填充 BaseURL/模型；API Key 经 AiKeyStore(Keystore) 加密存储。
 */
class AiConfigFragment : PreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_ai)
        addTestConnectionPreference()
        initProviderPreset()
        initModelPicker()
        initApiKey()
        initChatBackgroundPrefs()
        upAllSummary()
    }

    private fun initProviderPreset() {
        findPreference<ListPreference>(PreferKey.aiProvider)?.setOnPreferenceChangeListener { _, newValue ->
            val provider = newValue as? String
            provider?.let { applyProviderPreset(it) }
            true
        }
    }

    private fun applyProviderPreset(provider: String) {
        val preset = AiProviderPresets.byId(provider)
        if (preset == null) {
            toast("未知服务商: $provider")
            return
        }
        putPrefString(PreferKey.aiBaseUrl, preset.baseUrl)
        putPrefString(PreferKey.aiProvider, provider)
        preset.models.firstOrNull()?.let { putPrefString(PreferKey.aiModel, it) }
        upAllSummary()
        toast("已应用服务商：${preset.label}")
    }

    private fun initApiKey() {
        findPreference<EditTextPreference>(PreferKey.aiApiKey)?.let { pref ->
            pref.setOnBindEditTextListener { editText: EditText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setText(AiKeyStore.getApiKey())
            }
            // 明文拦截：写入即走 Keystore 加密，pref 不留明文
            pref.setOnPreferenceChangeListener { _, newValue ->
                AiKeyStore.putApiKey(newValue as? String ?: "")
                upAllSummary()
                refreshModels()
                false
            }
        }
    }

    private fun initChatBackgroundPrefs() {
        findPreference<Preference>("ai_chat_bg_pick")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(Intent.createChooser(intent, "选择聊天背景图"), REQ_PICK_CHAT_BG)
            true
        }
        findPreference<Preference>("ai_chat_bg_clear")?.setOnPreferenceClickListener {
            File(requireContext().filesDir, "ai_chat_bg.jpg").delete()
            removePref(PreferKey.aiChatBgPath)
            toast("已恢复默认背景")
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_CHAT_BG && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                val dst = File(requireContext().filesDir, "ai_chat_bg.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                putPrefString(PreferKey.aiChatBgPath, dst.absolutePath)
                toast("背景已更新")
            }.onFailure { toast("设置失败: ${it.localizedMessage}") }
        }
    }


    /** 「测试连接」：按当前配置发起一次最小 /chat/completions 请求 */
    private fun addTestConnectionPreference() {
        val pref = findPreference<Preference>("ai_test_conn") ?: return
        pref.setOnPreferenceClickListener {
            pref.isEnabled = false
            pref.summary = "测试中…"
            viewLifecycleOwner.lifecycleScope.launch {
                val cfg = ModelManager.getConfig()
                // 阻塞式 HTTP：必须切 IO，否则在主线程必然抛 NetworkOnMainThreadException
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val client = OpenAIClient(
                            baseUrl = cfg.baseUrl,
                            apiKey = cfg.apiKey,
                            model = cfg.name,
                            timeoutMillis = cfg.timeoutMillis,
                            textToolMode = cfg.toolProtocol == AiModelConfig.PROTOCOL_TEXT
                        )
                        client.complete(listOf(ChatMessage(role = "user", content = "ping")), null, false)
                    }
                }
                pref.isEnabled = true
                pref.summary = result.fold(
                    onSuccess = { "✅ 连接成功（模型：${cfg.name}）" },
                    onFailure = { "❌ ${it.localizedMessage ?: it.javaClass.simpleName}" }
                )
            }
            true
        }
    }

    private var modelPick: ListPreference? = null
    private var refreshPref: Preference? = null

    /** 模型选择器 + 刷新按钮（键由 pref_config_ai.xml 声明） */
    private fun initModelPicker() {
        modelPick = findPreference("ai_model_pick")
        modelPick?.setOnPreferenceChangeListener { _, newValue ->
            val m = newValue as? String
            if (!m.isNullOrBlank()) {
                putPrefString(PreferKey.aiModel, m)
                upAllSummary()
            }
            true
        }
        refreshPref = findPreference("ai_refresh_models")
        refreshPref?.setOnPreferenceClickListener {
            refreshModels()
            true
        }

        findPreference<EditTextPreference>(PreferKey.aiBaseUrl)?.setOnPreferenceChangeListener { _, v ->
            val url = (v as? String).orEmpty()
            if (url.isNotBlank()) view?.postDelayed({ refreshModels() }, 250)
            true
        }
        findPreference<EditTextPreference>(PreferKey.aiModel)?.let { m ->
            m.setOnPreferenceChangeListener { _, _ ->
                view?.postDelayed({ upAllSummary() }, 100)
                true
            }
        }
        // 注意：这里不能调用 refreshModels()——onCreatePreferences 阶段 Fragment 还没有 View，
        // 访问 viewLifecycleOwner 会抛 IllegalStateException（真机崩溃日志：AiConfigFragment.refreshModels:210）。
        // 首次刷新改到 onViewCreated 里做。
        findPreference<Preference>("ai_tools_info")?.let { pref ->
            // 数量动态取注册表，不再硬编码（曾写死 28，实际 32）
            val count = runCatching { AiPlatform.registry.definitions().size }.getOrDefault(0)
            if (count > 0) pref.title = "AI 工具清单（$count 个）"
            pref.setOnPreferenceClickListener {
                showToolsDialog()
                true
            }
        }
        findPreference<Preference>("ai_logs_entry")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AiLogActivity::class.java))
            true
        }
        // 向导可重入：装过旧版 / 点过跳过的用户不必清数据也能重新配置
        findPreference<Preference>("ai_rerun_setup")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AiSetupWizardActivity::class.java))
            true
        }
    }

    /** 视图就绪后再首次拉取模型列表（onCreatePreferences 阶段无 View，不能碰 viewLifecycleOwner） */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshModels()
    }

    /** 拉取 /models 并回填列表；失败回退到服务商预设模型 */
    private fun refreshModels() {
        if (!isAdded) return
        val ctx = requireContext()
        val pick = modelPick ?: return
        val refresh = refreshPref
        val cfg = ModelManager.getConfig()
        if (cfg.apiKey.isBlank()) {
            refresh?.summary = "先填写 API Key 再刷新"
            return
        }
        // 视图未就绪（onCreatePreferences 阶段）直接跳过，避免 IllegalStateException
        if (view == null) return
        refresh?.isEnabled = false
        refresh?.summary = "拉取中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelManager.fetchModels(cfg.baseUrl, cfg.apiKey)
            }
            refresh?.isEnabled = true
            res.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) {
                        val preset = AiProviderPresets.byBaseUrl(cfg.baseUrl)
                            ?: AiProviderPresets.byId(ctx.getPrefString(PreferKey.aiProvider))
                        val fallback = preset?.models.orEmpty()
                        pick.entryValues = fallback.toTypedArray()
                        pick.entries = fallback.toTypedArray()
                        refresh?.summary = "服务商未返回列表，已用预设模型（${fallback.size} 个）"
                    } else {
                        pick.entryValues = models.toTypedArray()
                        pick.entries = models.toTypedArray()
                        refresh?.summary = "共 ${models.size} 个模型"
                        val cur = cfg.name
                        if (cur.isBlank() || models.none { it == cur }) {
                            models.firstOrNull()?.let {
                                putPrefString(PreferKey.aiModel, it)
                                upAllSummary()
                            }
                        }
                        pick.value = ctx.getPrefString(PreferKey.aiModel)
                    }
                },
                onFailure = { e ->
                    refresh?.summary = "拉取失败：${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            )
        }
    }
    /** 工具清单：按分类展开全部工具（id / 说明 / 是否写确认），替代原先只弹一个 Toast */
    private fun showToolsDialog() {
        val defs = runCatching { AiPlatform.registry.definitions() }.getOrDefault(emptyList())
        if (defs.isEmpty()) {
            toast("工具注册表未就绪，请重启 App 后重试")
            return
        }
        val text = defs.groupBy { it.category }
            .entries
            .joinToString("\n\n") { (category, tools) ->
                "【$category】${tools.size} 个\n" + tools.joinToString("\n") { t ->
                    "· ${t.id}" + if (t.manualConfirm) "（写操作 · 需确认）" else "" +
                        "\n    ${t.info.description}"
                }
            }
        AlertDialog.Builder(requireContext())
            .setTitle("AI 工具清单（${defs.size} 个）")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun upAllSummary() {
        val ctx = requireContext()
        val preset = AiProviderPresets.byId(ctx.getPrefString(PreferKey.aiProvider) ?: "")
        findPreference<Preference>(PreferKey.aiProvider)?.summary =
            preset?.label ?: ctx.getPrefString(PreferKey.aiBaseUrl) ?: "未设置"
        findPreference<Preference>(PreferKey.aiBaseUrl)?.summary =
            ctx.getPrefString(PreferKey.aiBaseUrl) ?: "—"
        findPreference<Preference>(PreferKey.aiModel)?.summary =
            ctx.getPrefString(PreferKey.aiModel) ?: "—"
        // 审计 A-1/A-5：不展示 Key 的任何字符（尾 4 位是真实熵），只显示状态与真实存储方式；
        // 同时避免 getApiKey() 把明文载入 UI 层。
        findPreference<Preference>(PreferKey.aiApiKey)?.summary = when (AiKeyStore.storageMode()) {
            AiKeyStore.StorageMode.ENCRYPTED -> "已配置 · Android Keystore 加密存储"
            AiKeyStore.StorageMode.PLAINTEXT -> "⚠️ 已配置，但本机 Keystore 不可用，当前为明文存储"
            AiKeyStore.StorageMode.NONE -> "未设置（必填）"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PICK_CHAT_BG = 42001
    }
}