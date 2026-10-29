// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "dsp/AudioRingBuffer.h"

namespace dsp {

// Compensates clock drift between an independent producer and consumer by
// resampling at a continuously adjusted ratio. This is the PC system-mode
// core: the capture endpoint and the render endpoint run on two unrelated
// clocks (virtual cable vs DAC crystal), and WASAPI exclusive mode does not
// synchronize them. Without compensation the FIFO slowly runs dry or
// overflows, producing periodic clicks.
//
// Model: the producer (capture callback) writes into an AudioRingBuffer; the
// consumer (render callback, the clock master) calls process() every block:
//   1. the ring content is moved into an internal FIFO,
//   2. a PI controller on the FIFO fill level adjusts the resampling ratio
//      (1.0 = passthrough) toward the producer's clock,
//   3. [frames] output frames are interpolated (cubic Hermite) at the
//      current ratio.
//
// configure() performs all allocation; reset()/process() are real-time safe
// (no allocation, no locks). The ratio is clamped to 1.0 +/- 0.5%; a
// one-pole smoother removes audible step changes.
class DriftCompensator {
 public:
  explicit DriftCompensator(int channels);

  // [fixed_ratio] > 0 disables the PI controller and pins the ratio (used by
  // tests and diagnostics). [fifo_capacity_frames] bounds the internal FIFO;
  // 0 selects two seconds at [sample_rate].
  void configure(int sample_rate, double target_fill_frames, double fixed_ratio = 0.0,
                 std::size_t fifo_capacity_frames = 0);
  void reset() noexcept;

  // Consumer side; called from the render callback. Reads the producer's
  // ring, adjusts the ratio and writes [frames] interleaved frames to [out].
  // Starvation produces silence and counts as underrun frames.
  void process(AudioRingBuffer* ring, float* out, std::size_t frames) noexcept;

  [[nodiscard]] double ratio() const noexcept { return ratio_; }
  [[nodiscard]] std::size_t fifo_fill_frames() const noexcept { return fill_frames_; }
  [[nodiscard]] int64_t underrun_frames() const noexcept { return underruns_; }

 private:
  float Sample(int channel, std::size_t index, float fallback) const noexcept;
  void ConsumeFrame(int channel) noexcept;

  int channels_ = 0;
  int sample_rate_ = 0;
  double target_fill_ = 0.0;
  double fixed_ratio_ = 0.0;
  double ratio_ = 1.0;
  double raw_ratio_ = 1.0;
  double integral_ = 0.0;
  double read_position_ = 0.0;

  // FIFO: interleaved planar-in-index layout (frame-major), power-of-two
  // capacity, indexed with a mask.
  std::vector<float> fifo_;
  std::size_t fifo_mask_ = 0;
  std::size_t fifo_capacity_ = 0;
  std::size_t fifo_read_ = 0;   // First valid frame index.
  std::size_t fifo_write_ = 0;  // Next free frame index.
  std::size_t fill_frames_ = 0;
  std::int64_t underruns_ = 0;

  // Continuity state for the interpolation edge.
  std::vector<float> last_sample_;
  bool has_history_ = false;

  // Push scratch; allocated in configure().
  std::vector<float> scratch_;
};

}  // namespace dsp
