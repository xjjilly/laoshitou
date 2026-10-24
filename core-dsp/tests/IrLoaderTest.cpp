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

#include "dsp/IrLoader.h"

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

void TestStereoWav() {
  const std::string path = "ir_loader_test_stereo.wav";
  WritePcm16Wav(path, 48000, 2, {0.25f, -0.25f, -0.5f, 0.5f, 0.0f, 0.0f, 0.75f, -0.75f});

  dsp::IrData ir;
  std::string error;
  CHECK(dsp::LoadIrWav(path, &ir, &error));
  CHECK(error.empty());
  CHECK(ir.sample_rate == 48000);
  CHECK(ir.frames == 4U);
  CHECK(std::abs(ir.left[0] - 0.25f) < 1.0e-4f);
  CHECK(std::abs(ir.right[0] + 0.25f) < 1.0e-4f);
  CHECK(std::abs(ir.left[1] + 0.5f) < 1.0e-4f);
  CHECK(std::abs(ir.right[3] + 0.75f) < 1.0e-4f);
  std::remove(path.c_str());
}

void TestMonoDuplicates() {
  const std::string path = "ir_loader_test_mono.wav";
  WritePcm16Wav(path, 44100, 1, {0.1f, -0.2f, 0.3f});

  dsp::IrData ir;
  std::string error;
  CHECK(dsp::LoadIrWav(path, &ir, &error));
  CHECK(ir.frames == 3U);
  CHECK(ir.left == ir.right);
  std::remove(path.c_str());
}

void TestInvalidFile() {
  const std::string path = "ir_loader_test_invalid.wav";
  {
    std::ofstream out(path, std::ios::binary);
    out.write("NOPE", 4);
  }
  dsp::IrData ir;
  std::string error;
  CHECK(!dsp::LoadIrWav(path, &ir, &error));
  CHECK(!error.empty());
  std::remove(path.c_str());
}

}  // namespace

int main() {
  TestStereoWav();
  TestMonoDuplicates();
  TestInvalidFile();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
