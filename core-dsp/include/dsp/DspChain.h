// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <memory>
#include <string>

#include "dsp/BrirProcessor.h"
#include "dsp/Crossfeed.h"
#include "dsp/Limiter.h"
#include "dsp/Loudness.h"
#include "dsp/Peq.h"

namespace dsp {

struct DspChainConfig {
  bool eq_enabled = false;
  PeqConfig eq;
  bool crossfeed_enabled = false;
  CrossfeedPreset crossfeed_preset = CrossfeedPreset::kWeak;
  // < 0 keeps the preset level; [0, 1] selects a continuous strength.
  double crossfeed_width = -1.0;
  // Mid/Side width: 0 = mono, 1 = original, > 1 = wider (max 1.5).
  double stereo_width = 1.0;
  bool loudness_enabled = false;
  double loudness_volume = 1.0;
  bool limiter_enabled = true;
  double limiter_threshold_db = -1.0;
  bool brir_enabled = false;
  std::string brir_path;
  double brir_mix = 0.35;
};

// Processing-mode chain:
//   preamp/PEQ -> loudness -> stereo width -> crossfeed -> limiter.
//
// configure() runs on the control thread before the stream starts. process()
// is called only from the Oboe callback and is allocation/lock free.
class DspChain {
 public:
  void configure(int sample_rate, int channels, const DspChainConfig& config);
  void reset() noexcept;

  // Safe to call from the control thread while playback is running.
  void set_loudness_volume(double volume) noexcept;

  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] std::size_t eq_band_count() const noexcept;
  [[nodiscard]] bool crossfeed_enabled() const noexcept;
  [[nodiscard]] bool limiter_enabled() const noexcept;
  [[nodiscard]] bool brir_active() const noexcept;

 private:
  Peq peq_;
  Loudness loudness_;
  std::unique_ptr<Crossfeed> crossfeed_;
  BrirProcessor brir_;
  Limiter limiter_;
  bool eq_enabled_ = false;
  double stereo_width_ = 1.0;
  bool crossfeed_enabled_ = false;
  bool limiter_enabled_ = false;
};

}  // namespace dsp
