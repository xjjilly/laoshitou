// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl

/**
 * Session-scoped NAS repository. P0 keeps one server connection and a lazy
 * folder view; it does not persist or mirror the remote tree.
 */
class NasRepository {
    private val connectMutex = Mutex()

    @Volatile
    private var client: WebDavClient? = null

    val connected: Boolean
        get() = client != null

    suspend fun connect(
        account: WebDavAccount,
        password: String,
        authType: WebDavAuthType,
    ): WebDavEntry = connectMutex.withLock {
        disconnect()
        val newClient = WebDavClient(account, password, authType)
        try {
            val root = newClient.testConnection()
            synchronized(this) { client = newClient }
            root
        } catch (e: Exception) {
            newClient.close()
            throw e
        }
    }

    suspend fun list(url: HttpUrl): List<WebDavEntry> {
        val active = synchronized(this) { client } ?: throw WebDavException("尚未连接 NAS")
        return active.list(url)
    }

    @Synchronized
    fun disconnect() {
        client?.close()
        client = null
    }

    companion object {
        const val MAX_VISIBLE_ENTRIES = 400

        fun visible(
            entries: List<WebDavEntry>,
            limit: Int = MAX_VISIBLE_ENTRIES,
        ): List<WebDavEntry> {
            val filtered = entries.filter { it.isCollection || it.isAudio }
            return filtered.sortedWith(COMPARATOR).take(limit)
        }

        private val COMPARATOR = Comparator<WebDavEntry> { left, right ->
            if (left.isCollection != right.isCollection) {
                if (left.isCollection) -1 else 1
            } else {
                naturalCompare(left.name, right.name)
            }
        }

        fun naturalCompare(left: String, right: String): Int {
            var i = 0
            var j = 0
            while (i < left.length && j < right.length) {
                val a = left[i]
                val b = right[j]
                if (a.isDigit() && b.isDigit()) {
                    val endA = digitEnd(left, i)
                    val endB = digitEnd(right, j)
                    val digitsA = left.substring(i, endA).trimStart('0')
                    val digitsB = right.substring(j, endB).trimStart('0')
                    if (digitsA.length != digitsB.length) {
                        return digitsA.length - digitsB.length
                    }
                    val compared = digitsA.compareTo(digitsB)
                    if (compared != 0) {
                        return compared
                    }
                    i = endA
                    j = endB
                } else {
                    val compared = a.lowercaseChar().compareTo(b.lowercaseChar())
                    if (compared != 0) {
                        return compared
                    }
                    i++
                    j++
                }
            }
            return (left.length - i) - (right.length - j)
        }

        private fun digitEnd(text: String, start: Int): Int {
            var end = start
            while (end < text.length && text[end].isDigit()) {
                end++
            }
            return end
        }
    }
}
