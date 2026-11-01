// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

/**
 * Snapshot of the player's current DSP configuration, captured when the DMR
 * starts. The received LAN stream runs through the same native chain as local
 * playback (PEQ/crossfeed/BRIR/loudness/limiter).
 */
data class DmrDspConfig(
    val processing: Boolean = true,
    val eqPath: String = "",
    val crossfeed: Boolean = true,
    val eqCascade: Boolean = false,
    val eqUseSlope: Boolean = false,
    val eqSlope: Double = 1.0,
    val forcedOutputRate: Int = 0,
    val resamplerQuality: Int = 0,
    val crossfeedWidth: Double = 0.33,
    val stereoWidth: Double = 1.0,
    val loudnessEnabled: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Double = -1.0,
    val autoUsb192k: Boolean = true,
    val brirPath: String = "",
    val brirEnabled: Boolean = false,
    val brirMix: Double = 0.35,
    val eqLinearPhase: Boolean = false,
    val multibandEnabled: Boolean = false,
    val multibandLowHz: Double = 200.0,
    val multibandHighHz: Double = 2000.0,
    val multibandThresholdDb: Double = -12.0,
)
