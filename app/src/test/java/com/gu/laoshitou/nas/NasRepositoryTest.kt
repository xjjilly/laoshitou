// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NasRepositoryTest {
    private fun entry(name: String, collection: Boolean, size: Long? = null): WebDavEntry {
        val url: HttpUrl = "http://nas/$name".toHttpUrl()
        return WebDavEntry(
            url = url,
            path = "/$name",
            name = name,
            isCollection = collection,
            size = size,
        )
    }

    @Test
    fun filtersNonAudioAndSortsFoldersFirstNaturally() {
        val entries = listOf(
            entry("b.flac", false, 10),
            entry("A", true),
            entry("cover.jpg", false, 5),
            entry("a.wav", false, 20),
            entry("10.flac", false, 30),
            entry("2.flac", false, 40),
        )
        val visible = NasRepository.visible(entries)
        assertEquals(
            listOf("A", "2.flac", "10.flac", "a.wav", "b.flac"),
            visible.map { it.name },
        )
    }

    @Test
    fun dffIsListedButMarkedUnsupported() {
        val dff = entry("track.dff", false, 1)
        assertTrue(dff.isAudio)
        assertFalse(dff.playbackSupported)
    }

    @Test
    fun dsfIsSupported() {
        val dsf = entry("track.dsf", false, 1)
        assertTrue(dsf.isAudio)
        assertTrue(dsf.playbackSupported)
    }
}
