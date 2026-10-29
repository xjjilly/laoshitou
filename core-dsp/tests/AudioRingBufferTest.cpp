// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cstddef>
#include <iostream>
#include <stdexcept>
#include <vector>

#include "dsp/AudioRingBuffer.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

std::vector<float> MakeRamp(std::size_t first, std::size_t samples) {
  std::vector<float> result(samples);
  for (std::size_t i = 0; i < samples; ++i) {
    result[i] = static_cast<float>(first + i);
  }
  return result;
}

void TestWriteRead() {
  dsp::AudioRingBuffer ring(1024, 2);
  CHECK(ring.capacity_frames() == 1024);
  CHECK(ring.channels() == 2);
  CHECK(ring.available_read_frames() == 0);
  CHECK(ring.available_write_frames() == 1024);

  const std::vector<float> input = MakeRamp(0, 1024 * 2);
  CHECK(ring.write(input.data(), 1024) == 1024);
  CHECK(ring.available_read_frames() == 1024);
  CHECK(ring.available_write_frames() == 0);

  std::vector<float> output(1024 * 2, 0.0f);
  CHECK(ring.read(output.data(), 1024) == 1024);
  CHECK(output == input);
  CHECK(ring.available_read_frames() == 0);
}

void TestFullAndEmpty() {
  dsp::AudioRingBuffer ring(16, 2);
  const std::vector<float> input = MakeRamp(0, 16 * 2);
  CHECK(ring.write(input.data(), 16) == 16);
  CHECK(ring.write(input.data(), 1) == 0);
  CHECK(ring.available_write_frames() == 0);

  std::vector<float> output(16 * 2, 0.0f);
  CHECK(ring.read(output.data(), 16) == 16);
  CHECK(output == input);
  CHECK(ring.read(output.data(), 1) == 0);
  CHECK(ring.available_read_frames() == 0);
}

void TestWrapAround() {
  dsp::AudioRingBuffer ring(8, 2);

  const std::vector<float> first = MakeRamp(0, 6 * 2);
  CHECK(ring.write(first.data(), 6) == 6);

  std::vector<float> out_first(4 * 2, 0.0f);
  CHECK(ring.read(out_first.data(), 4) == 4);
  std::vector<float> expected_first(first.begin(), first.begin() + 4 * 2);
  CHECK(out_first == expected_first);

  const std::vector<float> second = MakeRamp(100, 6 * 2);
  CHECK(ring.write(second.data(), 6) == 6);
  CHECK(ring.available_read_frames() == 8);

  std::vector<float> out_all(8 * 2, 0.0f);
  CHECK(ring.read(out_all.data(), 8) == 8);

  std::vector<float> expected;
  expected.insert(expected.end(), first.begin() + 4 * 2, first.end());
  expected.insert(expected.end(), second.begin(), second.end());
  CHECK(out_all == expected);
}

void TestPartialFramesAreWhole() {
  dsp::AudioRingBuffer ring(8, 2);
  const std::vector<float> input = MakeRamp(0, 5 * 2);
  CHECK(ring.write(input.data(), 5) == 5);

  std::vector<float> output(3 * 2, 0.0f);
  CHECK(ring.read(output.data(), 3) == 3);
  CHECK(ring.available_read_frames() == 2);
  CHECK(ring.available_write_frames() == 6);
}

void TestMono() {
  dsp::AudioRingBuffer ring(8, 1);
  CHECK(ring.capacity_frames() == 8);
  CHECK(ring.channels() == 1);

  const std::vector<float> input = MakeRamp(0, 8);
  CHECK(ring.write(input.data(), 8) == 8);
  std::vector<float> output(8, 0.0f);
  CHECK(ring.read(output.data(), 8) == 8);
  CHECK(output == input);
}

void TestCapacityRounding() {
  // 1000 frames * 2 channels = 2000 samples, rounded up to 2048 samples, so
  // the effective capacity is 1024 frames.
  dsp::AudioRingBuffer ring(1000, 2);
  CHECK(ring.capacity_frames() == 1024);
}

void TestInvalidArguments() {
  bool threw = false;
  try {
    dsp::AudioRingBuffer ring(0, 2);
  } catch (const std::invalid_argument&) {
    threw = true;
  }
  CHECK(threw);

  threw = false;
  try {
    dsp::AudioRingBuffer ring(16, 0);
  } catch (const std::invalid_argument&) {
    threw = true;
  }
  CHECK(threw);
}

}  // namespace

int main() {
  TestWriteRead();
  TestFullAndEmpty();
  TestWrapAround();
  TestPartialFramesAreWhole();
  TestMono();
  TestCapacityRounding();
  TestInvalidArguments();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
