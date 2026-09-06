package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.ai.ModelManager
import io.legado.app.ai.model.AiProviderPresets
import io.legado.app.ai.runtime.AiKeyStore
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import java.io.File

/**
 * AI 平台配置（移植自 Arisemoss/legado，2026 基线 PreferenceFragment 版）。
 * 说明：服务商预设自动填充 BaseURL/模型；API Key 经 AiKeyStore(Keystore) 加密存储。
 */
class AiConfigFragment : PreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_ai)
        initProviderPreset()
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

    private fun upAllSummary() {
        val ctx = requireContext()
        val preset = AiProviderPresets.byId(ctx.getPrefString(PreferKey.aiProvider) ?: "")
        findPreference<Preference>(PreferKey.aiProvider)?.summary =
            preset?.label ?: ctx.getPrefString(PreferKey.aiBaseUrl) ?: "未设置"
        findPreference<Preference>(PreferKey.aiBaseUrl)?.summary =
            ctx.getPrefString(PreferKey.aiBaseUrl) ?: "—"
        findPreference<Preference>(PreferKey.aiModel)?.summary =
            ctx.getPrefString(PreferKey.aiModel) ?: "—"
        val keyText = AiKeyStore.getApiKey().orEmpty()
        findPreference<Preference>(PreferKey.aiApiKey)?.summary =
            if (keyText.isBlank()) "未设置（必填）" else "已加密保存 (${keyText.take(4)}…${keyText.takeLast(4)})"
        // 连接测试按钮在后续版本接入
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PICK_CHAT_BG = 42001
    }
}