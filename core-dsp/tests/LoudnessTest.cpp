// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/Loudness.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846264338327950288;

std::vector<float> MakeSine(double frequency, std::size_t frames) {
  std::vector<float> samples(frames * 2);
  for (std::size_t i = 0; i < frames; ++i) {
    const auto value =
        static_cast<float>(0.1 * std::sin(2.0 * kPi * frequency * static_cast<double>(i) /
                                          static_cast<double>(kRate)));
    samples[i * 2] = value;
    samples[i * 2 + 1] = value;
  }
  return samples;
}

double Rms(const std::vector<float>& samples, std::size_t start_frame) {
  const std::size_t start = start_frame * 2;
  if (start >= samples.size()) {
    return 0.0;
  }
  double sum = 0.0;
  for (std::size_t i = start; i < samples.size(); ++i) {
    sum += static_cast<double>(samples[i]) * static_cast<double>(samples[i]);
  }
  return std::sqrt(sum / static_cast<double>(samples.size() - start));
}

double GainAt(double frequency, double volume) {
  dsp::Loudness loudness;
  loudness.configure(kRate, 2);
  loudness.set_volume(volume);
  auto samples = MakeSine(frequency, kRate);
  // Run two blocks so the amount has converged before the measured region.
  loudness.process(samples.data(), kRate / 2, 2);
  loudness.process(samples.data() + (kRate / 2) * 2, kRate / 2, 2);
  const double wet = Rms(samples, kRate / 2);
  const double dry = 0.1 / std::sqrt(2.0);
  return wet / dry;
}

void TestLoudnessBoost() {
  const double bass_off = GainAt(60.0, 1.0);
  const double bass_on = GainAt(60.0, 0.2);
  CHECK(std::abs(bass_off - 1.0) < 0.02);
  CHECK(bass_on > 1.20);

  const double treble_off = GainAt(10000.0, 1.0);
  const double treble_on = GainAt(10000.0, 0.2);
  CHECK(std::abs(treble_off - 1.0) < 0.02);
  CHECK(treble_on > 1.05);

  const double mid_on = GainAt(1000.0, 0.2);
  CHECK(std::abs(mid_on - 1.0) < 0.15);
}

void TestVolumeClamp() {
  dsp::Loudness loudness;
  loudness.configure(kRate, 2);
  loudness.set_volume(-1.0);
  auto samples = MakeSine(60.0, 4096);
  loudness.process(samples.data(), 4096, 2);
  for (const float sample : samples) {
    CHECK(std::isfinite(sample));
  }
}

}  // namespace

int main() {
  TestLoudnessBoost();
  TestVolumeClamp();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
