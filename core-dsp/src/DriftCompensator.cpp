// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/DriftCompensator.h"

#include <algorithm>
#include <cmath>

namespace dsp {
namespace {

constexpr std::size_t kPushBlockFrames = 512;
constexpr double kMinRatio = 0.995;
constexpr double kMaxRatio = 1.005;
// PI gains, in ratio per frame of fill error (proportional) and per frame of
// accumulated error (integral). Sized for ppm-scale producer drift: a
// 100-frame deficit moves the ratio by ~20 ppm immediately and the integral
// closes the rest within seconds.
constexpr double kProportional = 2.0e-7;
constexpr double kIntegral = 2.0e-9;
// One-pole smoothing of the ratio applied to the stream (per process call).
constexpr double kSmoothing = 0.05;

}  // namespace

DriftCompensator::DriftCompensator(int channels) : channels_(channels) {}

void DriftCompensator::configure(int sample_rate, double target_fill_frames, double fixed_ratio,
                                 std::size_t fifo_capacity_frames) {
  sample_rate_ = sample_rate;
  target_fill_ = std::max(target_fill_frames, 4.0);
  fixed_ratio_ = fixed_ratio;

  if (fifo_capacity_frames == 0) {
    fifo_capacity_frames = static_cast<std::size_t>(std::max(sample_rate, 1) * 2);
  }
  std::size_t capacity = 64;
  while (capacity < fifo_capacity_frames) {
    capacity *= 2;
  }
  fifo_capacity_ = capacity;
  fifo_mask_ = capacity - 1;
  fifo_.assign(capacity * static_cast<std::size_t>(channels_), 0.0f);
  scratch_.assign(kPushBlockFrames * static_cast<std::size_t>(channels_), 0.0f);
  last_sample_.assign(static_cast<std::size_t>(channels_), 0.0f);
  reset();
}

void DriftCompensator::reset() noexcept {
  fifo_read_ = 0;
  fifo_write_ = 0;
  fill_frames_ = 0;
  underruns_ = 0;
  read_position_ = 0.0;
  ratio_ = 1.0;
  raw_ratio_ = 1.0;
  integral_ = 0.0;
  has_history_ = false;
}

// Reads the frame at [index] relative to fifo_read_; [fallback] is returned
// when the index is beyond the valid range (edge clamping).
float DriftCompensator::Sample(int channel, std::size_t index, float fallback) const noexcept {
  if (index >= fill_frames_) {
    return fallback;
  }
  const std::size_t frame = (fifo_read_ + index) & fifo_mask_;
  return fifo_[frame * static_cast<std::size_t>(channels_) + static_cast<std::size_t>(channel)];
}

void DriftCompensator::ConsumeFrame(int channel) noexcept {
  // Stores the frame being consumed as the continuity sample (the "x[-1]" of
  // the next interpolation).
  last_sample_[static_cast<std::size_t>(channel)] =
      fifo_[fifo_read_ * static_cast<std::size_t>(channels_) + static_cast<std::size_t>(channel)];
}

void DriftCompensator::process(AudioRingBuffer* ring, float* out, std::size_t frames) noexcept {
  if (out == nullptr || channels_ <= 0 || fifo_capacity_ == 0) {
    return;
  }

  // 1. Move producer data from the ring into the FIFO.
  if (ring != nullptr) {
    const std::size_t available = ring->available_read_frames();
    std::size_t remaining = available;
    while (remaining > 0 && fill_frames_ < fifo_capacity_) {
      const std::size_t free = fifo_capacity_ - fill_frames_;
      std::size_t chunk = std::min(remaining, free);
      chunk = std::min(chunk, kPushBlockFrames);
      const std::size_t got = ring->read(scratch_.data(), chunk);
      if (got == 0) {
        break;
      }
      remaining -= got;
      // Copy into the FIFO, handling the wrap.
      const std::size_t first_segment = std::min(got, fifo_capacity_ - fifo_write_);
      const std::size_t float_count = got * static_cast<std::size_t>(channels_);
      const std::size_t first_floats = first_segment * static_cast<std::size_t>(channels_);
      std::copy_n(scratch_.data(), first_floats,
                  fifo_.begin() + static_cast<std::ptrdiff_t>(fifo_write_ *
                                                              static_cast<std::size_t>(channels_)));
      if (first_segment < got) {
        std::copy_n(scratch_.data() + static_cast<std::ptrdiff_t>(first_floats),
                    float_count - first_floats, fifo_.begin());
      }
      fifo_write_ = (fifo_write_ + got) & fifo_mask_;
      fill_frames_ += got;
    }
  }

  // 2. Adjust the ratio with the PI controller (fill error in frames).
  if (fixed_ratio_ <= 0.0 && sample_rate_ > 0) {
    const double error = static_cast<double>(fill_frames_) - target_fill_;
    integral_ += error;
    const double raw = 1.0 + kProportional * error + kIntegral * integral_;
    raw_ratio_ = std::clamp(raw, kMinRatio, kMaxRatio);
    ratio_ += (raw_ratio_ - ratio_) * kSmoothing;
  } else if (fixed_ratio_ > 0.0) {
    ratio_ = fixed_ratio_;
  }

  // 3. Interpolate [frames] output frames. read_position_ is the fractional
  // position (in [0, 1)) inside the frame at fifo_read_; frames are consumed
  // in the same loop, so the position can never run ahead of the available
  // data. Starvation is fill == 0: the x0 sample is missing.
  for (std::size_t frame = 0; frame < frames; ++frame) {
    const std::size_t base = frame * static_cast<std::size_t>(channels_);
    if (fill_frames_ == 0) {
      // Starvation: drop the stale residue and emit silence for the rest of
      // the block. Recovery starts from a clean position.
      fifo_read_ = fifo_write_;
      read_position_ = 0.0;
      has_history_ = false;
      std::fill_n(out + base, (frames - frame) * static_cast<std::size_t>(channels_), 0.0f);
      underruns_ += static_cast<std::int64_t>(frames - frame);
      break;
    }

    for (int channel = 0; channel < channels_; ++channel) {
      const std::size_t offset = base + static_cast<std::size_t>(channel);
      const float x0 = Sample(channel, 0, 0.0f);
      const float x1 = Sample(channel, 1, x0);
      const float x2 = Sample(channel, 2, x1);
      const float x_minus = has_history_ ? last_sample_[static_cast<std::size_t>(channel)] : x0;

      const double t = read_position_;
      const double t2 = t * t;
      const double t3 = t2 * t;
      const double c_minus = -0.5 * t3 + t2 - 0.5 * t;
      const double c0 = 1.5 * t3 - 2.5 * t2 + 1.0;
      const double c1 = -1.5 * t3 + 2.0 * t2 + 0.5 * t;
      const double c2 = 0.5 * t3 - 0.5 * t2;
      out[offset] = static_cast<float>(c_minus * x_minus + c0 * x0 + c1 * x1 + c2 * x2);
    }

    read_position_ += ratio_;
    // Consume whole frames; at most two per output frame at ratio <= 1.005.
    while (read_position_ >= 1.0 && fill_frames_ > 0) {
      for (int channel = 0; channel < channels_; ++channel) {
        ConsumeFrame(channel);
      }
      has_history_ = true;
      fifo_read_ = (fifo_read_ + 1) & fifo_mask_;
      --fill_frames_;
      read_position_ -= 1.0;
    }
  }
}

}  // namespace dsp
