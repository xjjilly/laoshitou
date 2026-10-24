// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Loudness.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace dsp {
namespace {

constexpr double kPi = 3.14159265358979323846264338327950288;
constexpr double kLowFrequency = 60.0;
constexpr double kHighFrequency = 10000.0;
constexpr double kBassMix = 0.50;
constexpr double kTrebleMix = 0.30;
constexpr double kSmoothingSeconds = 0.03;

double OnePoleAlpha(double frequency, int sample_rate) {
  if (sample_rate <= 0) {
    return 0.0;
  }
  return 1.0 - std::exp(-2.0 * kPi * frequency / static_cast<double>(sample_rate));
}

}  // namespace

void Loudness::configure(int sample_rate, int channels) noexcept {
  sample_rate_ = sample_rate;
  channels_ = std::min(channels, kMaxChannels);
  low_alpha_ = OnePoleAlpha(kLowFrequency, sample_rate);
  high_alpha_ = OnePoleAlpha(kHighFrequency, sample_rate);
  configured_ = sample_rate > 0 && channels_ > 0;
  reset();
}

void Loudness::reset() noexcept {
  for (int channel = 0; channel < kMaxChannels; ++channel) {
    low_state_[channel] = 0.0;
    high_state_[channel] = 0.0;
  }
  current_amount_ = 0.0;
}

void Loudness::set_volume(double volume) noexcept {
  target_volume_.store(std::clamp(volume, 0.0, 1.0), std::memory_order_relaxed);
}

bool Loudness::active() const noexcept { return configured_; }

void Loudness::process(float* interleaved, int frames, int channels) noexcept {
  if (!configured_ || interleaved == nullptr || frames <= 0) {
    return;
  }

  const double volume = target_volume_.load(std::memory_order_relaxed);
  const double target_amount = std::clamp((1.0 - volume) * 1.5, 0.0, 1.0);
  const double start_amount = current_amount_;
  const double block_seconds = static_cast<double>(frames) / static_cast<double>(sample_rate_);
  const double alpha = 1.0 - std::exp(-block_seconds / kSmoothingSeconds);
  const double end_amount = start_amount + (target_amount - start_amount) * alpha;
  current_amount_ = end_amount;

  if (std::max(start_amount, end_amount) < 1.0e-6) {
    // Compensation is effectively off; leave the filter states alone.
    return;
  }

  const int processed_channels = std::min(channels, kMaxChannels);
  const double step = static_cast<double>(frames) > 1.0
                          ? (end_amount - start_amount) / static_cast<double>(frames)
                          : 0.0;
  double amount = start_amount;

  for (int frame = 0; frame < frames; ++frame) {
    const std::size_t base = static_cast<std::size_t>(frame) * static_cast<std::size_t>(channels);
    for (int channel = 0; channel < processed_channels; ++channel) {
      const std::size_t index = base + static_cast<std::size_t>(channel);
      const double dry = static_cast<double>(interleaved[index]);
      low_state_[channel] += low_alpha_ * (dry - low_state_[channel]);
      high_state_[channel] += high_alpha_ * (dry - high_state_[channel]);
      const double treble = dry - high_state_[channel];
      const double boosted = dry + amount * (kBassMix * low_state_[channel] + kTrebleMix * treble);
      interleaved[index] = static_cast<float>(boosted);
    }
    amount += step;
  }
}

}  // namespace dsp
