package io.legado.app.ai.model

/**
 * 模型服务商预设。全部为 OpenAI 兼容端点，可直接被 [io.legado.app.ai.runtime.OpenAIClient] 使用。
 * 供设置页（AiConfigFragment）与 AI Hub 快捷配置共享。
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val models: List<String>,
    val needsKey: Boolean = true,
    val note: String = ""
)

object AiProviderPresets {

    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            label = "DeepSeek（深度求索）",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            note = "国内直连、价格低，推荐入门；平台 platform.deepseek.com"
        ),
        ProviderPreset(
            id = "tongyi",
            label = "通义千问（阿里云百炼）",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf("qwen-plus", "qwen-turbo", "qwen-max"),
            note = "阿里云百炼控制台开通，有免费额度"
        ),
        ProviderPreset(
            id = "zhipu",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            models = listOf("glm-4-flash", "glm-4-air", "glm-4-plus"),
            note = "glm-4-flash 免费，注册即用"
        ),
        ProviderPreset(
            id = "moonshot",
            label = "Kimi（月之暗面）",
            baseUrl = "https://api.moonshot.cn/v1",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            note = "长文本友好，适合整章分析"
        ),
        ProviderPreset(
            id = "siliconflow",
            label = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            models = listOf(
                "deepseek-ai/DeepSeek-V3",
                "Qwen/Qwen2.5-72B-Instruct",
                "THUDM/glm-4-9b-chat"
            ),
            note = "聚合多家开源模型，注册送额度"
        ),
        ProviderPreset(
            id = "minimax",
            label = "MiniMax（海螺）",
            baseUrl = "https://api.minimax.chat/v1",
            models = listOf("abab6.5s-chat", "abab6.5g-chat"),
            note = "OpenAI 兼容的 chatcompletion_v2 端点"
        ),
        ProviderPreset(
            id = "doubao",
            label = "豆包（火山方舟）",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            models = listOf("doubao-pro-32k", "doubao-lite-32k"),
            note = "火山方舟需创建推理接入点，或直接填模型名"
        ),
        ProviderPreset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o-mini", "gpt-4o"),
            note = "需国际网络与外卡"
        ),
        ProviderPreset(
            id = "openrouter",
            label = "OpenRouter（聚合）",
            baseUrl = "https://openrouter.ai/api/v1",
            models = listOf(
                "openai/gpt-4o-mini",
                "deepseek/deepseek-chat",
                "google/gemini-flash-1.5"
            ),
            note = "一个 Key 用遍各家模型，部分免费"
        ),
        ProviderPreset(
            id = "groq",
            label = "Groq（极速推理）",
            baseUrl = "https://api.groq.com/openai/v1",
            models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            note = "速度极快的开源模型托管，有免费层"
        ),
        ProviderPreset(
            id = "xai",
            label = "xAI（Grok）",
            baseUrl = "https://api.x.ai/v1",
            models = listOf("grok-2-latest", "grok-beta"),
            note = "xAI 控制台申请 API Key"
        ),
        ProviderPreset(
            id = "ollama",
            label = "Ollama（本机/局域网）",
            baseUrl = "http://127.0.0.1:11434/v1",
            models = listOf("llama3", "qwen2.5"),
            needsKey = false,
            note = "完全本地免费；手机端需 Termux 等方式运行 Ollama"
        ),
        ProviderPreset(
            id = "lmstudio",
            label = "LM Studio（本机）",
            baseUrl = "http://127.0.0.1:1234/v1",
            models = listOf("local-model"),
            needsKey = false,
            note = "电脑端 LM Studio 开启 Local Server 后使用"
        )
    )

    fun byId(id: String?): ProviderPreset? = all.find { it.id == id }

    /** 按 Base URL 反查预设（用于状态栏显示当前服务商名） */
    fun byBaseUrl(url: String?): ProviderPreset? =
        url?.let { u -> all.find { it.baseUrl.equals(u, ignoreCase = true) } }

    /** 默认选中：优先 DeepSeek */
    val default: ProviderPreset get() = byId("deepseek") ?: all.first()
}
