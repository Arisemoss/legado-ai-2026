package io.legado.app.ai.runtime

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import io.legado.app.App
import io.legado.app.ai.log.AiLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 安全存储。用 Android Keystore 的 AES/GCM 主密钥加密 `ai_api_key`，
 * 密文存到独立的 pref key；主密钥不可用（低版本/异常）时回退明本并打日志。
 */
object AiKeyStore {

    private const val TAG = "AiKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "legado_ai_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    // 密文独立存放，避免与明本 key 冲突
    private const val ENC_KEY = "ai_api_key_enc"

    fun getApiKey(): String {
        App.INSTANCE.getPrefString(ENC_KEY)?.let { enc ->
            if (enc.isNotBlank()) {
                decrypt(enc)?.let { return it }
                // 解密失败，回退明本（可能为不可用主密钥遗留数据）
                AiLog.w("KeyStore", "解密 ai_api_key 失败，回退明文")
            }
        }
        // 无密文：读明文；明文有效则顺手迁移加密（自愈历史数据，如设置页直写或旧版本遗留）
        val plain = App.INSTANCE.getPrefString(PreferKey.aiApiKey).orEmpty()
        if (plain.isBlank()) return ""
        encrypt(plain)?.let { enc ->
            App.INSTANCE.putPrefString(ENC_KEY, enc)
            App.INSTANCE.putPrefString(PreferKey.aiApiKey, "")
            AiLog.i("KeyStore", "已将明文 API Key 迁移至加密存储")
        }
        return plain
    }

    fun putApiKey(value: String) {
        val ctx = App.INSTANCE
        if (value.isBlank()) {
            ctx.putPrefString(ENC_KEY, "")
            ctx.putPrefString(PreferKey.aiApiKey, "")
            return
        }
        encrypt(value)?.let { enc ->
            ctx.putPrefString(ENC_KEY, enc)
            ctx.putPrefString(PreferKey.aiApiKey, "") // 已迁移，清掉明本
        } ?: run {
            AiLog.w("KeyStore", "主密钥不可用，ai_api_key 以明文存储")
            ctx.putPrefString(ENC_KEY, "")
            ctx.putPrefString(PreferKey.aiApiKey, value)
        }
    }

    // —— Keystore AES/GCM ——

    private fun encrypt(value: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "加密失败", it) }.getOrNull()
    }

    private fun decrypt(encoded: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        }.onFailure { Log.w(TAG, "解密失败", it) }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return kg.generateKey()
    }
}