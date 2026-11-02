// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal data class WebDavXmlEntry(
    val href: String,
    val displayName: String?,
    val isCollection: Boolean,
    val size: Long?,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
)

/**
 * Minimal PROPFIND `207 Multi-Status` parser.
 *
 * Namespace prefixes vary between servers (`D:`, `d:`, default namespace), so
 * matching is done on the local tag name rather than a fixed prefix. Only
 * properties from a `200 OK` propstat are used; a 404 propstat must not erase
 * a valid property from another block.
 */
internal object WebDavXml {
    fun parseMultiStatus(xml: String): List<WebDavXmlEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching { isXIncludeAware = false }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement ?: return emptyList()
        return collectChildren(root, "response").mapNotNull(::parseResponse)
    }

    private fun parseResponse(response: Element): WebDavXmlEntry? {
        val href = childText(response, "href")?.trim().orEmpty()
        if (href.isEmpty()) {
            return null
        }
        var displayName: String? = null
        var isCollection = false
        var size: Long? = null
        var contentType: String? = null
        var etag: String? = null
        var lastModified: String? = null

        val propstats = collectChildren(response, "propstat")
        val blocks: List<Element> = if (propstats.isNotEmpty()) propstats else listOf(response)
        for (block in blocks) {
            val status = childText(block, "status")
            if (status != null && !status.contains("200")) {
                continue
            }
            val prop = firstChild(block, "prop") ?: block
            displayName = displayName ?: childText(prop, "displayname")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (!isCollection) {
                isCollection = collectChildren(prop, "resourcetype")
                    .any { resType -> collectChildren(resType, "collection").isNotEmpty() }
            }
            if (size == null) {
                size = childText(prop, "getcontentlength")?.trim()?.toLongOrNull()
            }
            if (contentType == null) {
                contentType = childText(prop, "getcontenttype")?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (etag == null) {
                etag = childText(prop, "getetag")?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (lastModified == null) {
                lastModified = childText(prop, "getlastmodified")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        return WebDavXmlEntry(
            href = href,
            displayName = displayName,
            isCollection = isCollection,
            size = size,
            contentType = contentType,
            etag = etag,
            lastModified = lastModified,
        )
    }

    private fun collectChildren(parent: Node, localName: String): List<Element> {
        val children = parent.childNodes
        val result = ArrayList<Element>()
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node is Element && node.localTagName().equals(localName, ignoreCase = true)) {
                result += node
            }
        }
        return result
    }

    private fun firstChild(parent: Node, localName: String): Element? =
        collectChildren(parent, localName).firstOrNull()

    private fun childText(parent: Node, localName: String): String? =
        firstChild(parent, localName)?.textContent

    private fun Element.localTagName(): String = nodeName.substringAfter(':')
}
