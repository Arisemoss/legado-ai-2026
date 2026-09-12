package io.legado.app.ai.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.ai.ModelManager
import io.legado.app.ai.model.AiProviderPresets
import io.legado.app.ai.model.ProviderPreset
import io.legado.app.ai.runtime.AiKeyStore
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityAiSetupBinding
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次使用的 AI 助手配置向导：介绍 → 选服务商 → 填 Key（自动取模型）→ 选模型 → 完成。
 * 可随时跳过；完成状态记录在 PreferKey.aiSetupDone。
 */
class AiSetupWizardActivity : BaseActivity<ActivityAiSetupBinding>() {

    override val binding by viewBinding(ActivityAiSetupBinding::inflate)

    private var step = 0
    private var provider: ProviderPreset? = null
    private val models = ArrayList<String>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        provider = AiProviderPresets.byId(getPrefString(PreferKey.aiProvider))
            ?: AiProviderPresets.default
        binding.lvProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            AiProviderPresets.all.map { it.label + if (it.needsKey) "（需 API Key）" else "（本地，无需 Key）" }
        )
        binding.lvProvider.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        binding.lvProvider.setOnItemClickListener { _, _, position, _ ->
            provider = AiProviderPresets.all.getOrNull(position)
            provider?.let {
                putPrefString(PreferKey.aiProvider, it.id)
                putPrefString(PreferKey.aiBaseUrl, it.baseUrl)
                it.models.firstOrNull()?.let { m -> putPrefString(PreferKey.aiModel, m) }
            }
            render()
        }
        binding.lvModel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            models
        )
        binding.lvModel.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        binding.lvModel.setOnItemClickListener { _, _, position, _ ->
            models.getOrNull(position)?.let { m ->
                putPrefString(PreferKey.aiModel, m)
                binding.etModelManual.setText(m)
            }
        }
        binding.btnSkip.setOnClickListener { finishSetup(skip = true) }
        binding.btnPrev.setOnClickListener { if (step > 0) { step--; render() } }
        binding.btnNext.setOnClickListener { next() }
        binding.btnFetchModels.setOnClickListener { fetchModels() }
        render()
    }

    private fun render() {
        binding.tvStep.text = "第 ${step + 1} / 4 步"
        binding.pageIntro.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.pageProvider.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.pageKey.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.pageModel.visibility = if (step == 3) View.VISIBLE else View.GONE
        binding.btnPrev.isEnabled = step > 0
        binding.btnNext.text = if (step == 3) "完成" else "下一步"
        binding.tvBaseUrl.text = "Base URL：" +
            (getPrefString(PreferKey.aiBaseUrl) ?: provider?.baseUrl.orEmpty())
        if (step == 1) {
            val idx = AiProviderPresets.all.indexOfFirst { it.id == provider?.id }
            if (idx >= 0) binding.lvProvider.setItemChecked(idx, true)
        }
    }

    private fun next() {
        when (step) {
            0, 1 -> {
                if (step == 1 && provider == null) {
                    toast("请先选择服务商"); return
                }
                step++; render()
            }
            2 -> {
                val key = binding.etKey.text?.toString()?.trim().orEmpty()
                if (key.isNotBlank()) AiKeyStore.putApiKey(key)
                if (models.isEmpty()) fetchModels() else { step++; render() }
            }
            3 -> finishSetup(skip = false)
        }
    }

    private fun fetchModels() {
        val key = binding.etKey.text?.toString()?.trim().orEmpty()
        val baseUrl = getPrefString(PreferKey.aiBaseUrl) ?: provider?.baseUrl.orEmpty()
        if (key.isBlank() && provider?.needsKey != false) {
            binding.tvFetchStatus.text = "请先填写 API Key"
            return
        }
        binding.tvFetchStatus.text = "正在获取模型列表…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { ModelManager.fetchModels(baseUrl, key) }
            res.fold(
                onSuccess = { list ->
                    models.clear()
                    if (list.isEmpty()) {
                        models.addAll(provider?.models.orEmpty())
                        binding.tvFetchStatus.text = "服务商未返回列表，已使用预设模型（${models.size} 个）"
                    } else {
                        models.addAll(list)
                        binding.tvFetchStatus.text = "获取到 ${models.size} 个模型，请选择"
                    }
                    (binding.lvModel.adapter as? ArrayAdapter<String>)?.notifyDataSetChanged()
                    step = 3
                    render()
                },
                onFailure = { e ->
                    binding.tvFetchStatus.text =
                        "获取失败：${e.localizedMessage ?: e.javaClass.simpleName}（可跳过，稍后在设置页重试）"
                }
            )
        }
    }

    private fun finishSetup(skip: Boolean) {
        val manual = binding.etModelManual.text?.toString()?.trim().orEmpty()
        if (!skip && manual.isNotBlank()) putPrefString(PreferKey.aiModel, manual)
        putPrefBoolean(PreferKey.aiSetupDone, true)
        toast(if (skip) "可稍后在「我的 → AI 智能助手」里配置" else "配置完成，开始使用 AI 助手")
        finish()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
