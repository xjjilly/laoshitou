// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

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

  // Processes exactly [block_size] samples. input/output may not be null.
  void process_block(const float* input, float* output) noexcept;

  [[nodiscard]] bool valid() const noexcept { return valid_; }
  [[nodiscard]] std::size_t block_size() const noexcept { return block_size_; }

 private:
  std::unique_ptr<Fft> fft_;
  std::size_t block_size_ = 0;
  std::size_t fft_size_ = 0;
  std::size_t partition_count_ = 0;
  std::size_t history_position_ = 0;
  std::vector<std::vector<std::complex<float>>> ir_spectra_;
  std::vector<std::vector<std::complex<float>>> input_history_;
  std::vector<std::complex<float>> accumulator_;
  std::vector<std::complex<float>> time_buffer_;
  std::vector<float> overlap_;
  bool valid_ = false;
};

}  // namespace dsp
