// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.cybergarage.xml.Node
import org.cybergarage.xml.Parser
import org.cybergarage.xml.ParserException
import org.w3c.dom.Document
import org.w3c.dom.NamedNodeMap
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CyberLink's JaxpParser sets several Apache-specific parser features that
 * Android's DocumentBuilderFactory does not support, so description parsing
 * fails and silently leaves the device node null. This parser keeps the DOM
 * to CyberLink [Node] conversion but uses only portable JAXP features and
 * rejects DOCTYPE/external entities explicitly.
 */
internal class AndroidXmlParser : Parser() {

    override fun parse(inStream: InputStream): Node {
        val bytes = inStream.readBytes()
        if (bytes.size > MAX_XML_BYTES) {
            throw ParserException("XML 过大")
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true)) {
            throw ParserException("不允许 DOCTYPE")
        }

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        try {
            factory.isXIncludeAware = false
        } catch (_: UnsupportedOperationException) {
            // Not every Android JAXP implementation exposes XInclude.
        }
        trySetAttribute(factory, "http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        trySetAttribute(factory, "http://javax.xml.XMLConstants/property/accessExternalSchema", "")

        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("外部实体被拒绝") }
        val document: Document = builder.parse(InputSource(ByteArrayInputStream(bytes)))
        val rootElement = document.documentElement ?: throw ParserException("XML 无根节点")
        return convert(null, rootElement) ?: throw ParserException("XML 解析失败")
    }

    private fun convert(
        parentNode: Node?,
        domNode: org.w3c.dom.Node?,
    ): Node? {
        if (domNode == null) {
            return parentNode
        }
        if (domNode.nodeType == org.w3c.dom.Node.TEXT_NODE) {
            parentNode?.addValue(domNode.nodeValue)
            return parentNode
        }
        if (domNode.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
            return parentNode
        }

        val node = Node()
        node.setName(domNode.nodeName)
        node.setValue(domNode.nodeValue)
        parentNode?.addNode(node)

        val attributes: NamedNodeMap? = domNode.attributes
        if (attributes != null) {
            for (i in 0 until attributes.length) {
                val attribute = attributes.item(i)
                if (attribute != null) {
                    node.setAttribute(attribute.nodeName, attribute.nodeValue)
                }
            }
        }

        var child = domNode.firstChild
        if (child == null) {
            node.setValue("")
            return node
        }
        while (child != null) {
            convert(node, child)
            child = child.nextSibling
        }
        return node
    }

    private fun trySetFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
            // Optional on Android; the DOCTYPE/entity resolver guards remain.
        }
    }

    private fun trySetAttribute(factory: DocumentBuilderFactory, name: String, value: String) {
        try {
            factory.setAttribute(name, value)
        } catch (_: IllegalArgumentException) {
            // Optional on Android; the DOCTYPE/entity resolver guards remain.
        }
    }

    private companion object {
        const val MAX_XML_BYTES = 1_048_576
    }
}
