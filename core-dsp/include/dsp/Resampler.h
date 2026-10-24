// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <memory>
#include <vector>

namespace dsp {

enum class ResamplerQuality {
  kAuto = 0,
  kBest = 1,
  kMedium = 2,
  kFastest = 3,
};

// Streaming sample-rate converter backed by libsamplerate.
//
// kAuto uses SRC_SINC_BEST_QUALITY for upsampling and mild downsampling, and
// SRC_SINC_MEDIUM_QUALITY when the ratio is below 0.75 (high-rate sources such
// as DSD 352.8 kHz -> 48/192 kHz), where the best converter is too slow for
// real time on mobile.
// kBest/kMedium/kFastest force one converter for benchmarking and tuning.
//
// This is intentionally not a real-time class: process() may allocate and
// libsamplerate is CPU-heavy. It belongs on the supplier/decoder thread, never
// in the Oboe callback.
class Resampler {
 public:
  Resampler(int channels, double ratio, ResamplerQuality quality = ResamplerQuality::kAuto);
  ~Resampler();

  Resampler(Resampler&&) noexcept;
  Resampler& operator=(Resampler&&) noexcept;

  Resampler(const Resampler&) = delete;
  Resampler& operator=(const Resampler&) = delete;

  [[nodiscard]] bool valid() const noexcept;
  [[nodiscard]] double ratio() const noexcept;

  // Processes interleaved float frames. Appends the generated frames to `out`.
  // Returns false on error.
  bool process(const float* input, std::size_t input_frames, std::vector<float>* out);

  // Flushes the converter at end of input. Appends the remaining frames.
  bool flush(std::vector<float>* out);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace dsp
