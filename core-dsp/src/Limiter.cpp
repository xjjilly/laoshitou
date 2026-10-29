// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Limiter.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace dsp {

void Limiter::configure(int sample_rate, int channels, double threshold_db, double release_ms,
                        double lookahead_ms) {
  channels_ = channels > 0 ? channels : 2;
  if (channels_ > 2) {
    channels_ = 2;
  }
  if (sample_rate <= 0) {
    sample_rate = 48000;
  }

  const double threshold_clamped = std::clamp(threshold_db, -24.0, 0.0);
  threshold_ = std::pow(10.0, threshold_clamped / 20.0);

  const double lookahead_frames =
      static_cast<double>(sample_rate) * std::max(lookahead_ms, 0.0) / 1000.0;
  lookahead_frames_ = static_cast<int>(lookahead_frames + 0.5);
  if (lookahead_frames_ < 1) {
    lookahead_frames_ = 1;
  }

  const double release_samples =
      static_cast<double>(sample_rate) * std::max(release_ms, 1.0) / 1000.0;
  release_step_ = 1.0 - std::exp(-1.0 / release_samples);

  delay_.assign(static_cast<std::size_t>(channels_) * static_cast<std::size_t>(lookahead_frames_),
                0.0f);
  reset();
  active_ = true;
}

void Limiter::reset() noexcept {
  std::fill(delay_.begin(), delay_.end(), 0.0f);
  write_index_ = 0;
  hold_ = 0;
  gain_ = 1.0;
}

void Limiter::process(float* interleaved, int frames, int channels) noexcept {
  if (!active_ || interleaved == nullptr || frames <= 0 || channels != channels_) {
    return;
  }

  for (int frame = 0; frame < frames; ++frame) {
    double peak = 0.0;
    const auto base = static_cast<std::size_t>(frame) * static_cast<std::size_t>(channels_);
    for (int channel = 0; channel < channels_; ++channel) {
      peak = std::max(peak, std::fabs(static_cast<double>(interleaved[base + channel])));
    }

    const double target = peak > threshold_ ? threshold_ / peak : 1.0;
    if (target < gain_) {
      gain_ = target;
      hold_ = lookahead_frames_;
    } else if (hold_ > 0) {
      --hold_;
    } else {
      gain_ += release_step_ * (target - gain_);
      gain_ = std::min(gain_, 1.0);
    }

    const auto write_base =
        static_cast<std::size_t>(write_index_) * static_cast<std::size_t>(channels_);
    for (int channel = 0; channel < channels_; ++channel) {
      const auto delay_index = write_base + static_cast<std::size_t>(channel);
      const auto sample_index = base + static_cast<std::size_t>(channel);
      const float delayed = delay_[delay_index];
      delay_[delay_index] = interleaved[sample_index];
      interleaved[sample_index] = static_cast<float>(static_cast<double>(delayed) * gain_);
    }

    write_index_ = (write_index_ + 1) % lookahead_frames_;
  }
}

bool Limiter::active() const noexcept { return active_; }

}  // namespace dsp
