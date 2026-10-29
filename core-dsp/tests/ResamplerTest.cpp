// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/Resampler.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

std::vector<float> MakeSine(std::size_t frames, double sample_rate, double frequency,
                            float amplitude) {
  constexpr double kTwoPi = 6.283185307179586476925286766559;
  std::vector<float> result(frames * 2);
  for (std::size_t i = 0; i < frames; ++i) {
    const auto sample =
        amplitude *
        static_cast<float>(std::sin(kTwoPi * frequency * static_cast<double>(i) / sample_rate));
    result[i * 2] = sample;
    result[i * 2 + 1] = sample;
  }
  return result;
}

float Rms(const std::vector<float>& samples) {
  if (samples.empty()) {
    return 0.0f;
  }
  double sum = 0.0;
  for (const float sample : samples) {
    sum += static_cast<double>(sample) * static_cast<double>(sample);
  }
  return static_cast<float>(std::sqrt(sum / static_cast<double>(samples.size())));
}

void TestUpsample2x() {
  dsp::Resampler resampler(2, 2.0);
  CHECK(resampler.valid());
  CHECK(std::abs(resampler.ratio() - 2.0) < 1.0e-9);

  const auto input = MakeSine(4800, 48000.0, 440.0, 0.5f);
  std::vector<float> output;
  constexpr std::size_t kChunkFrames = 480;

  for (std::size_t offset = 0; offset < 4800; offset += kChunkFrames) {
    std::vector<float> chunk;
    CHECK(resampler.process(input.data() + offset * 2, kChunkFrames, &chunk));
    output.insert(output.end(), chunk.begin(), chunk.end());
  }

  std::vector<float> tail;
  CHECK(resampler.flush(&tail));
  output.insert(output.end(), tail.begin(), tail.end());

  const std::size_t frames = output.size() / 2;
  CHECK(frames >= 9400);
  CHECK(frames <= 9800);
  CHECK(Rms(output) > 0.2f);
  for (const float sample : output) {
    CHECK(std::isfinite(sample));
  }
}

void Test441To48() {
  const double ratio = 48000.0 / 44100.0;
  dsp::Resampler resampler(2, ratio);
  CHECK(resampler.valid());

  const auto input = MakeSine(4410, 44100.0, 440.0, 0.5f);
  std::vector<float> output;
  CHECK(resampler.process(input.data(), 4410, &output));

  std::vector<float> tail;
  CHECK(resampler.flush(&tail));
  output.insert(output.end(), tail.begin(), tail.end());

  const std::size_t frames = output.size() / 2;
  CHECK(frames >= 4700);
  CHECK(frames <= 4950);
  CHECK(Rms(output) > 0.2f);
}

void TestInvalidConfig() {
  dsp::Resampler zero_ratio(2, 0.0);
  CHECK(!zero_ratio.valid());

  dsp::Resampler zero_channels(0, 1.0);
  CHECK(!zero_channels.valid());
}

}  // namespace

int main() {
  TestUpsample2x();
  Test441To48();
  TestInvalidConfig();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
