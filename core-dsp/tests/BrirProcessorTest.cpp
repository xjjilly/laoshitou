// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "dsp/BrirProcessor.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

void WriteLe16(std::ofstream& out, std::uint16_t value) {
  out.put(static_cast<char>(value & 0xFF));
  out.put(static_cast<char>((value >> 8) & 0xFF));
}

void WriteLe32(std::ofstream& out, std::uint32_t value) {
  out.put(static_cast<char>(value & 0xFF));
  out.put(static_cast<char>((value >> 8) & 0xFF));
  out.put(static_cast<char>((value >> 16) & 0xFF));
  out.put(static_cast<char>((value >> 24) & 0xFF));
}

void WritePcm16Wav(const std::string& path, int sample_rate, int channels,
                   const std::vector<float>& interleaved) {
  std::ofstream out(path, std::ios::binary);
  const std::uint32_t data_bytes =
      static_cast<std::uint32_t>(interleaved.size() * sizeof(std::int16_t));
  out.write("RIFF", 4);
  WriteLe32(out, 36 + data_bytes);
  out.write("WAVE", 4);
  out.write("fmt ", 4);
  WriteLe32(out, 16);
  WriteLe16(out, 1);
  WriteLe16(out, static_cast<std::uint16_t>(channels));
  WriteLe32(out, static_cast<std::uint32_t>(sample_rate));
  WriteLe32(out, static_cast<std::uint32_t>(sample_rate * channels * 2));
  WriteLe16(out, static_cast<std::uint16_t>(channels * 2));
  WriteLe16(out, 16);
  out.write("data", 4);
  WriteLe32(out, data_bytes);
  for (const float sample : interleaved) {
    const float clamped = std::clamp(sample, -1.0f, 1.0f);
    const auto value = static_cast<std::int16_t>(std::lround(clamped * 32767.0f));
    WriteLe16(out, static_cast<std::uint16_t>(value));
  }
}

std::string MakeIrFile() {
  const std::string path = "brir_processor_test_ir.wav";
  std::vector<float> ir(32 * 2, 0.0f);
  // Interleaved stereo: Left [0.5, 0.1, 0...], Right [0.25, -0.05, 0...].
  ir[0] = 0.5f;
  ir[1] = 0.25f;
  ir[2] = 0.1f;
  ir[3] = -0.05f;
  WritePcm16Wav(path, 48000, 2, ir);
  return path;
}

void TestImpulseAlignedWet() {
  const std::string path = MakeIrFile();
  dsp::BrirProcessor processor;
  CHECK(processor.configure(48000, 2, path, 1.0, dsp::BrirMode::kPartitioned, 2048));
  CHECK(processor.active());
  CHECK(processor.mode() == dsp::BrirMode::kPartitioned);
  CHECK(processor.latency_frames() == 2048U);

  constexpr int kFrames = 4096;
  std::vector<float> samples(kFrames * 2, 0.0f);
  samples[0] = 1.0f;
  samples[1] = 1.0f;
  processor.process(samples.data(), kFrames, 2);

  for (int i = 0; i < 2048; ++i) {
    CHECK(std::abs(samples[i * 2]) < 1.0e-5f);
    CHECK(std::abs(samples[i * 2 + 1]) < 1.0e-5f);
  }

  // Peak normalization scales the 0.5/0.25 stereo IR by 0.9/0.5 = 1.8.
  CHECK(std::abs(samples[2048 * 2] - 0.9f) < 1.0e-3f);
  CHECK(std::abs(samples[2048 * 2 + 1] - 0.45f) < 1.0e-3f);
  CHECK(std::abs(samples[2049 * 2] - 0.18f) < 1.0e-3f);
  CHECK(std::abs(samples[2049 * 2 + 1] + 0.09f) < 1.0e-3f);
  for (int i = 2050; i < kFrames; ++i) {
    CHECK(std::abs(samples[i * 2]) < 1.0e-5f);
    CHECK(std::abs(samples[i * 2 + 1]) < 1.0e-5f);
  }
  std::remove(path.c_str());
}

void TestDryWetMix() {
  const std::string path = MakeIrFile();
  dsp::BrirProcessor processor;
  CHECK(processor.configure(48000, 2, path, 0.5, dsp::BrirMode::kPartitioned, 2048));

  constexpr int kFrames = 4096;
  std::vector<float> samples(kFrames * 2, 0.0f);
  samples[0] = 1.0f;
  samples[1] = 1.0f;
  processor.process(samples.data(), kFrames, 2);

  // 0.5 * wet(0.9) + 0.5 * delayed dry(1.0)
  CHECK(std::abs(samples[2048 * 2] - 0.95f) < 1.0e-3f);
  CHECK(std::abs(samples[2048 * 2 + 1] - 0.725f) < 1.0e-3f);
  std::remove(path.c_str());
}

void TestMissingIr() {
  dsp::BrirProcessor processor;
  CHECK(!processor.configure(48000, 2, "does_not_exist.wav", 0.5));
  CHECK(!processor.active());
}

void TestDirectImpulse() {
  const std::string path = MakeIrFile();
  dsp::BrirProcessor processor;
  CHECK(processor.configure(48000, 2, path, 1.0, dsp::BrirMode::kDirect));
  CHECK(processor.active());
  CHECK(processor.mode() == dsp::BrirMode::kDirect);
  CHECK(processor.latency_frames() == 0U);

  // Direct mode has no block latency: the wet IR starts at frame 0.
  constexpr int kFrames = 64;
  std::vector<float> samples(kFrames * 2, 0.0f);
  samples[0] = 1.0f;
  samples[1] = 1.0f;
  processor.process(samples.data(), kFrames, 2);

  // Peak normalization scales the 0.5/0.25 stereo IR by 0.9/0.5 = 1.8.
  CHECK(std::abs(samples[0] - 0.9f) < 1.0e-3f);
  CHECK(std::abs(samples[1] - 0.45f) < 1.0e-3f);
  CHECK(std::abs(samples[2] - 0.18f) < 1.0e-3f);
  CHECK(std::abs(samples[3] + 0.09f) < 1.0e-3f);
  for (int i = 4; i < kFrames * 2; ++i) {
    CHECK(std::abs(samples[i]) < 1.0e-5f);
  }
  std::remove(path.c_str());
}

void TestAutoModeSelectsDirect() {
  const std::string path = MakeIrFile();
  dsp::BrirProcessor processor;
  // Default kAuto: the 32-sample IR is below the direct-mode threshold.
  CHECK(processor.configure(48000, 2, path, 1.0));
  CHECK(processor.mode() == dsp::BrirMode::kDirect);
  CHECK(processor.latency_frames() == 0U);
  std::remove(path.c_str());
}

void TestBlockSizeClamped() {
  const std::string path = MakeIrFile();
  dsp::BrirProcessor processor;
  // 100 is not a power of two: rounds down to 64.
  CHECK(processor.configure(48000, 2, path, 1.0, dsp::BrirMode::kPartitioned, 100));
  CHECK(processor.latency_frames() == 64U);
  std::remove(path.c_str());
}

void TestDirectMatchesPartitioned() {
  // A 96-frame stereo IR with deterministic pseudo-random content, long enough
  // to span two 64-frame partitions.
  std::vector<float> ir(96 * 2, 0.0f);
  std::uint32_t state = 12345u;
  for (float& sample : ir) {
    state = state * 1664525u + 1013904223u;
    sample = static_cast<float>((state >> 16) & 0xFFFF) / 32768.0f - 1.0f;
  }
  const std::string path = "brir_processor_test_eq.wav";
  WritePcm16Wav(path, 48000, 2, ir);

  dsp::BrirProcessor direct;
  CHECK(direct.configure(48000, 2, path, 1.0, dsp::BrirMode::kDirect));
  dsp::BrirProcessor partitioned;
  CHECK(partitioned.configure(48000, 2, path, 1.0, dsp::BrirMode::kPartitioned, 64));
  CHECK(partitioned.latency_frames() == 64U);

  constexpr int kFrames = 512;
  std::vector<float> direct_samples(kFrames * 2, 0.0f);
  std::vector<float> partitioned_samples(kFrames * 2, 0.0f);
  for (int i = 0; i < kFrames; ++i) {
    // An impulse train exercises every tap of the IR.
    const float value = (i % 7 == 0) ? 1.0f : 0.0f;
    direct_samples[i * 2] = value;
    direct_samples[i * 2 + 1] = -value;
  }
  partitioned_samples = direct_samples;
  direct.process(direct_samples.data(), kFrames, 2);
  partitioned.process(partitioned_samples.data(), kFrames, 2);

  // The partitioned output equals the direct output delayed by one block.
  constexpr int kLatency = 64;
  constexpr float kTolerance = 2.0e-3f;
  for (int t = kLatency; t < kFrames; ++t) {
    CHECK(std::abs(direct_samples[(t - kLatency) * 2] - partitioned_samples[t * 2]) < kTolerance);
    CHECK(std::abs(direct_samples[(t - kLatency) * 2 + 1] - partitioned_samples[t * 2 + 1]) <
          kTolerance);
  }
  std::remove(path.c_str());
}

}  // namespace

int main() {
  TestImpulseAlignedWet();
  TestDryWetMix();
  TestMissingIr();
  TestDirectImpulse();
  TestAutoModeSelectsDirect();
  TestBlockSizeClamped();
  TestDirectMatchesPartitioned();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
