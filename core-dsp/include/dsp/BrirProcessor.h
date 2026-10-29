// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <string>
#include <vector>

#include "dsp/PartitionedConvolver.h"

namespace dsp {

// Convolution strategy for the BRIR stage.
//
// kAuto picks Direct for short IRs (<= kDirectMaxTaps) and Partitioned for
// long ones. Direct runs a time-domain FIR: near-zero extra memory, no block
// latency, CPU proportional to the IR length. Partitioned runs a uniform
// partitioned FFT: CPU nearly independent of the IR length at the cost of
// [block_size] frames of block latency.
enum class BrirMode {
  kAuto = 0,
  kDirect = 1,
  kPartitioned = 2,
};

// Stereo BRIR convolver with a wet/dry mix.
//
// The left input is convolved with the left-ear IR and the right input with
// the right-ear IR.
//
// Partitioned mode: a block-based partitioned FFT adds [block_size] frames of
// latency; the dry path is delayed by the same amount so the mix does not
// comb-filter.
//
// Direct mode: a time-domain FIR with no block latency; the wet path is
// inherently aligned with the dry path, so no delay lines are needed.
//
// configure() performs file I/O and allocation; process() is real-time safe.
class BrirProcessor {
 public:
  // [block_size] is the partitioned-mode block size in frames. It is clamped
  // to a power of two in [64, 8192]; Direct mode ignores it.
  bool configure(int sample_rate, int channels, const std::string& ir_path, double mix,
                 BrirMode mode = BrirMode::kAuto, std::size_t block_size = 512);
  void reset() noexcept;
  void process(float* interleaved, int frames, int channels) noexcept;

  // Live wet/dry mix update (control thread, safe while running): a plain
  // atomic store read per process() call; the dry-path delay stays fixed.
  void set_mix(double mix) noexcept {
    mix_.store(std::clamp(mix, 0.0, 1.0), std::memory_order_relaxed);
  }

  [[nodiscard]] bool active() const noexcept { return active_; }
  [[nodiscard]] BrirMode mode() const noexcept { return mode_; }
  [[nodiscard]] std::size_t latency_frames() const noexcept {
    return mode_ == BrirMode::kDirect ? 0 : block_size_;
  }

 private:
  PartitionedConvolver left_convolver_;
  PartitionedConvolver right_convolver_;
  std::size_t block_size_ = 512;

  // Direct-mode state: the time-domain IR and a circular input history.
  BrirMode mode_ = BrirMode::kAuto;
  std::vector<float> direct_left_;
  std::vector<float> direct_right_;
  std::vector<float> history_left_;
  std::vector<float> history_right_;
  // Index of the oldest history sample.
  std::size_t history_position_ = 0;

  // Partitioned-mode state.
  std::size_t input_position_ = 0;
  std::vector<float> input_left_;
  std::vector<float> input_right_;
  std::vector<float> wet_left_;
  std::vector<float> wet_right_;
  std::size_t wet_read_position_ = 0;
  std::size_t wet_available_ = 0;

  std::vector<float> dry_left_;
  std::vector<float> dry_right_;
  std::size_t dry_position_ = 0;

  std::atomic<double> mix_{0.0};
  bool active_ = false;
};

}  // namespace dsp
