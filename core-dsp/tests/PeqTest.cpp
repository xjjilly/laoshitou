// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/Peq.h"

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

void TestPeakingBoost() {
  dsp::PeqConfig config;
  config.bands.push_back(dsp::PeqBandConfig{dsp::PeqFilterType::kPeaking, 1000.0, 6.0, 1.0, true});

  dsp::Peq peq;
  peq.configure(48000, 2, config);

  auto samples = MakeSine(48000, 48000, 1000.0, 0.1f);
  const auto reference = Rms(samples, 24000);
  peq.process(samples.data(), 48000, 2);
  const auto boosted = Rms(samples, 24000);

  const double ratio = boosted / reference;
  CHECK(ratio > 1.80);
  CHECK(ratio < 2.20);
  for (const float sample : samples) {
    CHECK(std::isfinite(sample));
  }
}

void TestPreamp() {
  dsp::PeqConfig config;
  config.preamp_db = -6.0;

  dsp::Peq peq;
  peq.configure(48000, 2, config);

  auto samples = MakeSine(48000, 48000, 1000.0, 0.1f);
  const auto reference = Rms(samples, 24000);
  peq.process(samples.data(), 48000, 2);
  const auto attenuated = Rms(samples, 24000);

  const double ratio = attenuated / reference;
  CHECK(ratio > 0.45);
  CHECK(ratio < 0.55);
}

void TestIdentity() {
  dsp::PeqConfig config;
  dsp::Peq peq;
  peq.configure(48000, 2, config);

  auto samples = MakeSine(4096, 48000, 440.0, 0.2f);
  const auto original = samples;
  peq.process(samples.data(), 4096, 2);
  CHECK(samples == original);
}

void TestLowShelf() {
  dsp::PeqConfig config;
  config.bands.push_back(dsp::PeqBandConfig{dsp::PeqFilterType::kLowShelf, 100.0, 6.0, 0.7, true});

  dsp::Peq peq;
  peq.configure(48000, 2, config);

  auto samples = MakeSine(48000, 48000, 50.0, 0.1f);
  const auto reference = Rms(samples, 24000);
  peq.process(samples.data(), 48000, 2);
  const auto boosted = Rms(samples, 24000);
  const double ratio = boosted / reference;
  CHECK(ratio > 1.70);
  CHECK(ratio < 2.30);
}

void TestCascadedShelfKeepsTotalGain() {
  dsp::PeqBandConfig single{dsp::PeqFilterType::kLowShelf, 100.0, 6.0, 0.7, true};
  dsp::PeqBandConfig cascaded = single;
  cascaded.order = 2;

  const double single_low = dsp::Peq::BandResponseDb(single, 20.0, 48000);
  const double cascaded_low = dsp::Peq::BandResponseDb(cascaded, 20.0, 48000);
  CHECK(std::abs(single_low - 6.0) < 0.2);
  CHECK(std::abs(cascaded_low - 6.0) < 0.2);

  // A 4th-order shelf transitions faster than a 2nd-order one.
  const double single_mid = dsp::Peq::BandResponseDb(single, 300.0, 48000);
  const double cascaded_mid = dsp::Peq::BandResponseDb(cascaded, 300.0, 48000);
  CHECK(cascaded_mid < single_mid);
}

void TestCascadedPeakKeepsCenterGain() {
  const dsp::PeqBandConfig single{dsp::PeqFilterType::kPeaking, 1000.0, 6.0, 1.0, true};
  dsp::PeqBandConfig cascaded = single;
  cascaded.order = 2;

  CHECK(std::abs(dsp::Peq::BandResponseDb(single, 1000.0, 48000) - 6.0) < 0.01);
  CHECK(std::abs(dsp::Peq::BandResponseDb(cascaded, 1000.0, 48000) - 6.0) < 0.05);
  CHECK(dsp::Peq::BandResponseDb(cascaded, 2000.0, 48000) <
        dsp::Peq::BandResponseDb(single, 2000.0, 48000));
}

void TestHighPassLowPass() {
  const dsp::PeqBandConfig high_pass{dsp::PeqFilterType::kHighPass, 100.0, 0.0, 0.707, true};
  CHECK(std::abs(dsp::Peq::BandResponseDb(high_pass, 1000.0, 48000)) < 0.1);
  CHECK(dsp::Peq::BandResponseDb(high_pass, 20.0, 48000) < -10.0);

  const dsp::PeqBandConfig low_pass{dsp::PeqFilterType::kLowPass, 10000.0, 0.0, 0.707, true};
  CHECK(std::abs(dsp::Peq::BandResponseDb(low_pass, 1000.0, 48000)) < 0.1);
  CHECK(dsp::Peq::BandResponseDb(low_pass, 20000.0, 48000) < -6.0);
}

void TestShelfSlope() {
  dsp::PeqBandConfig band{dsp::PeqFilterType::kLowShelf, 100.0, 6.0, 0.7, true};
  band.use_slope = true;
  band.slope = 0.5;

  const double low = dsp::Peq::BandResponseDb(band, 20.0, 48000);
  const double high = dsp::Peq::BandResponseDb(band, 10000.0, 48000);
  CHECK(std::abs(low - 6.0) < 0.5);
  CHECK(std::abs(high) < 0.1);

  band.slope = 1.0;
  const double steep_low = dsp::Peq::BandResponseDb(band, 20.0, 48000);
  CHECK(std::abs(steep_low - 6.0) < 0.5);
}

}  // namespace

int main() {
  TestPeakingBoost();
  TestPreamp();
  TestIdentity();
  TestLowShelf();
  TestCascadedShelfKeepsTotalGain();
  TestCascadedPeakKeepsCenterGain();
  TestHighPassLowPass();
  TestShelfSlope();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
