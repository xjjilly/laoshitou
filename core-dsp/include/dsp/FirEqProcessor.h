// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "dsp/Fft.h"
#include "dsp/PartitionedConvolver.h"
#include "dsp/Peq.h"

namespace dsp {

// Linear-phase FIR EQ: the same 16-band PeqConfig as the IIR chain, but the
// magnitude response is sampled on an FFT grid and transformed into a
// symmetric FIR kernel (frequency-sampling method), so the phase response is
// exactly linear. Convolution runs through uniform partitioned overlap-add
// (PartitionedConvolver), which is allocation/lock free in the audio thread.
//
// Latency = block_size + kernel_size / 2 frames (~35 ms at 44.1 kHz with the
// default 2048-tap kernel and 512-frame blocks) — inherent to linear phase.
//
// Threading:
//   configure()/reset()      control thread, before the stream starts
//   set_kernel()             control thread OR the callback thread (no
//                            allocation; the swap lands on the next block)
//   process()                audio callback only
class FirEqProcessor {
 public:
  // Largest per-call frame count handled by the staging buffer (well above
  // any device buffer size; larger calls are processed in chunks).
  static constexpr int kMaxProcessFrames = 8192;
  // Allocates the FFT plans, partition buffers and the internal block
  // buffers for a [kernel_size]-tap kernel processed in [block_size]
  // blocks (both powers of two).
  void configure(int sample_rate, int channels, std::size_t kernel_size, std::size_t block_size);
  void reset() noexcept;

  // Hot-swaps the convolution kernel (no allocation; next-block pickup).
  void set_kernel(const float* kernel) noexcept;

  // Processes [frames] interleaved stereo samples in place. Arbitrary frame
  // counts are buffered internally to [block_size] boundaries; the output
  // is delayed by one block plus half the kernel. Calls larger than
  // kMaxProcessFrames are handled in chunks, so decoded block sizes
  // (MP3 1152, FLAC/WAV 4096) never lose or repeat samples.
  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] bool valid() const noexcept { return valid_; }
  [[nodiscard]] int latency_frames() const noexcept {
    return block_size_ + static_cast<int>(kernel_size_ / 2);
  }

 private:
  // Processes one chunk of at most kMaxProcessFrames frames; the caller splits
  // larger buffers so the staging and ring capacities stay bounded.
  void process_chunk(float* interleaved, int count) noexcept;

  // Stereo partitioned convolvers (L/R) + decoupled input/output rings:
  // non-aligned chunk sizes neither drop nor duplicate audio.
  PartitionedConvolver convolver_l_;
  PartitionedConvolver convolver_r_;
  std::vector<float> in_ring_;   // interleaved input ring (2 blocks)
  std::vector<float> out_ring_;  // interleaved output ring (chunk + 2 blocks)
  std::vector<float> stage_;     // per-call input staging (in-place safety)
  std::vector<float> in_l_;      // planar split of the current block
  std::vector<float> in_r_;
  std::vector<float> out_l_;
  std::vector<float> out_r_;
  int in_capacity_ = 0;   // frames in the input ring (2 x block_size)
  int out_capacity_ = 0;  // frames in the output ring (>= kMaxProcessFrames)
  int in_read_ = 0;
  int in_write_ = 0;
  int in_count_ = 0;
  int out_read_ = 0;
  int out_write_ = 0;
  int out_count_ = 0;
  int channels_ = 2;
  std::size_t kernel_size_ = 0;
  int block_size_ = 0;
  bool valid_ = false;
};

// Designs a linear-phase FIR kernel matching the RBJ magnitude response of
// [config] (preamp + enabled bands) at [sample_rate]. [kernel] is resized to
// [kernel_size] (a power of two). No window is applied: the frequency grid
// density (kernel_size bins over the Nyquist range) already bounds the
// ripple for smooth EQ curves.
bool DesignLinearPhaseEq(const PeqConfig& config, int sample_rate, std::size_t kernel_size,
                         std::vector<float>* kernel, std::string* error);

}  // namespace dsp
