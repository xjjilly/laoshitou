// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/PartitionedConvolver.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

void TestMatchesDirectConvolution() {
  constexpr std::size_t kBlock = 16;
  constexpr std::size_t kBlocks = 3;
  constexpr std::size_t kFrames = kBlock * kBlocks;

  std::vector<float> ir(40);
  for (std::size_t i = 0; i < ir.size(); ++i) {
    ir[i] = std::exp(-0.08f * static_cast<float>(i)) * std::cos(0.3f * static_cast<float>(i));
  }

  std::vector<float> input(kFrames);
  for (std::size_t i = 0; i < kFrames; ++i) {
    input[i] =
        std::sin(0.21f * static_cast<float>(i)) + 0.25f * std::cos(0.07f * static_cast<float>(i));
  }

  std::vector<float> direct(kFrames, 0.0f);
  for (std::size_t n = 0; n < kFrames; ++n) {
    for (std::size_t k = 0; k < ir.size() && k <= n; ++k) {
      direct[n] += input[n - k] * ir[k];
    }
  }

  dsp::PartitionedConvolver convolver;
  convolver.configure(ir.data(), ir.size(), kBlock);
  CHECK(convolver.valid());
  CHECK(convolver.block_size() == kBlock);

  std::vector<float> output(kFrames, 0.0f);
  for (std::size_t block = 0; block < kBlocks; ++block) {
    convolver.process_block(input.data() + block * kBlock, output.data() + block * kBlock);
  }

  double max_error = 0.0;
  for (std::size_t i = 0; i < kFrames; ++i) {
    max_error = std::max(max_error, std::abs(static_cast<double>(output[i] - direct[i])));
    CHECK(std::isfinite(output[i]));
  }
  CHECK(max_error < 1.0e-3);
}

void TestInvalidConfiguration() {
  dsp::PartitionedConvolver convolver;
  const float ir = 1.0f;
  convolver.configure(&ir, 1, 15);
  CHECK(!convolver.valid());
  convolver.configure(nullptr, 0, 16);
  CHECK(!convolver.valid());
}

}  // namespace

int main() {
  TestMatchesDirectConvolution();
  TestInvalidConfiguration();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
