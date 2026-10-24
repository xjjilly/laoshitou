// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>

namespace dsp {

// AutoEQ/RBJ coefficients normalized by a0. The feedback signs follow the
// AutoEQ convention: the difference equation uses +a1*y1 +a2*y2.
struct BiquadCoefficients {
  double b0 = 1.0;
  double b1 = 0.0;
  double b2 = 0.0;
  double a1 = 0.0;
  double a2 = 0.0;
};

// Stereo transposed direct form II biquad. Coefficients/state are double for
// numerical stability at low frequencies and high Q; samples are float.
class Biquad {
 public:
  void set_coefficients(const BiquadCoefficients& coefficients) noexcept;
  void reset() noexcept;

  // Processes interleaved float samples in place. Only the first two channels
  // are filtered; the player DSP chain is stereo.
  void process(float* interleaved, int frames, int channels) noexcept;

 private:
  BiquadCoefficients coefficients_{};
  double z1_[2] = {0.0, 0.0};
  double z2_[2] = {0.0, 0.0};
};

// Magnitude response in dB at [frequency_hz] for normalized coefficients.
// The denominator is 1 - a1*z^-1 - a2*z^-2, matching Biquad::process().
double BiquadMagnitudeDb(const BiquadCoefficients& coefficients, double frequency_hz,
                         int sample_rate) noexcept;

}  // namespace dsp
