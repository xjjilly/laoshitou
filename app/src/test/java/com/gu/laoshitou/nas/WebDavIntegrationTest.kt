// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in live test against the fnOS Docker WebDAV services.
 *
 * Credentials are read from the environment and never committed:
 *   LAOSHITOU_NAS_URL, LAOSHITOU_NAS_USER, LAOSHITOU_NAS_PASS
 *   LAOSHITOU_NAS_DIGEST_URL (optional, defaults to LAOSHITOU_NAS_URL)
 */
class WebDavIntegrationTest {
    private fun env(name: String): String? =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    @Test
    fun basicListAgainstConfiguredNas() = runBlocking {
        val url = env("LAOSHITOU_NAS_URL")
        val user = env("LAOSHITOU_NAS_USER")
        val pass = env("LAOSHITOU_NAS_PASS")
        assumeTrue(
            "set LAOSHITOU_NAS_* to run live NAS test",
            url != null && user != null && pass != null,
        )
        val account = WebDavAccount(
            WebDavUrl.parseBase(requireNotNull(url)),
            requireNotNull(user),
        )
        val client = WebDavClient(
            account,
            requireNotNull(pass),
            WebDavAuthType.BASIC,
        )
        try {
            val root = client.testConnection()
            assertTrue(root.isCollection)
            val entries = client.list(root.url)
            assertTrue("expected at least one folder/audio entry", entries.isNotEmpty())
            assertTrue(entries.any { it.isCollection || it.isAudio })
        } finally {
            client.close()
        }
    }

    @Test
    fun rangeSkipReopensAgainstConfiguredNas() = runBlocking {
        val url = env("LAOSHITOU_NAS_URL")
        val user = env("LAOSHITOU_NAS_USER")
        val pass = env("LAOSHITOU_NAS_PASS")
        assumeTrue(
            "set LAOSHITOU_NAS_* to run live NAS test",
            url != null && user != null && pass != null,
        )
        val base = WebDavUrl.parseBase(requireNotNull(url))
        val account = WebDavAccount(base, requireNotNull(user))
        val client = WebDavClient(account, requireNotNull(pass), WebDavAuthType.BASIC)
        try {
            val albumUrl = base.newBuilder()
                .addPathSegment("musicok")
                .addPathSegment("周杰伦")
                .addPathSegment("Jay")
                .build()
            val entries = client.list(albumUrl)
            val flac = entries.firstOrNull { it.isAudio && (it.size ?: 0) > 4096 }
            assertTrue("expected a FLAC track in musicok/周杰伦/Jay", flac != null)
            val resource = WebDavResource(client, requireNotNull(flac))
            val stream = resource.openStream()
            try {
                val skipTo = resource.length - 8
                assertEquals(skipTo, stream.skip(skipTo))
                val tail = ByteArray(8)
                var read = 0
                while (read < tail.size) {
                    val count = stream.read(tail, read, tail.size - read)
                    if (count < 0) {
                        break
                    }
                    read += count
                }
                assertEquals(8, read)
                assertEquals(-1, stream.read())
            } finally {
                stream.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun digestListAgainstConfiguredNas() = runBlocking {
        val url = env("LAOSHITOU_NAS_DIGEST_URL") ?: env("LAOSHITOU_NAS_URL")
        val user = env("LAOSHITOU_NAS_USER")
        val pass = env("LAOSHITOU_NAS_PASS")
        assumeTrue(
            "set LAOSHITOU_NAS_* to run live NAS test",
            url != null && user != null && pass != null,
        )
        val account = WebDavAccount(
            WebDavUrl.parseBase(requireNotNull(url)),
            requireNotNull(user),
        )
        val client = WebDavClient(
            account,
            requireNotNull(pass),
            WebDavAuthType.DIGEST,
        )
        try {
            val root = client.testConnection()
            assertTrue(root.isCollection)
            val entries = client.list(root.url)
            assertTrue("expected at least one folder/audio entry", entries.isNotEmpty())
        } finally {
            client.close()
        }
    }
}
