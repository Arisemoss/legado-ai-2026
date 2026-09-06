package io.legado.app.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.*
import io.legado.app.ai.runtime.AiKeyStore
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "ModelManager"

/**
 * 类型安全的配置读取。SharedPreferences 的同一个键一旦被不同类型的控件写过
 * （如 SwitchPreference 写 Boolean、EditTextPreference 写 String），
 * 再用错误类型读取就会抛 ClassCastException 击穿宿主页面。
 * 这里逐字段防御：读取失败即移除坏数据回退默认值，自愈一次后恢复正常，
 * 避免单个坏配置项导致 AI 整体静默失效。
 */
private fun Context.prefStringSafe(key: String, defValue: String? = null): String? =
    runCatching { getPrefString(key) }
        .onFailure {
            removePref(key)
            Log.w(TAG, "配置项 $key 类型异常，已重置为默认", it)
        }
        .getOrNull() ?: defValue

private fun Context.prefBooleanSafe(key: String, defValue: Boolean = false): Boolean =
    runCatching { getPrefBoolean(key, defValue) }
        .onFailure {
            removePref(key)
            Log.w(TAG, "配置项 $key 类型异常，已重置为默认", it)
        }
        .getOrDefault(defValue)

/**
 * 管理 AI 模型的配置与 API 调用
 */
object ModelManager {

    private val gson = Gson()
    private val jsonMediaType = MediaType.parse("application/json; charset=utf-8")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun getConfig(): AiModelConfig {
        val prefs = io.legado.app.App.INSTANCE
        val baseUrl = prefs.prefStringSafe(PreferKey.aiBaseUrl)
            ?: "https://api.openai.com/v1"
        val model = prefs.prefStringSafe(PreferKey.aiModel) ?: "gpt-4o-mini"
        return AiModelConfig(
            name = model,
            baseUrl = baseUrl,
            apiKey = AiKeyStore.getApiKey(),
            stream = prefs.prefBooleanSafe(PreferKey.aiStream),
            timeoutMillis = prefs.prefStringSafe(PreferKey.aiTimeout)?.toLongOrNull() ?: 120_000L,
            maxRounds = prefs.prefStringSafe(PreferKey.aiMaxRounds)?.toIntOrNull() ?: 5,
            sessionWindow = prefs.prefStringSafe(PreferKey.aiSessionWindow)?.toIntOrNull() ?: 50,
            toolProtocol = prefs.prefStringSafe(PreferKey.aiToolProtocol, AiModelConfig.PROTOCOL_AUTO)
                ?: AiModelConfig.PROTOCOL_AUTO
        )
    }

    fun saveConfig(config: AiModelConfig) {
        val prefs = io.legado.app.App.INSTANCE
        prefs.putPrefString(PreferKey.aiBaseUrl, config.baseUrl)
        AiKeyStore.putApiKey(config.apiKey)
        prefs.putPrefString(PreferKey.aiModel, config.name)
        io.legado.app.ai.log.AiLog.i(
            "Config",
            "保存配置: model=${config.name}, baseUrl=${config.baseUrl}, key=${io.legado.app.ai.log.AiLog.mask(config.apiKey)}"
        )
    }

    /**
     * 发送非流式聊天请求
     */
    suspend fun chatCompletion(
        request: ChatCompletionRequest,
        config: AiModelConfig = getConfig()
    ): ChatCompletionResponse? {
        if (config.apiKey.isBlank()) {
            return null
        }

        return try {
            val jsonBody = gson.toJson(request)
            val url = "${config.baseUrl.trimEnd('/')}/chat/completions"

            val body = RequestBody.create(jsonMediaType, jsonBody)
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body()?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    gson.fromJson(responseBody, ErrorResponse::class.java)?.message
                } catch (_: Exception) { null }
                throw RuntimeException(errorMsg ?: "HTTP ${response.code()}: $responseBody")
            }

            gson.fromJson(responseBody, ChatCompletionResponse::class.java)
        } catch (e: Exception) {
            throw e
        }
    }
}