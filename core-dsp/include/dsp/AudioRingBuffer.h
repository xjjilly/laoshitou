// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <memory>

namespace dsp {

// Single-producer / single-consumer interleaved float ring buffer.
//
// The storage is pre-allocated by the constructor. write() and read() are
// lock-free and allocation-free, so the audio callback may call read().
// The effective capacity is rounded up so the number of samples is a power of
// two (required by the PortAudio ring buffer).
class AudioRingBuffer {
 public:
  AudioRingBuffer(std::size_t capacity_frames, int channels);
  ~AudioRingBuffer();

  AudioRingBuffer(AudioRingBuffer&&) noexcept;
  AudioRingBuffer& operator=(AudioRingBuffer&&) noexcept;

  AudioRingBuffer(const AudioRingBuffer&) = delete;
  AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

  // Returns the number of whole frames written.
  std::size_t write(const float* interleaved, std::size_t frames) noexcept;

  // Returns the number of whole frames read.
  std::size_t read(float* interleaved, std::size_t frames) noexcept;

  [[nodiscard]] std::size_t available_read_frames() const noexcept;
  [[nodiscard]] std::size_t available_write_frames() const noexcept;

  void reset() noexcept;

  [[nodiscard]] std::size_t capacity_frames() const noexcept;
  [[nodiscard]] int channels() const noexcept;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace dsp
