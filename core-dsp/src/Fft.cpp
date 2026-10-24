// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Fft.h"

#include <cmath>
#include <cstddef>
#include <utility>

namespace dsp {
namespace {

constexpr double kPi = 3.14159265358979323846264338327950288;

bool IsPowerOfTwo(std::size_t value) { return value != 0 && (value & (value - 1)) == 0; }

}  // namespace

Fft::Fft(std::size_t size) {
  if (!IsPowerOfTwo(size) || size < 2) {
    return;
  }
  size_ = size;
  bit_reverse_.resize(size);
  std::size_t bits = 0;
  while ((std::size_t{1} << bits) < size) {
    ++bits;
  }
  for (std::size_t index = 0; index < size; ++index) {
    std::size_t reversed = 0;
    for (std::size_t bit = 0; bit < bits; ++bit) {
      if ((index & (std::size_t{1} << bit)) != 0) {
        reversed |= std::size_t{1} << (bits - 1 - bit);
      }
    }
    bit_reverse_[index] = reversed;
  }

  twiddles_.resize(size / 2);
  for (std::size_t index = 0; index < size / 2; ++index) {
    const double angle = -2.0 * kPi * static_cast<double>(index) / static_cast<double>(size);
    twiddles_[index] = std::complex<float>(static_cast<float>(std::cos(angle)),
                                           static_cast<float>(std::sin(angle)));
  }
}

bool Fft::valid() const noexcept { return size_ != 0; }

void Fft::transform(std::complex<float>* data, bool inverse) const noexcept {
  if (!valid() || data == nullptr) {
    return;
  }

  for (std::size_t index = 0; index < size_; ++index) {
    const std::size_t reversed = bit_reverse_[index];
    if (index < reversed) {
      std::swap(data[index], data[reversed]);
    }
  }

  for (std::size_t length = 2; length <= size_; length <<= 1) {
    const std::size_t half = length / 2;
    const std::size_t step = size_ / length;
    for (std::size_t start = 0; start < size_; start += length) {
      for (std::size_t offset = 0; offset < half; ++offset) {
        std::complex<float> twiddle = twiddles_[offset * step];
        if (inverse) {
          twiddle = std::conj(twiddle);
        }
        const std::complex<float> even = data[start + offset];
        const std::complex<float> odd = data[start + offset + half] * twiddle;
        data[start + offset] = even + odd;
        data[start + offset + half] = even - odd;
      }
    }
  }

  if (inverse) {
    const float scale = 1.0f / static_cast<float>(size_);
    for (std::size_t index = 0; index < size_; ++index) {
      data[index] *= scale;
    }
  }
}

}  // namespace dsp
