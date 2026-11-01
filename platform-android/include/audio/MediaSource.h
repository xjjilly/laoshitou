// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstdint>
#include <string>

namespace audio {

// Random-access byte source for a decoder input. Implementations must be
// safe for one decoder thread; control-thread close() happens only after the
// decoder thread has been joined.
class MediaSource {
 public:
  virtual ~MediaSource() = default;

  // Reads up to `size` bytes at `position`.
  // Returns the number of bytes read (> 0), 0 at EOF, or -1 on error.
  virtual int read_at(std::int64_t position, void* buffer, int size) = 0;

  // Total size in bytes, or -1 when unknown.
  virtual std::int64_t size() = 0;

  [[nodiscard]] virtual std::string last_error() const { return {}; }
};

}  // namespace audio
