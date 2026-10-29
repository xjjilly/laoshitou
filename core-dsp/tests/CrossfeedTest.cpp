// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/Crossfeed.h"

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

std::vector<float> MakeLeftOnlySine(std::size_t frames, int sample_rate, double frequency,
                                    float amplitude) {
  std::vector<float> result(frames * 2, 0.0f);
  for (std::size_t i = 0; i < frames; ++i) {
    result[i * 2] =
        amplitude * static_cast<float>(std::sin(2.0 * kPi * frequency * static_cast<double>(i) /
                                                static_cast<double>(sample_rate)));
  }
  return result;
}

double RmsChannel(const std::vector<float>& samples, std::size_t start_frame, int channel) {
  const std::size_t start = start_frame * 2;
  double sum = 0.0;
  std::size_t count = 0;
  for (std::size_t i = start + static_cast<std::size_t>(channel); i < samples.size(); i += 2) {
    sum += static_cast<double>(samples[i]) * static_cast<double>(samples[i]);
    ++count;
  }
  return count == 0 ? 0.0 : std::sqrt(sum / static_cast<double>(count));
}

void TestCrossfeedCreatesOppositeChannelEnergy() {
  dsp::Crossfeed crossfeed(48000, dsp::CrossfeedPreset::kWeak);
  CHECK(crossfeed.valid());

  auto samples = MakeLeftOnlySine(48000, 48000, 1000.0, 0.5f);
  crossfeed.process(samples.data(), 48000, 2);

  const double left_rms = RmsChannel(samples, 24000, 0);
  const double right_rms = RmsChannel(samples, 24000, 1);
  CHECK(left_rms > 0.1);
  CHECK(right_rms > 0.001);
  CHECK(right_rms < left_rms);
  for (const float sample : samples) {
    CHECK(std::isfinite(sample));
  }
}

void TestMonoBypass() {
  dsp::Crossfeed crossfeed(48000, dsp::CrossfeedPreset::kWeak);
  CHECK(crossfeed.valid());

  std::vector<float> mono(100);
  for (std::size_t i = 0; i < mono.size(); ++i) {
    mono[i] = static_cast<float>(i) * 0.001f;
  }
  const auto original = mono;
  crossfeed.process(mono.data(), 100, 1);
  CHECK(mono == original);
}

void TestReset() {
  dsp::Crossfeed crossfeed(48000, dsp::CrossfeedPreset::kMedium);
  CHECK(crossfeed.valid());

  auto first = MakeLeftOnlySine(4096, 48000, 1000.0, 0.5f);
  crossfeed.process(first.data(), 4096, 2);
  crossfeed.reset();

  auto second = MakeLeftOnlySine(4096, 48000, 1000.0, 0.5f);
  crossfeed.process(second.data(), 4096, 2);
  for (const float sample : second) {
    CHECK(std::isfinite(sample));
  }
}

}  // namespace

int main() {
  TestCrossfeedCreatesOppositeChannelEnergy();
  TestMonoBypass();
  TestReset();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
