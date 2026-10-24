// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Peq.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace dsp {
namespace {

constexpr double kPi = 3.14159265358979323846264338327950288;

}  // namespace

BiquadCoefficients Peq::DesignBand(const PeqBandConfig& band, int sample_rate) noexcept {
  BiquadCoefficients coefficients{};
  const int order = std::clamp(band.order, 1, 2);
  if (sample_rate <= 0 || band.frequency <= 0.0 || band.q <= 0.0 || order <= 0) {
    return coefficients;
  }

  const double nyquist = 0.5 * static_cast<double>(sample_rate);
  const double frequency = std::clamp(band.frequency, 1.0, nyquist * 0.999);
  const double q = std::clamp(band.q, 0.05, 20.0);
  const double total_gain_db = std::clamp(band.gain_db, -30.0, 30.0);
  const double gain_db = total_gain_db / static_cast<double>(order);

  const double a = std::pow(10.0, gain_db / 40.0);
  const double w0 = 2.0 * kPi * frequency / static_cast<double>(sample_rate);
  const double cos_w0 = std::cos(w0);
  const double sin_w0 = std::sin(w0);

  double alpha = sin_w0 / (2.0 * q);
  if (band.type != PeqFilterType::kPeaking && band.use_slope) {
    const double shelf_slope = std::clamp(band.slope, 0.05, 1.0);
    alpha = sin_w0 * 0.5 * std::sqrt((a + 1.0 / a) * (1.0 / shelf_slope - 1.0) + 2.0);
  }
  const double sqrt_a = std::sqrt(a);

  switch (band.type) {
    case PeqFilterType::kPeaking: {
      const double a0 = 1.0 + alpha / a;
      coefficients.b0 = (1.0 + alpha * a) / a0;
      coefficients.b1 = (-2.0 * cos_w0) / a0;
      coefficients.b2 = (1.0 - alpha * a) / a0;
      coefficients.a1 = (2.0 * cos_w0) / a0;
      coefficients.a2 = -(1.0 - alpha / a) / a0;
      break;
    }
    case PeqFilterType::kLowShelf: {
      const double a0 = (a + 1.0) + (a - 1.0) * cos_w0 + 2.0 * sqrt_a * alpha;
      coefficients.b0 = a * ((a + 1.0) - (a - 1.0) * cos_w0 + 2.0 * sqrt_a * alpha) / a0;
      coefficients.b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w0) / a0;
      coefficients.b2 = a * ((a + 1.0) - (a - 1.0) * cos_w0 - 2.0 * sqrt_a * alpha) / a0;
      coefficients.a1 = 2.0 * ((a - 1.0) + (a + 1.0) * cos_w0) / a0;
      coefficients.a2 = -((a + 1.0) + (a - 1.0) * cos_w0 - 2.0 * sqrt_a * alpha) / a0;
      break;
    }
    case PeqFilterType::kHighShelf: {
      const double a0 = (a + 1.0) - (a - 1.0) * cos_w0 + 2.0 * sqrt_a * alpha;
      coefficients.b0 = a * ((a + 1.0) + (a - 1.0) * cos_w0 + 2.0 * sqrt_a * alpha) / a0;
      coefficients.b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w0) / a0;
      coefficients.b2 = a * ((a + 1.0) + (a - 1.0) * cos_w0 - 2.0 * sqrt_a * alpha) / a0;
      coefficients.a1 = -2.0 * ((a - 1.0) - (a + 1.0) * cos_w0) / a0;
      coefficients.a2 = -((a + 1.0) - (a - 1.0) * cos_w0 - 2.0 * sqrt_a * alpha) / a0;
      break;
    }
    case PeqFilterType::kHighPass: {
      const double a0 = 1.0 + alpha;
      coefficients.b0 = (1.0 + cos_w0) * 0.5 / a0;
      coefficients.b1 = -(1.0 + cos_w0) / a0;
      coefficients.b2 = (1.0 + cos_w0) * 0.5 / a0;
      coefficients.a1 = 2.0 * cos_w0 / a0;
      coefficients.a2 = -(1.0 - alpha) / a0;
      break;
    }
    case PeqFilterType::kLowPass: {
      const double a0 = 1.0 + alpha;
      coefficients.b0 = (1.0 - cos_w0) * 0.5 / a0;
      coefficients.b1 = (1.0 - cos_w0) / a0;
      coefficients.b2 = (1.0 - cos_w0) * 0.5 / a0;
      coefficients.a1 = 2.0 * cos_w0 / a0;
      coefficients.a2 = -(1.0 - alpha) / a0;
      break;
    }
  }

  return coefficients;
}

double Peq::BandResponseDb(const PeqBandConfig& band, double frequency_hz,
                           int sample_rate) noexcept {
  const int order = std::clamp(band.order, 1, 2);
  return static_cast<double>(order) *
         BiquadMagnitudeDb(DesignBand(band, sample_rate), frequency_hz, sample_rate);
}

void Peq::configure(int sample_rate, int channels, const PeqConfig& config) noexcept {
  sample_rate_ = sample_rate;
  channels_ = channels;
  preamp_gain_ = std::pow(10.0, config.preamp_db / 20.0);
  band_count_ = std::min(config.bands.size(), kMaxBands);

  for (std::size_t i = 0; i < kMaxBands; ++i) {
    enabled_[i] = false;
    order_[i] = 1;
    bands_[i * 2].set_coefficients(BiquadCoefficients{});
    bands_[i * 2 + 1].set_coefficients(BiquadCoefficients{});
  }

  for (std::size_t i = 0; i < band_count_; ++i) {
    const PeqBandConfig& band = config.bands[i];
    enabled_[i] = band.enabled;
    order_[i] = std::clamp(band.order, 1, 2);
    if (band.enabled) {
      const BiquadCoefficients coefficients = DesignBand(band, sample_rate);
      bands_[i * 2].set_coefficients(coefficients);
      if (order_[i] == 2) {
        bands_[i * 2 + 1].set_coefficients(coefficients);
      }
    }
  }
}

void Peq::reset() noexcept {
  for (auto& band : bands_) {
    band.reset();
  }
}

void Peq::process(float* interleaved, int frames, int channels) noexcept {
  if (interleaved == nullptr || frames <= 0 || channels <= 0) {
    return;
  }

  if (preamp_gain_ != 1.0) {
    const auto samples = static_cast<std::size_t>(frames) * static_cast<std::size_t>(channels);
    for (std::size_t i = 0; i < samples; ++i) {
      interleaved[i] = static_cast<float>(static_cast<double>(interleaved[i]) * preamp_gain_);
    }
  }

  for (std::size_t i = 0; i < band_count_; ++i) {
    if (!enabled_[i]) {
      continue;
    }
    bands_[i * 2].process(interleaved, frames, channels);
    if (order_[i] == 2) {
      bands_[i * 2 + 1].process(interleaved, frames, channels);
    }
  }
}

std::size_t Peq::band_count() const noexcept { return band_count_; }

bool Peq::active() const noexcept { return band_count_ > 0 || preamp_gain_ != 1.0; }

}  // namespace dsp
