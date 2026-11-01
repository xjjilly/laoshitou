// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.util.Log
import org.cybergarage.upnp.Action
import org.cybergarage.upnp.StateVariable
import org.cybergarage.upnp.control.ActionListener
import org.cybergarage.upnp.control.QueryListener

/** ConnectionManager:1 capability declaration for the MediaPlayer DMR. */
internal class DmrConnectionManager : ActionListener, QueryListener {

    override fun actionControlReceived(action: Action): Boolean = when (action.name) {
        "GetProtocolInfo" -> {
            action.setArgumentValue("Source", "")
            action.setArgumentValue("Sink", SINK_PROTOCOL_INFO)
            true
        }
        "GetCurrentConnectionIDs" -> {
            action.setArgumentValue("ConnectionIDs", "0")
            true
        }
        "GetCurrentConnectionInfo" -> {
            action.setArgumentValue("RcsID", "-1")
            action.setArgumentValue("AVTransportID", "-1")
            action.setArgumentValue("ProtocolInfo", "")
            action.setArgumentValue("PeerConnectionManager", "")
            action.setArgumentValue("PeerConnectionID", "-1")
            action.setArgumentValue("Direction", "Output")
            action.setArgumentValue("Status", "OK")
            true
        }
        "PrepareForConnection" -> {
            action.setStatus(701, "Transition not available")
            false
        }
        "ConnectionComplete" -> true
        else -> {
            Log.w(TAG, "不支持的 ConnectionManager 动作: ${action.name}")
            action.setStatus(401, "Unsupported action")
            false
        }
    }

    override fun queryControlReceived(stateVar: StateVariable): Boolean {
        val value = when (stateVar.name) {
            "SourceProtocolInfo" -> ""
            "SinkProtocolInfo" -> SINK_PROTOCOL_INFO
            "CurrentConnectionIDs" -> "0"
            else -> return false
        }
        stateVar.setValue(value)
        return true
    }

    private companion object {
        const val TAG = "M0Dlna"

        // Conservative set: only formats validated with Android MediaPlayer.
        val SINK_PROTOCOL_INFO = listOf(
            "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01",
            "http-get:*:audio/flac:DLNA.ORG_PN=FLAC;DLNA.ORG_OP=01",
            "http-get:*:audio/wav:DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01",
            "http-get:*:audio/mp4:DLNA.ORG_PN=AAC_ISO;DLNA.ORG_OP=01",
            "http-get:*:audio/aac:DLNA.ORG_PN=AAC_ADTS;DLNA.ORG_OP=01",
            "http-get:*:audio/ogg:DLNA.ORG_PN=OGG;DLNA.ORG_OP=01",
        ).joinToString(",")
    }
}
