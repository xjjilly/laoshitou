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
  CHECK(processor.configure(48000, 2, path, 1.0));
  CHECK(processor.active());
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
  CHECK(processor.configure(48000, 2, path, 0.5));

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

}  // namespace

int main() {
  TestImpulseAlignedWet();
  TestDryWetMix();
  TestMissingIr();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
