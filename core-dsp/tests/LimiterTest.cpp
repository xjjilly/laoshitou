// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/Limiter.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

constexpr double kPi = 3.14159265358979323846264338327950288;

std::vector<float> MakeSine(std::size_t frames, int sample_rate, double frequency,
                            float amplitude) {
  std::vector<float> result(frames * 2);
  for (std::size_t i = 0; i < frames; ++i) {
    const auto sample =
        amplitude * static_cast<float>(std::sin(2.0 * kPi * frequency * static_cast<double>(i) /
                                                static_cast<double>(sample_rate)));
    result[i * 2] = sample;
    result[i * 2 + 1] = sample;
  }
  return result;
}

double MaxAbsFrom(const std::vector<float>& samples, std::size_t start_frame) {
  double maximum = 0.0;
  const std::size_t start = start_frame * 2;
  for (std::size_t i = start; i < samples.size(); ++i) {
    maximum = std::max(maximum, std::fabs(static_cast<double>(samples[i])));
  }
  return maximum;
}

double RmsFrom(const std::vector<float>& samples, std::size_t start_frame) {
  const std::size_t start = start_frame * 2;
  double sum = 0.0;
  for (std::size_t i = start; i < samples.size(); ++i) {
    sum += static_cast<double>(samples[i]) * static_cast<double>(samples[i]);
  }
  return std::sqrt(sum / static_cast<double>(samples.size() - start));
}

void TestHighLevelIsLimited() {
  dsp::Limiter limiter;
  limiter.configure(48000, 2, -1.0, 100.0, 5.0);
  CHECK(limiter.active());

  auto samples = MakeSine(48000, 48000, 1000.0, 2.0f);
  limiter.process(samples.data(), 48000, 2);

  constexpr std::size_t kLookaheadFrames = 240;
  const double maximum = MaxAbsFrom(samples, kLookaheadFrames);
  CHECK(maximum <= 0.91);
  CHECK(maximum > 0.5);
  for (const float sample : samples) {
    CHECK(std::isfinite(sample));
  }
}

void TestCustomThreshold() {
  dsp::Limiter limiter;
  limiter.configure(48000, 2, -6.0, 100.0, 5.0);

  auto samples = MakeSine(48000, 48000, 1000.0, 2.0f);
  limiter.process(samples.data(), 48000, 2);

  constexpr std::size_t kLookaheadFrames = 240;
  const double maximum = MaxAbsFrom(samples, kLookaheadFrames);
  CHECK(maximum <= 0.505);
  CHECK(maximum > 0.3);
}

void TestLowLevelPasses() {
  dsp::Limiter limiter;
  limiter.configure(48000, 2, -1.0, 100.0, 5.0);

  auto samples = MakeSine(48000, 48000, 1000.0, 0.1f);
  const double reference = RmsFrom(samples, 24000);
  limiter.process(samples.data(), 48000, 2);
  const double processed = RmsFrom(samples, 24000);

  const double ratio = processed / reference;
  CHECK(ratio > 0.95);
  CHECK(ratio < 1.05);
}

void TestConfigureAndReset() {
  dsp::Limiter limiter;
  limiter.configure(44100, 2, -1.0, 80.0, 4.0);
  auto samples = MakeSine(8192, 44100, 440.0, 0.2f);
  limiter.process(samples.data(), 8192, 2);
  limiter.reset();
  limiter.process(samples.data(), 8192, 2);
  for (const float sample : samples) {
    CHECK(std::isfinite(sample));
  }
}

}  // namespace

int main() {
  TestHighLevelIsLimited();
  TestCustomThreshold();
  TestLowLevelPasses();
  TestConfigureAndReset();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
