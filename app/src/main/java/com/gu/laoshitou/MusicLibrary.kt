// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou

import java.io.File

internal val AUDIO_EXTENSIONS = setOf(
    "flac",
    "wav",
    "w64",
    "aif",
    "aiff",
    "caf",
    "alac",
    "m4a",
    "aac",
    "mp3",
    "ogg",
    "opus",
    "ape",
    "wv",
    "tta",
    "dsf",
    "dff",
)

private const val MAX_SCAN_DEPTH = 8

/** One album: a folder directly containing playable audio files. */
data class AlbumFolder(
    val name: String,
    val relativePath: String,
    val files: List<File>,
)

/**
 * Recursively collects playable audio files below [root] and groups them by
 * their containing folder. Each folder that directly contains audio files is
 * one album named after that folder ("未分类" for files directly in [root]).
 * Albums and tracks are ordered naturally (01, 02, ..., 10, ...).
 */
fun scanMusicLibrary(root: File): List<AlbumFolder> {
    if (!root.isDirectory) return emptyList()

    val files = ArrayList<File>()
    walk(root, 0, files)

    val grouped = LinkedHashMap<File, MutableList<File>>()
    for (file in files) {
        val parent = file.parentFile ?: continue
        grouped.getOrPut(parent) { mutableListOf() }.add(file)
    }

    return grouped.map { (directory, albumFiles) ->
        val relative = relativePath(root, directory)
        AlbumFolder(
            name = if (relative.isEmpty()) "未分类" else directory.name,
            relativePath = relative,
            files = albumFiles.sortedWith { left, right ->
                naturalCompare(left.name, right.name)
            },
        )
    }.sortedWith { left, right ->
        naturalCompare(left.relativePath, right.relativePath)
    }
}

private fun walk(directory: File, depth: Int, out: MutableList<File>) {
    if (depth > MAX_SCAN_DEPTH) return
    val children = directory.listFiles() ?: return
    for (child in children) {
        if (child.name.startsWith(".")) continue
        if (child.isDirectory) {
            walk(child, depth + 1, out)
        } else if (child.extension.lowercase() in AUDIO_EXTENSIONS) {
            out.add(child)
        }
    }
}

private fun relativePath(root: File, file: File): String {
    val rootPath = root.absolutePath.trimEnd(File.separatorChar)
    val filePath = file.absolutePath
    return if (filePath.startsWith(rootPath + File.separator)) {
        filePath.substring(rootPath.length + 1)
    } else {
        filePath
    }
}

private fun naturalCompare(left: String, right: String): Int {
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
            if (compared != 0) return compared
            i = endA
            j = endB
        } else {
            val compared = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (compared != 0) return compared
            i++
            j++
        }
    }
    return (left.length - i) - (right.length - j)
}

private fun digitEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length && text[end].isDigit()) end++
    return end
}
