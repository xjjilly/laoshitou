// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Biquad.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>

namespace dsp {
namespace {

constexpr double kPi = 3.14159265358979323846264338327950288;

}  // namespace

void Biquad::set_coefficients(const BiquadCoefficients& coefficients) noexcept {
  coefficients_ = coefficients;
  reset();
}

void Biquad::reset() noexcept {
  for (int channel = 0; channel < 2; ++channel) {
    z1_[channel] = 0.0;
    z2_[channel] = 0.0;
  }
}

void Biquad::process(float* interleaved, int frames, int channels) noexcept {
  if (interleaved == nullptr || frames <= 0 || channels <= 0) {
    return;
  }

  const int processed_channels = channels < 2 ? channels : 2;
  for (int frame = 0; frame < frames; ++frame) {
    const auto base = static_cast<std::size_t>(frame) * static_cast<std::size_t>(channels);
    for (int channel = 0; channel < processed_channels; ++channel) {
      const auto index = base + static_cast<std::size_t>(channel);
      const double x = static_cast<double>(interleaved[index]);
      const double y = coefficients_.b0 * x + z1_[channel];
      z1_[channel] = coefficients_.b1 * x + coefficients_.a1 * y + z2_[channel];
      z2_[channel] = coefficients_.b2 * x + coefficients_.a2 * y;
      interleaved[index] = static_cast<float>(y);
    }
  }
}

double BiquadMagnitudeDb(const BiquadCoefficients& coefficients, double frequency_hz,
                         int sample_rate) noexcept {
  if (sample_rate <= 0 || frequency_hz <= 0.0) {
    return 0.0;
  }

  const double omega = 2.0 * kPi * frequency_hz / static_cast<double>(sample_rate);
  const std::complex<double> z1(std::cos(omega), -std::sin(omega));
  const std::complex<double> z2 = z1 * z1;
  const std::complex<double> numerator =
      coefficients.b0 + coefficients.b1 * z1 + coefficients.b2 * z2;
  const std::complex<double> denominator = 1.0 - coefficients.a1 * z1 - coefficients.a2 * z2;
  const double magnitude = std::abs(numerator) / std::max(std::abs(denominator), 1.0e-12);
  return 20.0 * std::log10(std::max(magnitude, 1.0e-12));
}

}  // namespace dsp
