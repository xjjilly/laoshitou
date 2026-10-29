// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <atomic>
#include <cstddef>
#include <memory>
#include <string>
#include <vector>

#include "dsp/BrirProcessor.h"
#include "dsp/Crossfeed.h"
#include "dsp/FirEqProcessor.h"
#include "dsp/Limiter.h"
#include "dsp/Loudness.h"
#include "dsp/MultibandCompressor.h"
#include "dsp/Peq.h"

namespace dsp {

struct DspChainConfig {
  bool eq_enabled = false;
  // Linear-phase FIR EQ (frequency-sampling design + partitioned
  // convolution) instead of the minimum-phase IIR biquads. Adds
  // block_size + kernel/2 frames of latency.
  bool eq_linear_phase = false;
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
  // Convolution strategy and partitioned block size in frames (power of two,
  // clamped to [64, 8192]; ignored by Direct mode).
  BrirMode brir_mode = BrirMode::kAuto;
  std::size_t brir_block_size = 512;
  // 3-band LR4 dynamics; when enabled it replaces the single limiter as the
  // final protection stage.
  MultibandConfig multiband;
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

  // Live EQ-only update, safe to call from the control thread while the
  // stream runs: Peq uses fixed arrays (no allocation, no lock), so only
  // the biquad coefficients are rebuilt.
  void update_peq(const PeqConfig& config) noexcept;

  // Live parameter updates (control thread, safe while running): plain
  // atomic stores read by process(); no allocation, no lock.
  void set_stereo_width(double width) noexcept;
  void set_brir_mix(double mix) noexcept;
  void set_multiband_enabled(bool enabled) noexcept;
  void set_multiband_threshold_db(int band, double db) noexcept;
  void set_multiband_ratio(int band, double ratio) noexcept;
  void set_multiband_crossovers(double low_hz, double high_hz) noexcept;

  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] std::size_t eq_band_count() const noexcept;
  [[nodiscard]] bool crossfeed_enabled() const noexcept;
  [[nodiscard]] bool limiter_enabled() const noexcept;
  [[nodiscard]] bool brir_active() const noexcept;
  // True when the chain is actually running the linear-phase FIR path.
  [[nodiscard]] bool eq_linear_phase_active() const noexcept {
    return eq_enabled_ && eq_linear_phase_ && fir_eq_.valid();
  }
  // True when linear-phase FIR was requested but the sample rate exceeds
  // the 96 kHz safety cap, so the chain silently fell back to IIR.
  [[nodiscard]] bool fir_degraded() const noexcept { return fir_degraded_; }

 private:
  Peq peq_;
  Loudness loudness_;
  std::unique_ptr<Crossfeed> crossfeed_;
  BrirProcessor brir_;
  Limiter limiter_;
  FirEqProcessor fir_eq_;
  MultibandCompressor multiband_;
  std::vector<float> fir_kernel_;
  bool eq_enabled_ = false;
  bool eq_linear_phase_ = false;
  bool fir_degraded_ = false;
  std::atomic<double> stereo_width_{1.0};
  bool crossfeed_enabled_ = false;
  bool limiter_enabled_ = false;
  int sample_rate_ = 48000;
  int channels_ = 2;
};

}  // namespace dsp
