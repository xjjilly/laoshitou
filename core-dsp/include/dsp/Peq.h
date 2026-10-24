// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <array>
#include <cstddef>
#include <vector>

#include "dsp/Biquad.h"

namespace dsp {

enum class PeqFilterType {
  kPeaking = 0,
  kLowShelf,
  kHighShelf,
  kHighPass,
  kLowPass,
};

struct PeqBandConfig {
  PeqFilterType type = PeqFilterType::kPeaking;
  double frequency = 1000.0;
  double gain_db = 0.0;
  double q = 1.0;
  bool enabled = true;

  // Shelves (LSC/HSC) can use the RBJ shelf slope S instead of Q.
  bool use_slope = false;
  double slope = 1.0;  // 0 < S <= 1, where 1 is the steepest shelf.

  // 1 = one biquad (2nd order); 2 = two cascaded biquads (4th order).
  // gain_db is the total band gain; each cascade stage uses gain_db / order,
  // so the asymptotic/peak gain stays the same.
  int order = 1;
};

struct PeqConfig {
  double preamp_db = 0.0;
  std::vector<PeqBandConfig> bands;
};

// Parametric EQ using the exact AutoEQ/RBJ formulas for PK/LSC/HSC.
//
// configure() must run on the control thread before playback starts. process()
// is real-time safe: it uses fixed storage and never allocates or locks.
class Peq {
 public:
  static constexpr std::size_t kMaxBands = 16;

  void configure(int sample_rate, int channels, const PeqConfig& config) noexcept;
  void reset() noexcept;

  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] std::size_t band_count() const noexcept;
  [[nodiscard]] bool active() const noexcept;

  // Designs one AutoEQ/RBJ biquad stage. For order == 2 the returned stage
  // uses half of the configured gain; Peq cascades two identical stages.
  static BiquadCoefficients DesignBand(const PeqBandConfig& band, int sample_rate) noexcept;

  // Response of one band in dB at [frequency_hz], including cascade order.
  static double BandResponseDb(const PeqBandConfig& band, double frequency_hz,
                               int sample_rate) noexcept;

 private:
  std::array<Biquad, kMaxBands * 2> bands_{};
  std::array<bool, kMaxBands> enabled_{};
  std::array<int, kMaxBands> order_{};
  std::size_t band_count_ = 0;
  int sample_rate_ = 0;
  int channels_ = 0;
  double preamp_gain_ = 1.0;
};

}  // namespace dsp
