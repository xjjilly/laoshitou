// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** Stable, per-role UPnP UDN derived from the installation's Android ID. */
internal object DlnaUdn {

    fun forRole(context: Context, role: String): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val seed = "laoshitou-$role:$androidId"
        return "uuid:" + UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))
    }
}
