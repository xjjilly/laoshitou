// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/PartitionedConvolver.h"

#include <algorithm>
#include <complex>
#include <cstddef>

namespace dsp {
namespace {

bool IsPowerOfTwo(std::size_t value) { return value != 0 && (value & (value - 1)) == 0; }

}  // namespace

void PartitionedConvolver::configure(const float* ir, std::size_t ir_length,
                                     std::size_t block_size) {
  valid_ = false;
  block_size_ = 0;
  fft_size_ = 0;
  partition_count_ = 0;
  history_position_ = 0;
  ir_spectra_.clear();
  input_history_.clear();
  accumulator_.clear();
  time_buffer_.clear();
  overlap_.clear();
  fft_.reset();

  if (ir == nullptr || ir_length == 0 || block_size < 16 || !IsPowerOfTwo(block_size)) {
    return;
  }

  block_size_ = block_size;
  fft_size_ = block_size * 2;
  fft_ = std::make_unique<Fft>(fft_size_);
  if (!fft_->valid()) {
    fft_.reset();
    return;
  }

  partition_count_ = (ir_length + block_size_ - 1) / block_size_;
  ir_spectra_.resize(partition_count_);
  input_history_.assign(partition_count_, std::vector<std::complex<float>>(
                                              fft_size_, std::complex<float>(0.0f, 0.0f)));
  accumulator_.assign(fft_size_, std::complex<float>(0.0f, 0.0f));
  time_buffer_.assign(fft_size_, std::complex<float>(0.0f, 0.0f));
  overlap_.assign(block_size_, 0.0f);

  for (std::size_t partition = 0; partition < partition_count_; ++partition) {
    std::fill(time_buffer_.begin(), time_buffer_.end(), std::complex<float>(0.0f, 0.0f));
    const std::size_t offset = partition * block_size_;
    const std::size_t count = std::min(block_size_, ir_length - offset);
    for (std::size_t i = 0; i < count; ++i) {
      time_buffer_[i] = std::complex<float>(ir[offset + i], 0.0f);
    }
    fft_->transform(time_buffer_.data(), false);
    ir_spectra_[partition] = time_buffer_;
  }

  history_position_ = 0;
  valid_ = true;
}

void PartitionedConvolver::reset() noexcept {
  if (!valid_) {
    return;
  }
  for (auto& history : input_history_) {
    std::fill(history.begin(), history.end(), std::complex<float>(0.0f, 0.0f));
  }
  std::fill(accumulator_.begin(), accumulator_.end(), std::complex<float>(0.0f, 0.0f));
  std::fill(overlap_.begin(), overlap_.end(), 0.0f);
  history_position_ = 0;
}

void PartitionedConvolver::process_block(const float* input, float* output) noexcept {
  if (input == nullptr || output == nullptr) {
    return;
  }
  if (!valid_ || !fft_ || block_size_ == 0) {
    std::copy(input, input + block_size_, output);
    return;
  }

  for (std::size_t i = 0; i < block_size_; ++i) {
    time_buffer_[i] = std::complex<float>(input[i], 0.0f);
  }
  for (std::size_t i = block_size_; i < fft_size_; ++i) {
    time_buffer_[i] = std::complex<float>(0.0f, 0.0f);
  }
  fft_->transform(time_buffer_.data(), false);
  input_history_[history_position_] = time_buffer_;

  std::fill(accumulator_.begin(), accumulator_.end(), std::complex<float>(0.0f, 0.0f));
  std::size_t index = history_position_;
  for (std::size_t partition = 0; partition < partition_count_; ++partition) {
    const auto& spectrum = ir_spectra_[partition];
    const auto& history = input_history_[index];
    for (std::size_t bin = 0; bin < fft_size_; ++bin) {
      accumulator_[bin] += history[bin] * spectrum[bin];
    }
    index = (index + partition_count_ - 1) % partition_count_;
  }

  fft_->transform(accumulator_.data(), true);
  for (std::size_t i = 0; i < block_size_; ++i) {
    output[i] = static_cast<float>(accumulator_[i].real() + overlap_[i]);
    overlap_[i] = static_cast<float>(accumulator_[block_size_ + i].real());
  }

  history_position_ = (history_position_ + 1) % partition_count_;
}

}  // namespace dsp
