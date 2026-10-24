// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Fixed 16-band layout used by the visual EQ editor. The response curve is
// computed at 48 kHz as a stable reference; the native engine designs the same
// PK bands at the actual processing rate.
internal val EqEditorFrequencies = doubleArrayOf(
    25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
    1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0, 20000.0,
)

internal const val EqEditorQ = 1.41
private const val EqCurveSampleRate = 48000.0
private const val EqCurveMinFrequency = 20.0
private const val EqCurveMaxFrequency = 20000.0
private const val EqCurvePoints = 240

/** Peaking-filter magnitude response in dB (AutoEQ/RBJ convention). */
internal fun peqMagnitudeDb(
    frequency: Double,
    bandFrequency: Double,
    gainDb: Double,
    q: Double,
): Double {
    if (frequency <= 0.0 || bandFrequency <= 0.0 || q <= 0.0) {
        return 0.0
    }
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * PI * bandFrequency / EqCurveSampleRate
    val cosW0 = cos(w0)
    val alpha = sin(w0) / (2.0 * q)
    val a0 = 1.0 + alpha / a
    val b0 = (1.0 + alpha * a) / a0
    val b1 = (-2.0 * cosW0) / a0
    val b2 = (1.0 - alpha * a) / a0
    val a1 = (2.0 * cosW0) / a0
    val a2 = -(1.0 - alpha / a) / a0

    val w = 2.0 * PI * frequency / EqCurveSampleRate
    val cosW = cos(w)
    val sinW = sin(w)
    // z^-1 = cos(w) - j sin(w)
    val numReal = b0 + b1 * cosW + b2 * (cosW * cosW - sinW * sinW)
    val numImag = -b1 * sinW - b2 * 2.0 * cosW * sinW
    val denReal = 1.0 - a1 * cosW - a2 * (cosW * cosW - sinW * sinW)
    val denImag = a1 * sinW + a2 * 2.0 * cosW * sinW
    val numMagnitude = sqrt(numReal * numReal + numImag * numImag)
    val denMagnitude = sqrt(denReal * denReal + denImag * denImag)
    if (denMagnitude < 1.0e-12) {
        return 0.0
    }
    return 20.0 * log10(maxOf(numMagnitude / denMagnitude, 1.0e-12))
}

/** Sum of all 16 peaking bands, excluding preamp. */
internal fun eqEditorResponseDb(gains: List<Float>, frequency: Double): Double {
    var total = 0.0
    for (index in EqEditorFrequencies.indices) {
        val gain = gains.getOrElse(index) { 0f }.toDouble()
        if (gain == 0.0) {
            continue
        }
        total += peqMagnitudeDb(frequency, EqEditorFrequencies[index], gain, EqEditorQ)
    }
    return total
}

/** Maximum positive response, used to choose a safe preamp. */
internal fun eqEditorMaxDb(gains: List<Float>): Double {
    var maximum = 0.0
    for (point in 0 until EqCurvePoints) {
        val position = point.toDouble() / (EqCurvePoints - 1).toDouble()
        val frequency = EqCurveMinFrequency *
            (EqCurveMaxFrequency / EqCurveMinFrequency).pow(position)
        maximum = maxOf(maximum, eqEditorResponseDb(gains, frequency))
    }
    return maximum
}

internal fun eqCurveFrequencyAt(position: Double): Double {
    return EqCurveMinFrequency *
        (EqCurveMaxFrequency / EqCurveMinFrequency).pow(position)
}

internal fun eqCurveValueAt(gains: List<Float>, position: Double): Double {
    return eqEditorResponseDb(gains, eqCurveFrequencyAt(position))
}
