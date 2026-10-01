package com.lilac.anime.data.subtitle.translation

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys are encrypted with an AES-GCM key held by Android Keystore. No plaintext key is persisted. */
object SecureApiKeyStore {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "LilacAnime.TranslationApiKeys"
    private const val PREF = "translation_secure_keys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance("AES", KS)
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build())
        return gen.generateKey()
    }

    fun put(context: Context, provider: String, value: String) {
        if (value.isBlank()) { remove(context, provider); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(provider, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun get(context: Context, provider: String): String? = runCatching {
        val encoded = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(provider, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun remove(context: Context, provider: String) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(provider).apply()
    fun has(context: Context, provider: String): Boolean = !get(context, provider).isNullOrBlank()
}
