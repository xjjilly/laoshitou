// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/AudioRingBuffer.h"

#include <bit>
#include <cstdint>
#include <limits>
#include <memory>
#include <stdexcept>
#include <vector>

extern "C" {
#include "pa_ringbuffer.h"
}

namespace dsp {
namespace {

std::size_t NextPowerOfTwo(std::size_t value) {
  if (value == 0) {
    return 0;
  }
  if (value > (std::numeric_limits<std::size_t>::max() >> 1)) {
    throw std::length_error("AudioRingBuffer capacity overflow");
  }
  return std::bit_ceil(value);
}

}  // namespace

struct AudioRingBuffer::Impl {
  std::vector<float> storage;
  PaUtilRingBuffer ring{};
  std::size_t capacity_frames = 0;
  int channels = 0;
};

AudioRingBuffer::AudioRingBuffer(std::size_t capacity_frames, int channels)
    : impl_(std::make_unique<Impl>()) {
  if (channels <= 0) {
    throw std::invalid_argument("AudioRingBuffer: channels must be positive");
  }
  if (capacity_frames == 0) {
    throw std::invalid_argument("AudioRingBuffer: capacity must be positive");
  }

  const auto channel_count = static_cast<std::size_t>(channels);
  if (capacity_frames >
      std::numeric_limits<std::size_t>::max() / channel_count) {
    throw std::length_error("AudioRingBuffer: capacity overflow");
  }
  const std::size_t requested_samples = capacity_frames * channel_count;
  const std::size_t sample_capacity = NextPowerOfTwo(requested_samples);

  if (sample_capacity >
      static_cast<std::size_t>(std::numeric_limits<ring_buffer_size_t>::max())) {
    throw std::length_error("AudioRingBuffer: exceeds ring_buffer_size_t");
  }

  impl_->storage.assign(sample_capacity, 0.0f);
  impl_->channels = channels;
  impl_->capacity_frames = sample_capacity / channel_count;

  const ring_buffer_size_t element_count =
      static_cast<ring_buffer_size_t>(sample_capacity);
  if (PaUtil_InitializeRingBuffer(&impl_->ring,
                                  static_cast<ring_buffer_size_t>(sizeof(float)),
                                  element_count, impl_->storage.data()) != 0) {
    throw std::runtime_error("AudioRingBuffer: initialization failed");
  }
}

AudioRingBuffer::~AudioRingBuffer() = default;

AudioRingBuffer::AudioRingBuffer(AudioRingBuffer&&) noexcept = default;

AudioRingBuffer& AudioRingBuffer::operator=(AudioRingBuffer&&) noexcept = default;

std::size_t AudioRingBuffer::write(const float* interleaved,
                                   std::size_t frames) noexcept {
  if (interleaved == nullptr || frames == 0) {
    return 0;
  }
  if (frames > impl_->capacity_frames) {
    frames = impl_->capacity_frames;
  }

  const auto channel_count = static_cast<std::size_t>(impl_->channels);
  const std::size_t elements = frames * channel_count;
  const ring_buffer_size_t written = PaUtil_WriteRingBuffer(
      &impl_->ring, interleaved, static_cast<ring_buffer_size_t>(elements));
  return static_cast<std::size_t>(written) / channel_count;
}

std::size_t AudioRingBuffer::read(float* interleaved,
                                  std::size_t frames) noexcept {
  if (interleaved == nullptr || frames == 0) {
    return 0;
  }
  if (frames > impl_->capacity_frames) {
    frames = impl_->capacity_frames;
  }

  const auto channel_count = static_cast<std::size_t>(impl_->channels);
  const std::size_t elements = frames * channel_count;
  const ring_buffer_size_t read_count = PaUtil_ReadRingBuffer(
      &impl_->ring, interleaved, static_cast<ring_buffer_size_t>(elements));
  return static_cast<std::size_t>(read_count) / channel_count;
}

std::size_t AudioRingBuffer::available_read_frames() const noexcept {
  const ring_buffer_size_t available =
      PaUtil_GetRingBufferReadAvailable(&impl_->ring);
  return static_cast<std::size_t>(available) /
         static_cast<std::size_t>(impl_->channels);
}

std::size_t AudioRingBuffer::available_write_frames() const noexcept {
  const ring_buffer_size_t available =
      PaUtil_GetRingBufferWriteAvailable(&impl_->ring);
  return static_cast<std::size_t>(available) /
         static_cast<std::size_t>(impl_->channels);
}

void AudioRingBuffer::reset() noexcept {
  PaUtil_FlushRingBuffer(&impl_->ring);
}

std::size_t AudioRingBuffer::capacity_frames() const noexcept {
  return impl_->capacity_frames;
}

int AudioRingBuffer::channels() const noexcept { return impl_->channels; }

}  // namespace dsp
