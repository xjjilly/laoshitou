// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.util.Log
import org.cybergarage.upnp.Action
import org.cybergarage.upnp.Service
import org.cybergarage.upnp.StateVariable
import org.cybergarage.upnp.control.ActionListener
import org.cybergarage.upnp.control.QueryListener

/** RenderingControl:1 volume/mute, mapped to the DMR player's app-local volume. */
internal class DmrRenderingControl(
    private val service: Service,
    private val backend: DmrPlaybackBackend,
) : ActionListener, QueryListener {

    private val lock = Any()
    private var volume = 50
    private var mute = false

    override fun actionControlReceived(action: Action): Boolean {
        val instanceId = action.getArgumentValue("InstanceID")?.toIntOrNull() ?: 0
        if (instanceId != 0) {
            action.setStatus(ERROR_INVALID_INSTANCE_ID, "Invalid InstanceID")
            return false
        }
        return when (action.name) {
            "GetVolume" -> {
                action.setArgumentValue("CurrentVolume", synchronized(lock) { volume })
                true
            }
            "SetVolume" -> {
                val desired = action.getArgumentValue("DesiredVolume")?.toIntOrNull()
                if (desired == null || desired !in 0..100) {
                    action.setStatus(ERROR_INVALID_ARGS, "Volume out of range")
                    false
                } else {
                    synchronized(lock) { volume = desired }
                    backend.setVolume(desired)
                    service.getStateVariable("Volume")?.setValue(desired)
                    true
                }
            }
            "GetMute" -> {
                action.setArgumentValue("CurrentMute", if (synchronized(lock) { mute }) "1" else "0")
                true
            }
            "SetMute" -> {
                val value = action.getArgumentValue("DesiredMute")
                if (value == null) {
                    action.setStatus(ERROR_INVALID_ARGS, "Missing DesiredMute")
                    false
                } else {
                    val desired = value == "1" || value.equals("true", ignoreCase = true)
                    synchronized(lock) { mute = desired }
                    backend.setMute(desired)
                    service.getStateVariable("Mute")?.setValue(if (desired) "1" else "0")
                    true
                }
            }
            else -> {
                Log.w(TAG, "不支持的 RenderingControl 动作: ${action.name}")
                action.setStatus(ERROR_INVALID_ACTION, "Unsupported action")
                false
            }
        }
    }

    override fun queryControlReceived(stateVar: StateVariable): Boolean {
        val value = when (stateVar.name) {
            "Volume" -> synchronized(lock) { volume }.toString()
            "Mute" -> if (synchronized(lock) { mute }) "1" else "0"
            else -> return false
        }
        stateVar.setValue(value)
        return true
    }

    private companion object {
        const val TAG = "M0Dlna"
        const val ERROR_INVALID_ACTION = 401
        const val ERROR_INVALID_ARGS = 402
        const val ERROR_INVALID_INSTANCE_ID = 718
    }
}
