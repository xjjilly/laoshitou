// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

#include "dsp/Biquad.h"

namespace dsp {

// One band of the multiband compressor.
struct CompBandConfig {
  double threshold_db = -12.0;
  double ratio = 4.0;  // > 1; 1 = no compression
  double attack_ms = 10.0;
  double release_ms = 120.0;
};

// 3-band dynamics configuration. The low band defaults to a fast protective
// limiter-like setting; mid/high use a gentle 2:1 so the LR4 band overlap
// stays coherent (the sum crossover leaks out-of-band content out of phase,
// so compressing one band alone can disturb the phase cancellation).
struct MultibandConfig {
  bool enabled = false;
  double crossover_low_hz = 200.0;    // low/mid split
  double crossover_high_hz = 2000.0;  // mid/high split
  CompBandConfig low{-12.0, 4.0, 10.0, 120.0};
  CompBandConfig mid{-6.0, 2.0, 15.0, 150.0};
  CompBandConfig high{-6.0, 2.0, 15.0, 150.0};
};

// 3-band dynamics with Linkwitz-Riley 4th-order crossovers.
//
// The crossover pair (LP(f1), LP(f2), each LR4 = two cascaded Butterworth
// biquads) splits the signal as:
//   low  = LP(f1)
//   mid  = LP(f2) - low
//   high = x - LP(f2)
// which sums back to the input exactly — the LR property keeps the bands
// phase-aligned, so compressing one band never smears the others.
//
// Threading / allocation:
//   configure()            control thread, before the stream starts
//   set_* parameter calls  control thread, safe while running (atomics;
//                          crossover changes are applied at the next
//                          process() call with the biquad state preserved)
//   process()              audio callback only, allocation/lock free
class MultibandCompressor {
 public:
  void configure(int sample_rate, int channels, const MultibandConfig& config);
  void reset() noexcept;

  // Live parameter updates (control thread, safe while running).
  void set_enabled(bool enabled) noexcept;
  void set_threshold_db(int band, double db) noexcept;
  void set_ratio(int band, double ratio) noexcept;
  void set_attack_ms(int band, double ms) noexcept;
  void set_release_ms(int band, double ms) noexcept;
  void set_crossovers(double low_hz, double high_hz) noexcept;

  // Processes [frames] interleaved samples in place (stereo-linked gain).
  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] bool active() const noexcept { return enabled_.load(std::memory_order_relaxed); }

 private:
  struct BandState {
    std::atomic<double> threshold_db{-12.0};
    std::atomic<double> ratio{4.0};
    std::atomic<double> attack_ms{10.0};
    std::atomic<double> release_ms{120.0};
    // Per-sample one-pole coefficients (atomics: recomputed by the control
    // thread, read by the callback).
    std::atomic<double> attack_coef{1.0};
    std::atomic<double> release_coef{0.05};
    double envelope = 0.0;       // linear peak envelope (callback-only)
    double gain_smoothed = 1.0;  // linear gain applied to the band
  };

  // A pending crossover change (applied at the next process() call).
  struct PendingCrossover {
    std::atomic<bool> pending{false};
    double low_hz = 200.0;
    double high_hz = 2000.0;
  };

  void apply_crossovers(double low_hz, double high_hz);
  void update_coefficients(int band);

  Biquad lp1_a_;
  Biquad lp1_b_;
  Biquad lp2_a_;
  Biquad lp2_b_;
  BandState band_[3];
  PendingCrossover crossover_pending_;
  std::atomic<bool> enabled_{false};

  // Scratch (interleaved, allocated once): low / mid / high / input copy.
  std::vector<float> scratch_low_;
  std::vector<float> scratch_mid_;
  std::vector<float> scratch_high_;
  std::vector<float> scratch_input_;
  int channels_ = 2;
  int sample_rate_ = 48000;
};

}  // namespace dsp
