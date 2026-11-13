// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CatalogSettings(
    val baseUrl: String,
    val token: String,
)

/**
 * Keeps the SQL library endpoint and API token in SharedPreferences. The token
 * is encrypted by a dedicated Android Keystore key; it never lands in
 * plaintext and is never compiled into the APK.
 */
class CatalogSettingsStore(
    context: Context,
    private val prefsName: String = PREFS,
    private val keyAlias: String = KEY_ALIAS,
    private val defaultBase: String = DEFAULT_BASE,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(): CatalogSettings {
        val base = prefs.getString(KEY_BASE, null) ?: defaultBase
        val token = prefs.getString(KEY_TOKEN, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { decrypt(it) }.getOrNull() }
            ?: ""
        return CatalogSettings(base, token)
    }

    fun save(baseUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_BASE, baseUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, if (token.isEmpty()) "" else encrypt(token))
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(
            cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$iv:$body"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "invalid catalog token blob" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PREFS = "catalog_settings"
        const val KEY_BASE = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_ALIAS = "laoshitou_catalog_api"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_BASE = ""
    }
}
