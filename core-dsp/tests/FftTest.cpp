// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <array>
#include <cmath>
#include <complex>
#include <iostream>

#include "dsp/Fft.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

void TestImpulse() {
  dsp::Fft fft(8);
  CHECK(fft.valid());
  std::array<std::complex<float>, 8> data{};
  data[0] = 1.0f;
  fft.transform(data.data(), false);
  for (const auto& value : data) {
    CHECK(std::abs(value - std::complex<float>(1.0f, 0.0f)) < 1.0e-5f);
  }
  fft.transform(data.data(), true);
  CHECK(std::abs(data[0] - std::complex<float>(1.0f, 0.0f)) < 1.0e-5f);
  for (std::size_t i = 1; i < data.size(); ++i) {
    CHECK(std::abs(data[i]) < 1.0e-5f);
  }
}

void TestRoundTrip() {
  dsp::Fft fft(16);
  std::array<std::complex<float>, 16> data{};
  for (std::size_t i = 0; i < data.size(); ++i) {
    data[i] = std::complex<float>(std::sin(static_cast<float>(i) * 0.4f),
                                  std::cos(static_cast<float>(i) * 0.2f));
  }
  const auto original = data;
  fft.transform(data.data(), false);
  fft.transform(data.data(), true);
  for (std::size_t i = 0; i < data.size(); ++i) {
    CHECK(std::abs(data[i] - original[i]) < 1.0e-4f);
  }
}

void TestInvalidSize() {
  dsp::Fft fft(12);
  CHECK(!fft.valid());
}

}  // namespace

int main() {
  TestImpulse();
  TestRoundTrip();
  TestInvalidSize();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
