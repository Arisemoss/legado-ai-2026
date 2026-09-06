package io.legado.app.ai

import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.bridge.DefaultAppController
import io.legado.app.ai.bridge.DefaultBookFetcher
import io.legado.app.ai.bridge.DefaultBookSourceAnalyzer
import io.legado.app.ai.bridge.DefaultChapterReader
import io.legado.app.ai.bridge.DefaultSourceRuleWriter
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.AiModelConfig
import io.legado.app.ai.runtime.AgentRuntime
import io.legado.app.ai.runtime.OpenAIClient
import io.legado.app.ai.tool.impl.buildRegistry

/**
 * AI 平台统一装配点。
 * 在 [io.legado.app.App.onCreate] 初始化 client / bridge / registry / runtime，
 * 供 Agent Hub 与其 UI 消费。
 *
 * [syncConfig] 支持配置热更新：设置页改动后调用即可重建模型客户端，
 * 无需重启 App；bridge（领域桥）不随配置变化，仅装配一次。
 */
object AiPlatform {

    @Volatile
    private var initialized = false

    private var lastConfig: AiModelConfig? = null
    private val lock = Any()

    lateinit var runtime: AgentRuntime
        private set
    lateinit var bridge: AiBridge
        private set

    /**
     * 全局工具注册表。与模型配置解耦：init() 时装配一次，
     * 供 AgentRuntime 执行与 SystemPromptBuilder 生成文本协议工具清单。
     */
    lateinit var registry: io.legado.app.ai.tool.ToolRegistry
        private set

    /** 当前生效的配置（供 UI 状态栏显示） */
    val config: AiModelConfig? get() = lastConfig

    @Synchronized
    fun init() {
        if (initialized) return
        bridge = AiBridge(
            bookFetcher = DefaultBookFetcher(),
            chapterReader = DefaultChapterReader(),
            sourceAnalyzer = DefaultBookSourceAnalyzer(),
            appController = DefaultAppController(),
            sourceRuleWriter = DefaultSourceRuleWriter()
        )
        registry = buildRegistry(bridge)
        syncConfig()
    }

    /**
     * 按最新 [ModelManager.getConfig] 重建 runtime/client。
     * 配置未变化时为空操作，可安全地在 onResume 高频调用。
     */
    fun syncConfig() {
        synchronized(lock) {
            // 防御：历史坏数据(如键类型冲突)不应导致宿主页面崩溃，保持旧配置继续可用
            val cfg = runCatching { ModelManager.getConfig() }
                .onFailure { AiLog.e("Config", "读取配置失败，沿用旧配置", it) }
                .getOrElse { lastConfig }
                ?: run {
                    AiLog.i("Config", "无可用配置（未配置模型），跳过运行时重建")
                    return
                }
            if (initialized && cfg == lastConfig) return
            val client = OpenAIClient(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.name,
                timeoutMillis = cfg.timeoutMillis,
                textToolMode = cfg.toolProtocol == io.legado.app.ai.model.AiModelConfig.PROTOCOL_TEXT
            )
            runtime = AgentRuntime(
                client = client,
                registry = registry,
                maxRounds = cfg.maxRounds,
                maxTokens = 16_000L,
                // 确认窗口独立于请求超时：后台任务期间用户可能不在页面上，至少给 5 分钟
                confirmTimeoutMs = maxOf(cfg.timeoutMillis, 300_000L),
                preferStream = cfg.stream,
                toolProtocol = cfg.toolProtocol
            )
            lastConfig = cfg
            initialized = true
            AiLog.i(
                "Config",
                "运行时已重建: model=${cfg.name}, baseUrl=${cfg.baseUrl}, stream=${cfg.stream}, maxRounds=${cfg.maxRounds}, protocol=${cfg.toolProtocol}, key=${AiLog.mask(cfg.apiKey)}"
            )
        }
    }
}
