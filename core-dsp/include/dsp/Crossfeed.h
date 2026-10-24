// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <memory>

namespace dsp {

enum class CrossfeedPreset {
  kWeak = 0,  // BS2B default (Jan Meier, 700 Hz, 4.5 dB)
  kMedium,    // BS2B CMOY (700 Hz, 6.0 dB)
  kStrong,    // BS2B JMeier (650 Hz, 9.5 dB)
};

// Thin wrapper around the MIT libbs2b implementation. The library state uses
// double precision and a short delay line; process() is real-time safe.
class Crossfeed {
 public:
  // width < 0 keeps the preset level. width in [0, 1] interpolates the bs2b
  // feed (1.0..9.5 dB) and cutoff (700..650 Hz) continuously.
  Crossfeed(int sample_rate, CrossfeedPreset preset, double width = -1.0);
  ~Crossfeed();

  Crossfeed(Crossfeed&&) noexcept;
  Crossfeed& operator=(Crossfeed&&) noexcept;

  Crossfeed(const Crossfeed&) = delete;
  Crossfeed& operator=(const Crossfeed&) = delete;

  [[nodiscard]] bool valid() const noexcept;

  // Processes interleaved stereo float samples in place. Non-stereo input is
  // left untouched.
  void process(float* interleaved, int frames, int channels) noexcept;

  void reset() noexcept;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace dsp
