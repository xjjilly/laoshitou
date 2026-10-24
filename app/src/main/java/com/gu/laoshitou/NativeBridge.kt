// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou

object NativeBridge {
    init {
        System.loadLibrary("hifiplayer")
    }

    external fun sampleRatePlan(
        sourceRate: Int,
        processing: Boolean,
        supportedRates: IntArray,
        nativeRate: Int,
    ): Int

    external fun startTestTone(sampleRate: Int): Boolean
    external fun stopTestTone()
    external fun isTestToneRunning(): Boolean
    external fun testToneActualRate(): Int
    external fun testToneExclusive(): Boolean
    external fun testToneUnderruns(): Long

    external fun ffmpegVersion(): String

    external fun startFile(
        path: String,
        processing: Boolean,
        supportedRates: IntArray,
        nativeRate: Int,
        eqProfilePath: String,
        crossfeed: Boolean,
        eqCascade: Boolean,
        eqUseSlope: Boolean,
        eqSlope: Double,
        forcedOutputRate: Int,
        resamplerQuality: Int,
        crossfeedWidth: Double,
        stereoWidth: Double,
        loudnessEnabled: Boolean,
        loudnessVolume: Double,
        limiterEnabled: Boolean,
        limiterThresholdDb: Double,
        usbDacDetected: Boolean,
        autoUsb192k: Boolean,
        brirPath: String,
        brirEnabled: Boolean,
        brirMix: Double,
    ): Boolean

    external fun loadEqProfileForEditor(
        path: String,
        order: Int,
        useSlope: Boolean,
        slope: Double,
    ): FloatArray
    external fun stopPlayback()
    external fun isPlaying(): Boolean
    external fun playbackActualRate(): Int
    external fun playbackSourceRate(): Int
    external fun playbackTargetRate(): Int
    external fun playbackCrossFamily(): Boolean
    external fun playbackNonInteger(): Boolean
    external fun playbackDeviceLimited(): Boolean
    external fun playbackExclusive(): Boolean
    external fun playbackUnderruns(): Long
    external fun playbackEnded(): Boolean
    external fun playbackNeedsRecovery(): Boolean
    external fun recoverPlayback(): Boolean
    external fun playbackProcessingMode(): Boolean
    external fun playbackEqBandCount(): Int
    external fun playbackCrossfeedEnabled(): Boolean
    external fun playbackLimiterEnabled(): Boolean
    external fun playbackBrirActive(): Boolean

    external fun pausePlayback()
    external fun resumePlayback()
    external fun isPlaybackPaused(): Boolean
    external fun setPlaybackGain(gain: Float)
    external fun setLoudnessVolume(volume: Float)
}
