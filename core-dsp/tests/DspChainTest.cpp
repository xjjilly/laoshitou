// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

#include "dsp/DspChain.h"

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

void TestFullChain() {
  dsp::DspChainConfig config;
  config.eq_enabled = true;
  config.eq.bands.push_back(
      dsp::PeqBandConfig{dsp::PeqFilterType::kPeaking, 1000.0, 6.0, 1.0, true});
  config.crossfeed_enabled = true;
  config.crossfeed_preset = dsp::CrossfeedPreset::kWeak;
  config.limiter_enabled = true;

  dsp::DspChain chain;
  chain.configure(48000, 2, config);

  CHECK(chain.eq_band_count() == 1U);
  CHECK(chain.crossfeed_enabled());
  CHECK(chain.limiter_enabled());

  auto samples = MakeSine(48000, 48000, 1000.0, 0.05f);
  chain.process(samples.data(), 48000, 2);
  for (const float sample : samples) {
    CHECK(std::isfinite(sample));
  }
}

void TestStereoWidth() {
  constexpr std::size_t kFrames = 4096;
  auto source = MakeSine(kFrames, 48000, 440.0, 0.2f);
  for (std::size_t i = 0; i < kFrames; ++i) {
    source[i * 2 + 1] *= 0.5f;
  }

  dsp::DspChainConfig config;
  config.limiter_enabled = false;

  dsp::DspChain mono_chain;
  config.stereo_width = 0.0;
  mono_chain.configure(48000, 2, config);
  auto mono = source;
  mono_chain.process(mono.data(), kFrames, 2);
  for (std::size_t i = 0; i < kFrames; ++i) {
    CHECK(std::abs(mono[i * 2] - mono[i * 2 + 1]) < 1.0e-6f);
  }

  dsp::DspChain identity_chain;
  config.stereo_width = 1.0;
  identity_chain.configure(48000, 2, config);
  auto identity = source;
  identity_chain.process(identity.data(), kFrames, 2);
  CHECK(identity == source);

  dsp::DspChain wide_chain;
  config.stereo_width = 1.5;
  wide_chain.configure(48000, 2, config);
  auto wide = source;
  wide_chain.process(wide.data(), kFrames, 2);
  double original_side = 0.0;
  double wide_side = 0.0;
  for (std::size_t i = 0; i < kFrames; ++i) {
    original_side += std::abs(source[i * 2] - source[i * 2 + 1]);
    wide_side += std::abs(wide[i * 2] - wide[i * 2 + 1]);
  }
  CHECK(wide_side > original_side * 1.2);
}

void TestCrossfeedWidth() {
  constexpr std::size_t kFrames = 24000;
  auto source = MakeSine(kFrames, 48000, 100.0, 0.1f);
  for (std::size_t i = 0; i < kFrames; ++i) {
    source[i * 2 + 1] = 0.0f;
  }

  dsp::DspChainConfig config;
  config.crossfeed_enabled = true;
  config.limiter_enabled = false;

  auto measure_right = [&](double width) {
    config.crossfeed_width = width;
    dsp::DspChain chain;
    chain.configure(48000, 2, config);
    auto samples = source;
    chain.process(samples.data(), kFrames, 2);
    double right = 0.0;
    for (std::size_t i = 0; i < kFrames; ++i) {
      right += std::abs(static_cast<double>(samples[i * 2 + 1]));
    }
    return right;
  };

  const double narrow = measure_right(0.2);
  const double wide = measure_right(0.9);
  CHECK(narrow > 0.0);
  CHECK(wide > 0.0);
  CHECK(std::abs(narrow - wide) > narrow * 0.05);
}

void TestEmptyChainIsBypass() {
  dsp::DspChainConfig config;
  config.eq_enabled = false;
  config.crossfeed_enabled = false;
  config.limiter_enabled = false;

  dsp::DspChain chain;
  chain.configure(48000, 2, config);
  CHECK(!chain.crossfeed_enabled());
  CHECK(!chain.limiter_enabled());

  auto samples = MakeSine(4096, 48000, 440.0, 0.2f);
  const auto original = samples;
  chain.process(samples.data(), 4096, 2);
  CHECK(samples == original);
}

}  // namespace

int main() {
  TestFullChain();
  TestStereoWidth();
  TestCrossfeedWidth();
  TestEmptyChainIsBypass();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
