// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/MultibandCompressor.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace dsp {
namespace {

// Maximum scratch capacity in frames (well above any device buffer size).
constexpr int kMaxScratchFrames = 8192;

// RBJ 2nd-order low-pass; Q = 1/sqrt(2) gives a Butterworth section, two of
// which cascade into a Linkwitz-Riley 4th-order crossover.
BiquadCoefficients ButterworthLowPass(double frequency_hz, int sample_rate) {
  const double w0 = 2.0 * 3.14159265358979323846 * std::clamp(frequency_hz, 10.0, 20000.0) /
                    static_cast<double>(sample_rate);
  const double alpha = std::sin(w0) / (2.0 * 0.70710678118654752440);
  const double cosw = std::cos(w0);
  const double a0 = 1.0 + alpha;
  BiquadCoefficients coefficients;
  coefficients.b0 = ((1.0 - cosw) / 2.0) / a0;
  coefficients.b1 = (1.0 - cosw) / a0;
  coefficients.b2 = ((1.0 - cosw) / 2.0) / a0;
  // This codebase stores the NEGATED RBJ feedback coefficients (the
  // denominator convention is 1 - a1*z^-1 - a2*z^-2, see Biquad.h).
  coefficients.a1 = (2.0 * cosw) / a0;
  coefficients.a2 = -(1.0 - alpha) / a0;
  return coefficients;
}

}  // namespace

void MultibandCompressor::configure(int sample_rate, int channels, const MultibandConfig& config) {
  channels_ = channels >= 2 ? 2 : 1;
  sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
  const std::size_t scratch_size =
      static_cast<std::size_t>(kMaxScratchFrames) * static_cast<std::size_t>(channels_);
  scratch_low_.assign(scratch_size, 0.0f);
  scratch_mid_.assign(scratch_size, 0.0f);
  scratch_high_.assign(scratch_size, 0.0f);
  scratch_input_.assign(scratch_size, 0.0f);

  apply_crossovers(config.crossover_low_hz, config.crossover_high_hz);
  for (int band = 0; band < 3; ++band) {
    const CompBandConfig& cfg = band == 0 ? config.low : (band == 1 ? config.mid : config.high);
    band_[band].threshold_db.store(cfg.threshold_db, std::memory_order_relaxed);
    band_[band].ratio.store(std::max(cfg.ratio, 1.0), std::memory_order_relaxed);
    band_[band].attack_ms.store(cfg.attack_ms, std::memory_order_relaxed);
    band_[band].release_ms.store(cfg.release_ms, std::memory_order_relaxed);
    band_[band].envelope = 0.0;
    band_[band].gain_smoothed = 1.0;
  }
  for (int band = 0; band < 3; ++band) {
    update_coefficients(band);
  }
  enabled_.store(config.enabled, std::memory_order_relaxed);
  reset();
}

void MultibandCompressor::reset() noexcept {
  lp1_a_.reset();
  lp1_b_.reset();
  lp2_a_.reset();
  lp2_b_.reset();
  for (int band = 0; band < 3; ++band) {
    band_[band].envelope = 0.0;
    band_[band].gain_smoothed = 1.0;
  }
}

void MultibandCompressor::set_enabled(bool enabled) noexcept {
  enabled_.store(enabled, std::memory_order_relaxed);
}

void MultibandCompressor::set_threshold_db(int band, double db) noexcept {
  if (band >= 0 && band < 3) {
    band_[band].threshold_db.store(db, std::memory_order_relaxed);
  }
}

void MultibandCompressor::set_ratio(int band, double ratio) noexcept {
  if (band >= 0 && band < 3) {
    band_[band].ratio.store(std::max(ratio, 1.0), std::memory_order_relaxed);
  }
}

void MultibandCompressor::set_attack_ms(int band, double ms) noexcept {
  if (band >= 0 && band < 3) {
    band_[band].attack_ms.store(ms, std::memory_order_relaxed);
    update_coefficients(band);
  }
}

void MultibandCompressor::set_release_ms(int band, double ms) noexcept {
  if (band >= 0 && band < 3) {
    band_[band].release_ms.store(ms, std::memory_order_relaxed);
    update_coefficients(band);
  }
}

void MultibandCompressor::set_crossovers(double low_hz, double high_hz) noexcept {
  crossover_pending_.low_hz = low_hz;
  crossover_pending_.high_hz = high_hz;
  crossover_pending_.pending.store(true, std::memory_order_release);
}

void MultibandCompressor::apply_crossovers(double low_hz, double high_hz) {
  if (high_hz <= low_hz) {
    high_hz = low_hz * 10.0;
  }
  const BiquadCoefficients lp1 = ButterworthLowPass(low_hz, sample_rate_);
  const BiquadCoefficients lp2 = ButterworthLowPass(high_hz, sample_rate_);
  lp1_a_.set_coefficients(lp1);
  lp1_b_.set_coefficients(lp1);
  lp2_a_.set_coefficients(lp2);
  lp2_b_.set_coefficients(lp2);
}

void MultibandCompressor::update_coefficients(int band) {
  BandState& state = band_[band];
  const double attack_ms = state.attack_ms.load(std::memory_order_relaxed);
  const double release_ms = state.release_ms.load(std::memory_order_relaxed);
  const double attack_samples =
      static_cast<double>(sample_rate_) * std::max(attack_ms, 0.05) / 1000.0;
  const double release_samples =
      static_cast<double>(sample_rate_) * std::max(release_ms, 0.05) / 1000.0;
  const double attack = 1.0 - std::exp(-1.0 / attack_samples);
  const double release = 1.0 - std::exp(-1.0 / release_samples);
  band_[band].attack_coef.store(attack, std::memory_order_relaxed);
  band_[band].release_coef.store(release, std::memory_order_relaxed);
}

void MultibandCompressor::process(float* interleaved, int frames, int channels) noexcept {
  if (!enabled_.load(std::memory_order_relaxed) || interleaved == nullptr || frames <= 0) {
    return;
  }
  const int ch = channels >= 2 ? 2 : 1;
  // Process in scratch-sized chunks so calls larger than the pre-allocated
  // buffers are not truncated (the crossover state persists across chunks).
  int offset = 0;
  while (offset < frames) {
    const int count = std::min(frames - offset, kMaxScratchFrames);
    const std::size_t size = static_cast<std::size_t>(count) * static_cast<std::size_t>(ch);
    float* chunk = interleaved + static_cast<std::size_t>(offset) * static_cast<std::size_t>(ch);

    // Deferred crossover swap: state resets at this block boundary; the LR
    // sum keeps the bands aligned, so the transient stays bounded.
    if (crossover_pending_.pending.exchange(false, std::memory_order_acq_rel)) {
      apply_crossovers(crossover_pending_.low_hz, crossover_pending_.high_hz);
    }

    std::copy_n(chunk, size, scratch_input_.begin());
    std::copy_n(scratch_input_.begin(), size, scratch_low_.begin());
    std::copy_n(scratch_input_.begin(), size, scratch_mid_.begin());

    // low = LP(f1); mid = LP(f2) - low; high = x - LP(f2).
    lp1_a_.process(scratch_low_.data(), count, ch);
    lp1_b_.process(scratch_low_.data(), count, ch);
    lp2_a_.process(scratch_mid_.data(), count, ch);
    lp2_b_.process(scratch_mid_.data(), count, ch);
    for (std::size_t i = 0; i < size; ++i) {
      scratch_high_[i] = scratch_input_[i] - scratch_mid_[i];
      scratch_mid_[i] = scratch_mid_[i] - scratch_low_[i];
    }

    // Stereo-linked compression per band: per-sample peak envelope and gain
    // smoothing (the one-pole coefficients are per-sample; a block-rate
    // update would take seconds to converge).
    for (int band = 0; band < 3; ++band) {
      float* band_data = band == 0 ? scratch_low_.data()
                                   : (band == 1 ? scratch_mid_.data() : scratch_high_.data());
      BandState& state = band_[band];
      const double attack = state.attack_coef.load(std::memory_order_relaxed);
      const double release = state.release_coef.load(std::memory_order_relaxed);
      const double threshold =
          std::pow(10.0, state.threshold_db.load(std::memory_order_relaxed) / 20.0);
      const double ratio = state.ratio.load(std::memory_order_relaxed);
      double envelope = state.envelope;
      double gain = state.gain_smoothed;
      for (std::size_t i = 0; i < size; ++i) {
        const double level = static_cast<double>(std::abs(band_data[i]));
        if (level > envelope) {
          envelope += attack * (level - envelope);
        } else {
          envelope += release * (level - envelope);
        }
        double gain_target = 1.0;
        if (envelope > threshold && ratio > 1.0) {
          gain_target = std::pow(envelope / threshold, 1.0 / ratio - 1.0);
        }
        if (gain_target < gain) {
          gain += attack * (gain_target - gain);
        } else {
          gain += release * (gain_target - gain);
        }
        band_data[i] = static_cast<float>(static_cast<double>(band_data[i]) * gain);
      }
      state.envelope = envelope;
      state.gain_smoothed = gain;
    }

    for (std::size_t i = 0; i < size; ++i) {
      chunk[i] = scratch_low_[i] + scratch_mid_[i] + scratch_high_[i];
    }
    offset += count;
  }
}

}  // namespace dsp
