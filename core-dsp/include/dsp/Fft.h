// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <complex>
#include <cstddef>
#include <vector>

namespace dsp {

// Iterative radix-2 complex FFT. The twiddle table and bit-reversal order are
// built in the constructor; transform() performs no allocation, so it can be
// used from the audio callback after configure().
class Fft {
 public:
  explicit Fft(std::size_t size);
  ~Fft() = default;

  Fft(const Fft&) = delete;
  Fft& operator=(const Fft&) = delete;

  [[nodiscard]] bool valid() const noexcept;
  [[nodiscard]] std::size_t size() const noexcept { return size_; }

  void transform(std::complex<float>* data, bool inverse) const noexcept;

 private:
  std::size_t size_ = 0;
  std::vector<std::size_t> bit_reverse_;
  std::vector<std::complex<float>> twiddles_;
};

}  // namespace dsp
