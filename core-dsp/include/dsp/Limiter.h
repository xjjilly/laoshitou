// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <vector>

namespace dsp {

// Stereo look-ahead peak limiter.
//
// The input peak updates the gain immediately; the audio is delayed by the
// look-ahead amount, so the gain reduction is already in effect when the
// delayed peak reaches the output. A short hold prevents release before the
// delayed peak has passed. configure() allocates on the control thread;
// process() is real-time safe.
class Limiter {
 public:
  void configure(int sample_rate, int channels, double threshold_db = -1.0,
                 double release_ms = 100.0, double lookahead_ms = 5.0);
  void reset() noexcept;
  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] bool active() const noexcept;

 private:
  std::vector<float> delay_;
  int channels_ = 0;
  int lookahead_frames_ = 0;
  int write_index_ = 0;
  int hold_ = 0;
  double threshold_ = 0.891;
  double release_step_ = 0.0;
  double gain_ = 1.0;
  bool active_ = false;
};

}  // namespace dsp
