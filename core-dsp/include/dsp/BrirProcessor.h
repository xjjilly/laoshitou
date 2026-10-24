// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "dsp/PartitionedConvolver.h"

namespace dsp {

// Stereo BRIR convolver with a wet/dry mix.
//
// The left input is convolved with the left-ear IR and the right input with
// the right-ear IR. A block-based partitioned FFT adds [block_size] frames of
// latency; the dry path is delayed by the same amount so the mix does not
// comb-filter. configure() performs file I/O and allocation; process() is
// real-time safe.
class BrirProcessor {
 public:
  bool configure(int sample_rate, int channels, const std::string& ir_path, double mix);
  void reset() noexcept;
  void process(float* interleaved, int frames, int channels) noexcept;

  [[nodiscard]] bool active() const noexcept { return active_; }
  [[nodiscard]] std::size_t latency_frames() const noexcept { return block_size_; }

 private:
  PartitionedConvolver left_convolver_;
  PartitionedConvolver right_convolver_;
  std::size_t block_size_ = 2048;
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

  double mix_ = 0.0;
  bool active_ = false;
};

}  // namespace dsp
