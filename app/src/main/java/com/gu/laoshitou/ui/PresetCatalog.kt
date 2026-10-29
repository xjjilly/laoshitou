// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

/**
 * A built-in preset that is just a 16-band gain grid plus the DSP switches.
 * The device presets (Studio/NightIem/Nearfield/Live) keep their own profile
 * files and are handled separately in MainActivity.
 *
 * The gains mirror the PC one-click presets and follow [EqEditorFrequencies]
 * (25 Hz … 20 kHz, Q = [EqEditorQ]). Keeping the peak at or below +6 dB avoids
 * the clipping warning; [Bass] uses a negative preamp for that reason.
 */
data class PresetSpec(
    val gains: List<Float>,
    val preampDb: Double = 0.0,
    val crossfeed: Boolean = false,
    val crossfeedWidth: Double = 0.40,
    val stereoWidth: Double = 1.0,
    val loudness: Boolean = false,
    val multiband: Boolean = false,
    val multibandThresholdDb: Double = -12.0,
    val linearPhase: Boolean = false,
)

val SoundPresetCatalog: Map<SoundPreset, PresetSpec> = mapOf(
    SoundPreset.Fps to PresetSpec(
        gains = listOf(
            -7f, -6f, -5f, -3f, -1.5f, 1.5f, 3f, 2.5f,
            1f, 0.5f, 0f, 1f, 2f, 2.5f, 2f, 1f,
        ),
        stereoWidth = 1.25,
        multiband = true,
        multibandThresholdDb = -10.0,
        linearPhase = true,
    ),
    SoundPreset.Voice to PresetSpec(
        gains = listOf(
            -6f, -5f, -3f, -1.5f, 0f, 0f, 0f, 0f,
            0f, 1f, 2f, 2.5f, 1.5f, 0.5f, 0f, 0f,
        ),
        loudness = true,
    ),
    SoundPreset.Pop to PresetSpec(
        gains = listOf(
            -1f, 0f, 1f, 2f, 1.5f, 0f, -1f, -1f,
            0f, 1f, 2f, 2f, 1.5f, 1f, 0.5f, 0f,
        ),
        loudness = true,
    ),
    SoundPreset.Rock to PresetSpec(
        gains = listOf(
            2f, 3f, 3f, 2f, 1f, 0f, -1.5f, -2f,
            -2f, -1f, 0f, 1.5f, 2.5f, 2.5f, 2f, 1f,
        ),
        multiband = true,
        multibandThresholdDb = -10.0,
    ),
    SoundPreset.Classical to PresetSpec(
        gains = listOf(
            0f, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0.5f, 0.5f, 0f, 0.5f, 1f, 0.5f,
        ),
        crossfeed = true,
        crossfeedWidth = 0.30,
        stereoWidth = 1.10,
    ),
    SoundPreset.Bass to PresetSpec(
        gains = listOf(
            4f, 4f, 3.5f, 2.5f, 1f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        ),
        // The three boosted low bands overlap to +6.96 dB at Q=1.41; the
        // negative preamp keeps the response below the +6 dB warning line.
        preampDb = -3.0,
        multiband = true,
        multibandThresholdDb = -12.0,
    ),
    SoundPreset.Treble to PresetSpec(
        gains = listOf(
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 2f, 3f, 3f, 2.5f,
        ),
    ),
    SoundPreset.Movie to PresetSpec(
        gains = listOf(
            3f, 3.5f, 2f, 0f, -1f, -1f, 0f, 0.5f,
            1.5f, 2f, 1.5f, 0.5f, 0f, 1f, 1f, 0f,
        ),
        stereoWidth = 1.15,
        multiband = true,
        multibandThresholdDb = -12.0,
    ),
)
