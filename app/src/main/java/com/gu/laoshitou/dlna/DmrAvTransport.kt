// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.util.Log
import org.cybergarage.upnp.Action
import org.cybergarage.upnp.Service
import org.cybergarage.upnp.StateVariable
import org.cybergarage.upnp.control.ActionListener
import org.cybergarage.upnp.control.QueryListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Minimal but standards-shaped AVTransport:1 implementation on top of
 * [MediaPlayerBackend]. Input URIs are validated before they reach the
 * backend; errors are returned as UPnP error codes instead of exceptions.
 */
internal class DmrAvTransport(
    private val service: Service,
    private val backend: DmrPlaybackBackend,
) : ActionListener, QueryListener, MediaPlayerBackend.Listener {

    private val lock = Any()
    private val eventExecutor = Executors.newSingleThreadExecutor()

    /** Optional observer for the UI/media session (called on backend state changes). */
    var stateListener: ((MediaPlayerBackend.Snapshot) -> Unit)? = null

    private var transportState = "STOPPED"
    private var currentUri: String? = null
    private var currentMetadata = ""
    private var nextUri: String? = null
    private var nextMetadata = ""
    private var playMode = "NORMAL"
    private var errorText: String? = null
    /** Set after SetAVTransportURI/Stop; the next Play must start a new session. */
    private var needsFreshPlay = true

    init {
        backend.listener = this
        publishState()
    }

    override fun onStateChanged(snapshot: MediaPlayerBackend.Snapshot) {
        val mapped = when (snapshot.state) {
            MediaPlayerBackend.State.STOPPED -> "STOPPED"
            MediaPlayerBackend.State.PLAYING -> "PLAYING"
            MediaPlayerBackend.State.PAUSED -> "PAUSED_PLAYBACK"
            MediaPlayerBackend.State.TRANSITIONING -> "TRANSITIONING"
            MediaPlayerBackend.State.ERROR -> "STOPPED"
        }
        synchronized(lock) {
            transportState = mapped
            errorText = snapshot.error
        }
        publishState()
        stateListener?.invoke(snapshot)

        // Natural completion (position reached duration) advances to the URI
        // staged via SetNextAVTransportURI. An explicit Stop resets position
        // to 0 and must not advance.
        if (mapped == "STOPPED" &&
            snapshot.durationMs > 0 &&
            snapshot.positionMs >= snapshot.durationMs
        ) {
            val next = synchronized(lock) { nextUri }
            if (next != null) {
                advanceTo(next)
            }
        }
    }

    private fun advanceTo(uri: String) {
        synchronized(lock) {
            currentUri = uri
            nextUri = null
            nextMetadata = ""
            needsFreshPlay = false
            transportState = "TRANSITIONING"
        }
        publishState()
        backend.play(uri)
    }

    /** Title parsed from the DIDL-Lite metadata, or null when unavailable. */
    fun currentTitle(): String? {
        val metadata = synchronized(lock) { currentMetadata }
        val match = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
            .find(metadata)
        val title = match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null) {
            return title
        }
        val uri = synchronized(lock) { currentUri } ?: return null
        return uri.substringAfterLast('/').substringBefore('?').ifEmpty { null }
    }

    fun playNow(): Boolean {
        val uri = synchronized(lock) { currentUri } ?: return false
        val fresh = synchronized(lock) {
            val value = needsFreshPlay
            needsFreshPlay = false
            value
        }
        if (fresh) {
            synchronized(lock) { transportState = "TRANSITIONING" }
            publishState()
            backend.play(uri)
            return true
        }
        when (backend.snapshot().state) {
            MediaPlayerBackend.State.PLAYING,
            MediaPlayerBackend.State.TRANSITIONING,
            -> return true
            MediaPlayerBackend.State.PAUSED -> {
                backend.resume()
                return true
            }
            else -> {
                synchronized(lock) { transportState = "TRANSITIONING" }
                publishState()
                backend.play(uri)
                return true
            }
        }
    }

    fun pauseNow(): Boolean {
        val state = backend.snapshot().state
        if (state != MediaPlayerBackend.State.PLAYING && state != MediaPlayerBackend.State.PAUSED) {
            return false
        }
        backend.pause()
        return true
    }

    fun stopNow() {
        backend.stop()
        synchronized(lock) {
            transportState = "STOPPED"
            needsFreshPlay = true
        }
        publishState()
    }

    fun seekNow(positionMs: Long) {
        if (synchronized(lock) { currentUri } != null) {
            backend.seekTo(positionMs)
        }
    }

    override fun actionControlReceived(action: Action): Boolean {
        val instanceId = action.getArgumentValue(INSTANCE_ID)?.toIntOrNull() ?: 0
        if (instanceId != 0) {
            action.setStatus(ERROR_INVALID_INSTANCE_ID, "Invalid InstanceID")
            return false
        }
        return when (action.name) {
            SET_AV_TRANSPORT_URI -> setAvTransportUri(action)
            SET_NEXT_AV_TRANSPORT_URI -> setNextAvTransportUri(action)
            PLAY -> play(action)
            PAUSE -> pause(action)
            STOP -> stop()
            SEEK -> seek(action)
            NEXT -> next(action)
            PREVIOUS -> fail(action, ERROR_TRANSITION_NOT_AVAILABLE, "No previous track")
            GET_MEDIA_INFO -> getMediaInfo(action)
            GET_TRANSPORT_INFO -> getTransportInfo(action)
            GET_POSITION_INFO -> getPositionInfo(action)
            GET_DEVICE_CAPABILITIES -> getDeviceCapabilities(action)
            GET_TRANSPORT_SETTINGS -> getTransportSettings(action)
            GET_CURRENT_TRANSPORT_ACTIONS -> getCurrentTransportActions(action)
            SET_PLAY_MODE -> setPlayMode(action)
            else -> fail(action, ERROR_INVALID_ACTION, "Unsupported action: ${action.name}")
        }
    }

    override fun queryControlReceived(stateVar: StateVariable): Boolean {
        val value = when (stateVar.name) {
            "TransportState" -> synchronized(lock) { transportState }
            "TransportStatus" -> "OK"
            "CurrentTransportActions" -> currentActions()
            "CurrentPlayMode" -> synchronized(lock) { playMode }
            else -> return false
        }
        stateVar.setValue(value)
        return true
    }

    fun release() {
        backend.listener = null
        eventExecutor.shutdownNow()
    }

    private fun setAvTransportUri(action: Action): Boolean {
        val uri = action.getArgumentValue(CURRENT_URI).orEmpty()
        val metadata = action.getArgumentValue(CURRENT_URI_METADATA).orEmpty()
        val validated = try {
            UrlGuard.requireAllowed(uri)
        } catch (e: UrlGuardException) {
            Log.w(TAG, "DMR 拒绝 URI (${e.message})")
            return fail(action, ERROR_ILLEGAL_URI, "Only LAN http URLs are allowed")
        }
        backend.stop()
        synchronized(lock) {
            currentUri = validated.toString()
            currentMetadata = metadata
            nextUri = null
            nextMetadata = ""
            transportState = "STOPPED"
            errorText = null
            needsFreshPlay = true
        }
        publishState()
        return true
    }

    private fun setNextAvTransportUri(action: Action): Boolean {
        val uri = action.getArgumentValue(NEXT_URI).orEmpty()
        val metadata = action.getArgumentValue(NEXT_URI_METADATA).orEmpty()
        val validated = try {
            UrlGuard.requireAllowed(uri)
        } catch (e: UrlGuardException) {
            Log.w(TAG, "DMR 拒绝 Next URI (${e.message})")
            return fail(action, ERROR_ILLEGAL_URI, "Only LAN http URLs are allowed")
        }
        synchronized(lock) {
            nextUri = validated.toString()
            nextMetadata = metadata
        }
        return true
    }

    private fun play(action: Action): Boolean {
        val speed = action.getArgumentValue("Speed")
        if (speed != null && speed != "1") {
            return fail(action, ERROR_PLAY_SPEED_NOT_SUPPORTED, "Only speed 1 is supported")
        }
        if (playNow()) {
            return true
        }
        return fail(action, ERROR_TRANSITION_NOT_AVAILABLE, "No media selected")
    }

    private fun pause(action: Action): Boolean {
        if (pauseNow()) {
            return true
        }
        return fail(action, ERROR_TRANSITION_NOT_AVAILABLE, "Not playing")
    }

    private fun stop(): Boolean {
        stopNow()
        return true
    }

    private fun seek(action: Action): Boolean {
        val unit = action.getArgumentValue("Unit").orEmpty()
        val target = action.getArgumentValue("Target").orEmpty()
        if (synchronized(lock) { currentUri } == null) {
            return fail(action, ERROR_TRANSITION_NOT_AVAILABLE, "No media selected")
        }
        val position = when (unit) {
            "REL_TIME", "ABS_TIME" -> parseTime(target)
            "TRACK_NR" -> if (target == "1") 0L else null
            else -> null
        } ?: return fail(action, ERROR_ILLEGAL_SEEK_TARGET, "Unsupported seek target")
        backend.seekTo(position)
        return true
    }

    private fun next(action: Action): Boolean {
        val next = synchronized(lock) {
            nextUri ?: return fail(action, ERROR_TRANSITION_NOT_AVAILABLE, "No next track")
        }
        advanceTo(next)
        return true
    }

    private fun getMediaInfo(action: Action): Boolean {
        val snapshot = backend.snapshot()
        val hasMedia = synchronized(lock) { currentUri != null }
        action.setArgumentValue("NrTracks", if (hasMedia) "1" else "0")
        action.setArgumentValue("MediaDuration", formatTime(snapshot.durationMs))
        action.setArgumentValue("CurrentURI", synchronized(lock) { currentUri }.orEmpty())
        action.setArgumentValue("CurrentURIMetaData", synchronized(lock) { currentMetadata })
        action.setArgumentValue("NextURI", synchronized(lock) { nextUri }.orEmpty())
        action.setArgumentValue("NextURIMetaData", synchronized(lock) { nextMetadata })
        action.setArgumentValue("PlayMedium", "NETWORK")
        action.setArgumentValue("RecordMedium", "NOT_IMPLEMENTED")
        action.setArgumentValue("WriteStatus", "NOT_IMPLEMENTED")
        return true
    }

    private fun getTransportInfo(action: Action): Boolean {
        action.setArgumentValue("CurrentTransportState", synchronized(lock) { transportState })
        action.setArgumentValue("CurrentTransportStatus", "OK")
        action.setArgumentValue("CurrentSpeed", "1")
        return true
    }

    private fun getPositionInfo(action: Action): Boolean {
        val snapshot = backend.snapshot()
        val duration = formatTime(snapshot.durationMs)
        val position = formatTime(snapshot.positionMs)
        action.setArgumentValue("Track", "1")
        action.setArgumentValue("TrackDuration", duration)
        action.setArgumentValue("TrackMetaData", synchronized(lock) { currentMetadata })
        action.setArgumentValue("TrackURI", synchronized(lock) { currentUri }.orEmpty())
        action.setArgumentValue("RelTime", position)
        action.setArgumentValue("AbsTime", position)
        action.setArgumentValue("RelCount", "0")
        action.setArgumentValue("AbsCount", "0")
        return true
    }

    private fun getDeviceCapabilities(action: Action): Boolean {
        action.setArgumentValue("PlayMedia", "NETWORK")
        action.setArgumentValue("RecMedia", "NOT_IMPLEMENTED")
        action.setArgumentValue("RecQualityModes", "NOT_IMPLEMENTED")
        return true
    }

    private fun getTransportSettings(action: Action): Boolean {
        action.setArgumentValue("PlayMode", synchronized(lock) { playMode })
        action.setArgumentValue("RecQualityMode", "NOT_IMPLEMENTED")
        return true
    }

    private fun getCurrentTransportActions(action: Action): Boolean {
        action.setArgumentValue("Actions", currentActions())
        return true
    }

    private fun setPlayMode(action: Action): Boolean {
        val mode = action.getArgumentValue("NewPlayMode").orEmpty()
        if (mode !in PLAY_MODES) {
            return fail(action, ERROR_INVALID_ARGS, "Unsupported play mode")
        }
        synchronized(lock) { playMode = mode }
        return true
    }

    private fun currentActions(): String {
        val hasMedia = synchronized(lock) { currentUri != null }
        return if (hasMedia) "Play,Stop,Pause,Seek" else "Stop"
    }

    private fun publishState() {
        val state = synchronized(lock) { transportState }
        val actions = currentActions()
        val lastChange = buildString {
            append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">")
            append("<InstanceID val=\"0\">")
            append("<TransportState val=\"").append(state).append("\"/>")
            append("<TransportStatus val=\"OK\"/>")
            append("<CurrentTransportActions val=\"").append(actions).append("\"/>")
            append("</InstanceID></Event>")
        }
        try {
            eventExecutor.execute {
                try {
                    service.getStateVariable("TransportState")?.setValue(state)
                    service.getStateVariable("LastChange")?.setValue(lastChange)
                } catch (e: Exception) {
                    Log.w(TAG, "发布 AVTransport 状态失败: ${e.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Device is shutting down.
        }
    }

    private fun fail(action: Action, code: Int, message: String): Boolean {
        Log.w(TAG, "DMR 动作失败 ${action.name}: $code $message")
        action.setStatus(code, message)
        return false
    }

    private fun parseTime(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) {
            return null
        }
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        if (minutes !in 0..59 || seconds !in 0..59) {
            return null
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000).toInt()
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private companion object {
        const val TAG = "M0Dlna"

        const val INSTANCE_ID = "InstanceID"
        const val CURRENT_URI = "CurrentURI"
        const val CURRENT_URI_METADATA = "CurrentURIMetaData"
        const val NEXT_URI = "NextURI"
        const val NEXT_URI_METADATA = "NextURIMetaData"
        const val SET_AV_TRANSPORT_URI = "SetAVTransportURI"
        const val SET_NEXT_AV_TRANSPORT_URI = "SetNextAVTransportURI"
        const val PLAY = "Play"
        const val PAUSE = "Pause"
        const val STOP = "Stop"
        const val SEEK = "Seek"
        const val NEXT = "Next"
        const val PREVIOUS = "Previous"
        const val GET_MEDIA_INFO = "GetMediaInfo"
        const val GET_TRANSPORT_INFO = "GetTransportInfo"
        const val GET_POSITION_INFO = "GetPositionInfo"
        const val GET_DEVICE_CAPABILITIES = "GetDeviceCapabilities"
        const val GET_TRANSPORT_SETTINGS = "GetTransportSettings"
        const val GET_CURRENT_TRANSPORT_ACTIONS = "GetCurrentTransportActions"
        const val SET_PLAY_MODE = "SetPlayMode"

        const val ERROR_INVALID_ACTION = 401
        const val ERROR_INVALID_ARGS = 402
        const val ERROR_TRANSITION_NOT_AVAILABLE = 701
        const val ERROR_ILLEGAL_SEEK_TARGET = 711
        const val ERROR_INVALID_INSTANCE_ID = 718
        const val ERROR_ILLEGAL_URI = 714
        const val ERROR_PLAY_SPEED_NOT_SUPPORTED = 717

        val PLAY_MODES = setOf("NORMAL", "REPEAT_ALL", "REPEAT_ONE", "SHUFFLE")
    }
}
