// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

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

data class SavedWebDavAccount(
    val account: WebDavAccount,
    val password: String,
    val authType: WebDavAuthType,
)

/**
 * Keeps the single P0 WebDAV profile in SharedPreferences, with the password
 * encrypted by an Android Keystore AES/GCM key. The base URL and username are
 * not secrets; the password never lands in plaintext.
 */
class WebDavCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(account: WebDavAccount, password: String, authType: WebDavAuthType) {
        prefs.edit()
            .putString(KEY_BASE, account.baseUrl.toString())
            .putString(KEY_USER, account.username)
            .putString(KEY_AUTH, authType.name)
            .putString(KEY_PASS, encrypt(password))
            .apply()
    }

    fun load(): SavedWebDavAccount? {
        val base = prefs.getString(KEY_BASE, null) ?: return null
        val username = prefs.getString(KEY_USER, null) ?: return null
        val encrypted = prefs.getString(KEY_PASS, null) ?: return null
        val password = try {
            decrypt(encrypted)
        } catch (_: Exception) {
            return null
        }
        val baseUrl = try {
            WebDavUrl.parseBase(base)
        } catch (_: Exception) {
            return null
        }
        val authType = runCatching {
            WebDavAuthType.valueOf(prefs.getString(KEY_AUTH, null) ?: WebDavAuthType.AUTO.name)
        }.getOrDefault(WebDavAuthType.AUTO)
        return SavedWebDavAccount(WebDavAccount(baseUrl, username), password, authType)
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
        require(parts.size == 2) { "invalid credential blob" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PREFS = "nas_webdav"
        const val KEY_BASE = "base_url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_AUTH = "auth_type"
        const val KEY_ALIAS = "laoshitou_nas_webdav"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
