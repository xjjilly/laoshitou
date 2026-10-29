// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <atomic>
#include <complex>
#include <cstddef>
#include <memory>
#include <vector>

#include "dsp/Fft.h"

namespace dsp {

// Uniform partitioned overlap-add convolution for a mono impulse response.
//
// The IR is split into [block_size] partitions, each transformed to the
// frequency domain once. process_block() feeds one block and returns one
// block; a frequency-domain delay line provides O(partitions) complex
// multiplies per block instead of a direct FIR. No allocation happens in
// process_block().
class PartitionedConvolver {
 public:
  void configure(const float* ir, std::size_t ir_length, std::size_t block_size);
  void reset() noexcept;

  // Control-thread hot swap: replaces the IR with another one of the same
  // length without allocating. The new kernel is transformed into the
  // inactive double-buffer set and picked up by the next process_block()
  // call (no mid-block tear).
  void set_ir(const float* ir) noexcept;

  // Processes exactly [block_size] samples. input/output may not be null.
  void process_block(const float* input, float* output) noexcept;

  [[nodiscard]] bool valid() const noexcept { return valid_; }
  [[nodiscard]] std::size_t block_size() const noexcept { return block_size_; }

 private:
  // Transforms the IR partitions of [set] (0 or 1) into the frequency domain.
  void FillSpectra(const float* ir, std::size_t ir_length, int set);

  std::unique_ptr<Fft> fft_;
  std::size_t block_size_ = 0;
  std::size_t fft_size_ = 0;
  std::size_t partition_count_ = 0;
  std::size_t history_position_ = 0;
  // Double-buffered kernel spectra: set_ir() writes the inactive set, the
  // atomic index flips the active one for the next block.
  std::vector<std::vector<std::complex<float>>> ir_spectra_[2];
  std::atomic<int> ir_active_{0};
  std::vector<std::vector<std::complex<float>>> input_history_;
  std::vector<std::complex<float>> accumulator_;
  std::vector<std::complex<float>> time_buffer_;
  std::vector<float> overlap_;
  bool valid_ = false;
};

}  // namespace dsp
