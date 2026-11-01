// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime-permission contract for DLNA.
 *
 * NEARBY_WIFI_DEVICES is required from API 33 when reading Wi-Fi details or
 * scan results. The current SSDP path uses MulticastLock, but the release
 * role flow requests this permission before starting a DLNA session so future
 * Wi-Fi APIs remain available.
 */
internal object DlnaPermissions {

    fun runtimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyList()
        }

    fun missing(context: Context): List<String> = runtimePermissions().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
