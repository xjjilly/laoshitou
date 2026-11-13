// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.gu.laoshitou.catalog.CatalogSettingsStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the two NAS credential sets (WebDAV + SQL catalog) to a fixed,
 * user-visible text file:
 *
 *   Download/老石头/NAS-凭据.txt   (Android 10+, via MediaStore)
 *   <app external files>/credentials/NAS-凭据.txt (Android 8/9 fallback)
 *
 * The tokens/passwords are decrypted in-process from the Android Keystore
 * backed stores; nothing is sent over the network. The report is plaintext by
 * design (the owner asked for a directly readable file), so the UI must warn
 * that it should not be shared or uploaded.
 */
object NasCredentialExporter {

    data class Result(
        val ok: Boolean,
        val path: String = "",
        val error: String = "",
    )

    fun export(context: Context): Result {
        return try {
            val catalog = CatalogSettingsStore(context).load()
            val webdav = runCatching { WebDavCredentialStore(context).load() }.getOrNull()
            val text = buildReport(catalog, webdav)
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(context, text)
            } else {
                writeToAppFiles(context, text)
            }
            Result(ok = true, path = path)
        } catch (e: Exception) {
            Result(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildReport(
        catalog: com.gu.laoshitou.catalog.CatalogSettings,
        webdav: SavedWebDavAccount?,
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val webdavUrl = webdav?.account?.baseUrl?.toString()?.takeIf { it.isNotBlank() } ?: "（尚未配置）"
        val webdavUser = webdav?.account?.username?.takeIf { it.isNotBlank() } ?: "（尚未配置）"
        val webdavPassword = webdav?.password?.takeIf { it.isNotEmpty() } ?: "（尚未配置）"
        val webdavAuth = webdav?.authType?.name ?: "（尚未配置）"
        val catalogUrl = catalog.baseUrl.takeIf { it.isNotBlank() } ?: "（尚未配置）"
        val catalogToken = catalog.token.takeIf { it.isNotBlank() } ?: "（尚未配置）"
        return buildString {
            appendLine("============================================================")
            appendLine("老石头 · NAS / SQL 曲库凭据（请妥善保管）")
            appendLine("导出时间：$timestamp")
            appendLine("来源：Android 老石头（Keystore 加密存储，应用内解密）")
            appendLine("============================================================")
            appendLine()
            appendLine("【一、WebDAV（NAS 音频拉流，默认端口 9005）】")
            appendLine("地址(URL) ：$webdavUrl")
            appendLine("用户名(ID)：$webdavUser")
            appendLine("密码      ：$webdavPassword")
            appendLine("认证方式  ：$webdavAuth")
            appendLine()
            appendLine("【二、SQL 曲库 API（默认端口 9007）】")
            appendLine("地址(URL) ：$catalogUrl")
            appendLine("Token     ：$catalogToken")
            appendLine()
            appendLine("【电脑端】")
            appendLine("老石头 PC → 设置 / NAS 曲库 → 分别填写 WebDAV 与 SQL 曲库两组凭据。")
            appendLine()
            appendLine("【手机端】")
            appendLine("老石头 Android → NAS / SQL 曲库页面 → 填写同一组 WebDAV 与 SQL 凭据。")
            appendLine()
            appendLine("【说明】")
            appendLine("- 本文件是明文，任何能读取它的程序或账号都能看到密码/Token。")
            appendLine("- 请不要放进网盘、代码库、聊天或截图。")
            appendLine("- 在 NAS 上轮换凭据后，回到本页重新保存，再导出一次即可刷新本文件。")
            appendLine("- 手机专用凭据（如与电脑不同）：")
            appendLine("  手机 WebDAV 用户名：")
            appendLine("  手机 WebDAV 密码  ：")
            appendLine("  手机 SQL Token    ：")
            appendLine()
            appendLine("============================================================")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(context: Context, text: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/老石头"
        val displayName = "NAS-凭据.txt"

        // Replace an older report instead of accumulating "(1)" copies.
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(displayName, "$relativePath%"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                resolver.delete(uri, null, null)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        resolver.openOutputStream(uri, "w")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("MediaStore openOutputStream failed")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "$relativePath/$displayName"
    }

    private fun writeToAppFiles(context: Context, text: String): String {
        val dir = File(context.getExternalFilesDir(null), "credentials")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建目录：${dir.absolutePath}")
        }
        val file = File(dir, "NAS-凭据.txt")
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }
}
