// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <iostream>
#include <vector>

#include "dsp/AudioRingBuffer.h"
#include "dsp/DriftCompensator.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

constexpr int kChannels = 2;
constexpr int kSampleRate = 48000;
constexpr std::size_t kBlock = 480;

// Produces a deterministic sine, frame by frame.
class SineProducer {
 public:
  explicit SineProducer(double hz, int channels) : channels_(channels) {
    increment_ = 2.0 * 3.14159265358979323846 * hz / kSampleRate;
  }
  void fill(float* out, std::size_t frames) {
    for (std::size_t f = 0; f < frames; ++f) {
      const float sample = 0.5f * static_cast<float>(std::sin(phase_));
      for (int ch = 0; ch < channels_; ++ch) {
        out[f * static_cast<std::size_t>(channels_) + static_cast<std::size_t>(ch)] = sample;
      }
      phase_ += increment_;
      if (phase_ > 2.0 * 3.14159265358979323846) {
        phase_ -= 2.0 * 3.14159265358979323846;
      }
    }
  }

 private:
  int channels_;
  double phase_ = 0.0;
  double increment_ = 0.0;
};

void TestFixedRatioPassthrough() {
  // Ratio 1.0 must pass samples through bit-exactly (t = 0 -> x0 weight 1).
  dsp::AudioRingBuffer ring(kSampleRate, kChannels);
  dsp::DriftCompensator compensator(kChannels);
  compensator.configure(kSampleRate, /*target_fill=*/kBlock, /*fixed_ratio=*/1.0);

  SineProducer producer(440.0, kChannels);
  std::vector<float> pushed(kBlock * kChannels);
  std::vector<float> out(kBlock * kChannels);

  // Steady state: push one block, pull one block, repeat.
  for (int round = 0; round < 50; ++round) {
    producer.fill(pushed.data(), kBlock);
    CHECK(ring.write(pushed.data(), kBlock) == kBlock);
    compensator.process(&ring, out.data(), kBlock);
    for (std::size_t i = 0; i < kBlock * kChannels; ++i) {
      CHECK(out[i] == pushed[i]);
    }
  }
  CHECK(compensator.underrun_frames() == 0);
}

void TestFixedRatioResamples() {
  // Ratio 1.001 consumes input slightly faster than the producer: after a
  // long run the output frame count must match input / ratio.
  dsp::AudioRingBuffer ring(kSampleRate * 4, kChannels);
  dsp::DriftCompensator compensator(kChannels);
  compensator.configure(kSampleRate, /*target_fill=*/kBlock, /*fixed_ratio=*/1.001);

  SineProducer producer(440.0, kChannels);
  std::vector<float> pushed((kBlock + 1) * kChannels);
  std::vector<float> out(kBlock * kChannels);
  constexpr int kRounds = 2000;
  // The producer feeds 481 frames per round (1.00208x), slightly faster than
  // the 1.001 consumer, so the FIFO never drains.
  constexpr std::size_t kPushFrames = kBlock + 1;
  std::size_t total_in = 0;
  std::size_t total_out = 0;

  for (int round = 0; round < kRounds; ++round) {
    producer.fill(pushed.data(), kPushFrames);
    const std::size_t written = ring.write(pushed.data(), kPushFrames);
    total_in += written;
    if (written == 0) {
      // The consumer must be able to keep up: a full ring means the
      // compensator is not pulling, which fails the test.
      CHECK(false);
      break;
    }
    compensator.process(&ring, out.data(), kBlock);
    total_out += kBlock;
    if (compensator.underrun_frames() > 0) {
      CHECK(false);
      break;
    }
  }

  const double expected_out = static_cast<double>(total_in) / 1.001;
  CHECK(std::abs(static_cast<double>(total_out) - expected_out) <
        static_cast<double>(total_in) * 0.01);
  CHECK(compensator.underrun_frames() == 0);
}

void TestFixedRatioSineRate() {
  // A 440 Hz sine resampled at ratio 1.002 must come out at ~440.88 Hz:
  // verify with zero crossings.
  dsp::AudioRingBuffer ring(kSampleRate * 4, kChannels);
  dsp::DriftCompensator compensator(kChannels);
  compensator.configure(kSampleRate, /*target_fill=*/kBlock, /*fixed_ratio=*/1.002);

  SineProducer producer(440.0, kChannels);
  std::vector<float> pushed((kBlock + 1) * kChannels);
  std::vector<float> out(kBlock * kChannels);
  constexpr int kRounds = 400;  // ~4 s of audio
  constexpr std::size_t kPushFrames = kBlock + 1;
  int zero_crossings = 0;
  bool previous_positive = false;
  bool have_previous = false;

  for (int round = 0; round < kRounds; ++round) {
    producer.fill(pushed.data(), kPushFrames);
    if (ring.write(pushed.data(), kPushFrames) == 0) {
      CHECK(false);
      break;
    }
    compensator.process(&ring, out.data(), kBlock);
    for (std::size_t f = 0; f < kBlock; ++f) {
      const float sample = out[f * static_cast<std::size_t>(kChannels)];
      const bool positive = sample > 0.0f;
      if (have_previous && positive != previous_positive) {
        ++zero_crossings;
      }
      previous_positive = positive;
      have_previous = true;
    }
  }

  // Frequency = crossings / 2 / duration. Duration ~= total_out / 48000.
  const double duration = static_cast<double>(kRounds) * kBlock / kSampleRate;
  const double frequency = zero_crossings / 2.0 / duration;
  const double expected = 440.0 * 1.002;
  CHECK(std::abs(frequency - expected) < expected * 0.01);
}

void TestAdaptiveConvergence() {
  // Producer runs 0.999x of the consumer clock: the PI controller must lower
  // the ratio to ~0.999 and hold the FIFO near the target without underruns.
  dsp::AudioRingBuffer ring(kSampleRate * 4, kChannels);
  dsp::DriftCompensator compensator(kChannels);
  compensator.configure(kSampleRate, /*target_fill=*/kBlock * 2);

  SineProducer producer(440.0, kChannels);
  std::vector<float> pushed(kBlock * kChannels);
  std::vector<float> out(kBlock * kChannels);
  constexpr int kRounds = 5000;  // ~50 s simulated
  std::size_t deficit_accumulator = 0;

  // Prefill the ring so the PI controller starts near the target instead of
  // at zero (a real system prefills before starting the output stream).
  std::vector<float> prefill(kBlock * 2 * kChannels);
  producer.fill(prefill.data(), kBlock * 2);
  CHECK(ring.write(prefill.data(), kBlock * 2) == kBlock * 2);

  for (int round = 0; round < kRounds; ++round) {
    // Producer delivers 480 frames per round, minus one frame every 25th
    // round: 480 - 1/25 = 479.96, i.e. a 0.99992x clock.
    std::size_t push_frames = kBlock;
    deficit_accumulator += 40;  // 1 frame deficit per 25 rounds at 1000/25
    if (deficit_accumulator >= 1000) {
      deficit_accumulator -= 1000;
      push_frames -= 1;
    }
    producer.fill(pushed.data(), push_frames);
    if (ring.write(pushed.data(), push_frames) == 0) {
      CHECK(false);
      break;
    }
    compensator.process(&ring, out.data(), kBlock);
    if (compensator.underrun_frames() > 0) {
      CHECK(false);
      break;
    }
  }

  // After convergence the ratio tracks the producer clock.
  CHECK(std::abs(compensator.ratio() - 0.99992) < 0.001);
  // The FIFO holds near the target (allow generous transient tolerance).
  CHECK(compensator.fifo_fill_frames() > kBlock / 2);
  CHECK(compensator.fifo_fill_frames() < kBlock * 4);
  CHECK(compensator.underrun_frames() == 0);
}

void TestStarvationProducesSilence() {
  dsp::AudioRingBuffer ring(kSampleRate, kChannels);
  dsp::DriftCompensator compensator(kChannels);
  compensator.configure(kSampleRate, kBlock, /*fixed_ratio=*/1.0);

  std::vector<float> out(kBlock * kChannels, 1.0f);
  compensator.process(&ring, out.data(), kBlock);
  for (float sample : out) {
    CHECK(sample == 0.0f);
  }
  CHECK(compensator.underrun_frames() == static_cast<int64_t>(kBlock));
}

}  // namespace

int main() {
  TestFixedRatioPassthrough();
  TestFixedRatioResamples();
  TestFixedRatioSineRate();
  TestAdaptiveConvergence();
  TestStarvationProducesSilence();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
