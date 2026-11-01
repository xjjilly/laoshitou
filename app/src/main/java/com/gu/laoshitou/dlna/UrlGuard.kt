// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** A URL that would leave the local network or use an unsupported scheme. */
internal class UrlGuardException(message: String) : Exception(message)

/**
 * Injectable URL policy. Production code uses [DEFAULT] (i.e. [UrlGuard]);
 * tests may supply a permissive policy for a loopback server.
 */
internal class UrlPolicy(
    val requireAllowed: (String) -> URI,
    val resolveRedirect: (URI, String) -> URI,
) {
    companion object {
        val DEFAULT = UrlPolicy(UrlGuard::requireAllowed, UrlGuard::resolveRedirect)
    }
}

/**
 * Enforces the DLNA network boundary.
 *
 * Only cleartext HTTP to an IPv4 literal inside RFC1918 space is allowed.
 * Hostnames are deliberately rejected so a DNS answer cannot move a request
 * outside the LAN; redirects must be validated hop by hop by the caller.
 */
internal object UrlGuard {
    const val MAX_URL_LENGTH = 4096
    const val MAX_REDIRECTS = 3

    private val ipv4Literal = Regex(
        """^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$"""
    )

    /** Parses and validates [raw], returning the normalized URI. */
    fun requireAllowed(raw: String): URI {
        if (raw.isEmpty()) {
            throw UrlGuardException("URL 为空")
        }
        if (raw.length > MAX_URL_LENGTH) {
            throw UrlGuardException("URL 过长")
        }
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            throw UrlGuardException("URL 语法错误: ${e.reason}")
        }
        validate(uri)
        return uri
    }

    /** Validates an already parsed [uri]. */
    fun validate(uri: URI) {
        if (!"http".equals(uri.scheme, ignoreCase = true)) {
            throw UrlGuardException("只允许 http://")
        }
        if (uri.rawUserInfo != null) {
            throw UrlGuardException("不允许 URL 携带用户信息")
        }
        if (uri.rawFragment != null) {
            throw UrlGuardException("不允许 URL 片段")
        }

        val host = uri.host ?: throw UrlGuardException("缺少主机名")
        if (!ipv4Literal.matches(host)) {
            throw UrlGuardException("主机必须是 IPv4 字面量")
        }

        // A literal never triggers a DNS lookup; the regex above also rejects
        // octal/hex/leading-zero forms before they reach InetAddress.
        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            throw UrlGuardException("无法解析主机")
        }
        if (address !is Inet4Address) {
            throw UrlGuardException("只允许 IPv4")
        }
        if (!address.isSiteLocalAddress) {
            throw UrlGuardException("只允许 RFC1918 私有地址")
        }
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isMulticastAddress
        ) {
            throw UrlGuardException("禁止的地址类型")
        }

        val port = uri.port
        if (port == 0 || port > 65535) {
            throw UrlGuardException("端口无效")
        }
    }

    /**
     * Resolves a redirect [location] against [current] and validates the
     * result. Relative redirects stay on the same private host.
     */
    fun resolveRedirect(current: URI, location: String): URI {
        if (location.isEmpty()) {
            throw UrlGuardException("重定向地址为空")
        }
        if (location.length > MAX_URL_LENGTH) {
            throw UrlGuardException("重定向地址过长")
        }
        val next = try {
            current.resolve(location)
        } catch (e: IllegalArgumentException) {
            throw UrlGuardException("重定向地址语法错误")
        }
        validate(next)
        return next
    }
}
