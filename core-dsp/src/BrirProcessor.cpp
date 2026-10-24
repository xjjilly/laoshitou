// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/BrirProcessor.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "dsp/IrLoader.h"
#include "dsp/Resampler.h"

namespace dsp {
namespace {

constexpr std::size_t kBlockSize = 2048;
constexpr double kMaxIrSeconds = 2.0;
constexpr float kNormalizePeak = 0.9f;

bool ResampleIr(IrData* ir, int target_rate) {
  if (ir == nullptr || target_rate <= 0 || ir->sample_rate <= 0) {
    return false;
  }
  if (ir->sample_rate == target_rate) {
    return true;
  }

  const double ratio = static_cast<double>(target_rate) / static_cast<double>(ir->sample_rate);
  Resampler resampler(2, ratio, ResamplerQuality::kBest);
  if (!resampler.valid()) {
    return false;
  }

  std::vector<float> interleaved(ir->frames * 2);
  for (std::size_t i = 0; i < ir->frames; ++i) {
    interleaved[i * 2] = ir->left[i];
    interleaved[i * 2 + 1] = ir->right[i];
  }

  std::vector<float> output;
  if (!resampler.process(interleaved.data(), ir->frames, &output)) {
    return false;
  }
  std::vector<float> tail;
  if (resampler.flush(&tail)) {
    output.insert(output.end(), tail.begin(), tail.end());
  }
  if (output.size() < 2) {
    return false;
  }

  ir->frames = output.size() / 2;
  ir->left.resize(ir->frames);
  ir->right.resize(ir->frames);
  for (std::size_t i = 0; i < ir->frames; ++i) {
    ir->left[i] = output[i * 2];
    ir->right[i] = output[i * 2 + 1];
  }
  ir->sample_rate = target_rate;
  return true;
}

void NormalizeIr(IrData* ir) {
  float peak = 0.0f;
  for (std::size_t i = 0; i < ir->frames; ++i) {
    peak = std::max(peak, std::abs(ir->left[i]));
    peak = std::max(peak, std::abs(ir->right[i]));
  }
  if (peak <= 0.0f) {
    return;
  }
  const float scale = kNormalizePeak / peak;
  for (std::size_t i = 0; i < ir->frames; ++i) {
    ir->left[i] *= scale;
    ir->right[i] *= scale;
  }
}

}  // namespace

bool BrirProcessor::configure(int sample_rate, int channels, const std::string& ir_path,
                              double mix) {
  active_ = false;
  mix_ = std::clamp(mix, 0.0, 1.0);
  if (sample_rate <= 0 || channels <= 0 || ir_path.empty() || mix_ <= 0.0) {
    return false;
  }

  IrData ir;
  std::string error;
  if (!LoadIrWav(ir_path, &ir, &error)) {
    return false;
  }
  if (!ResampleIr(&ir, sample_rate)) {
    return false;
  }
  if (ir.frames == 0) {
    return false;
  }

  const std::size_t max_frames =
      static_cast<std::size_t>(static_cast<double>(sample_rate) * kMaxIrSeconds);
  if (ir.frames > max_frames) {
    ir.frames = max_frames;
    ir.left.resize(max_frames);
    ir.right.resize(max_frames);
  }
  NormalizeIr(&ir);

  block_size_ = kBlockSize;
  input_position_ = 0;
  wet_read_position_ = 0;
  wet_available_ = 0;
  dry_position_ = 0;
  input_left_.assign(block_size_, 0.0f);
  input_right_.assign(block_size_, 0.0f);
  wet_left_.assign(block_size_, 0.0f);
  wet_right_.assign(block_size_, 0.0f);
  dry_left_.assign(block_size_, 0.0f);
  dry_right_.assign(block_size_, 0.0f);

  left_convolver_.configure(ir.left.data(), ir.frames, block_size_);
  right_convolver_.configure(ir.right.data(), ir.frames, block_size_);
  if (!left_convolver_.valid() || !right_convolver_.valid()) {
    left_convolver_ = PartitionedConvolver{};
    right_convolver_ = PartitionedConvolver{};
    return false;
  }

  active_ = true;
  return true;
}

void BrirProcessor::reset() noexcept {
  left_convolver_.reset();
  right_convolver_.reset();
  std::fill(input_left_.begin(), input_left_.end(), 0.0f);
  std::fill(input_right_.begin(), input_right_.end(), 0.0f);
  std::fill(wet_left_.begin(), wet_left_.end(), 0.0f);
  std::fill(wet_right_.begin(), wet_right_.end(), 0.0f);
  std::fill(dry_left_.begin(), dry_left_.end(), 0.0f);
  std::fill(dry_right_.begin(), dry_right_.end(), 0.0f);
  input_position_ = 0;
  wet_read_position_ = 0;
  wet_available_ = 0;
  dry_position_ = 0;
}

void BrirProcessor::process(float* interleaved, int frames, int channels) noexcept {
  if (!active_ || interleaved == nullptr || frames <= 0 || channels < 2 || block_size_ == 0) {
    return;
  }

  const double dry_gain = 1.0 - mix_;
  for (int frame = 0; frame < frames; ++frame) {
    const std::size_t base = static_cast<std::size_t>(frame) * static_cast<std::size_t>(channels);
    const float dry_left = interleaved[base];
    const float dry_right = interleaved[base + 1];

    // Pop the wet sample produced by the previous block first. The newly
    // produced block (if any) starts on the next frame, keeping wet aligned
    // with the B-frame-delayed dry path.
    float wet_left = 0.0f;
    float wet_right = 0.0f;
    if (wet_available_ > 0) {
      wet_left = wet_left_[wet_read_position_];
      wet_right = wet_right_[wet_read_position_];
      ++wet_read_position_;
      --wet_available_;
    }

    const float delayed_left = dry_left_[dry_position_];
    const float delayed_right = dry_right_[dry_position_];
    dry_left_[dry_position_] = dry_left;
    dry_right_[dry_position_] = dry_right;
    dry_position_ = (dry_position_ + 1) % block_size_;

    input_left_[input_position_] = dry_left;
    input_right_[input_position_] = dry_right;
    ++input_position_;
    if (input_position_ == block_size_) {
      left_convolver_.process_block(input_left_.data(), wet_left_.data());
      right_convolver_.process_block(input_right_.data(), wet_right_.data());
      input_position_ = 0;
      wet_read_position_ = 0;
      wet_available_ = block_size_;
    }

    interleaved[base] = static_cast<float>(dry_gain * delayed_left + mix_ * wet_left);
    interleaved[base + 1] = static_cast<float>(dry_gain * delayed_right + mix_ * wet_right);
  }
}

}  // namespace dsp
