// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <atomic>

namespace dsp {

// Volume-dependent loudness compensation.
//
// Two fixed one-pole filters (low around 60 Hz, high around 10 kHz) are mixed
// into the dry signal by amounts derived from the media volume: lower volume
// boosts lows/highs more. Only the mix amounts ramp inside the audio callback;
// filter coefficients stay fixed, so the stage is allocation/lock free and
// click-free.
class Loudness {
 public:
  void configure(int sample_rate, int channels) noexcept;
  void reset() noexcept;

  // volume in [0, 1]; 1 means no compensation.
  void set_volume(double volume) noexcept;
  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] bool active() const noexcept;

 private:
  static constexpr int kMaxChannels = 2;

  double low_alpha_ = 0.0;
  double high_alpha_ = 0.0;
  double low_state_[kMaxChannels] = {0.0, 0.0};
  double high_state_[kMaxChannels] = {0.0, 0.0};
  std::atomic<double> target_volume_{1.0};
  double current_amount_ = 0.0;
  int sample_rate_ = 0;
  int channels_ = 0;
  bool configured_ = false;
};

}  // namespace dsp
