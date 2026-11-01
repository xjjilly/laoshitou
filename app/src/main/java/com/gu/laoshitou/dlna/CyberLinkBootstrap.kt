// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.cybergarage.upnp.UPnP

/** Installs the Android-compatible XML parser before any CyberLink device is used. */
internal object CyberLinkBootstrap {

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) {
            return
        }
        UPnP.setXMLParser(AndroidXmlParser())
        initialized = true
    }
}
