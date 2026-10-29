// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <cstdio>
#include <vector>

#include "dsp/MultibandCompressor.h"

namespace {

bool IsFinite(const std::vector<float>& data) {
  for (const float value : data) {
    if (!std::isfinite(value)) {
      return false;
    }
  }
  return true;
}

double Rms(const std::vector<float>& data, std::size_t begin, std::size_t end) {
  double sum = 0.0;
  for (std::size_t i = begin; i < end && i < data.size(); ++i) {
    sum += static_cast<double>(data[i]) * static_cast<double>(data[i]);
  }
  return std::sqrt(sum / static_cast<double>(end - begin));
}

std::vector<float> MakeSine(int rate, double frequency, int frames) {
  std::vector<float> signal(static_cast<std::size_t>(frames) * 2U, 0.0f);
  for (int i = 0; i < frames; ++i) {
    const float s = static_cast<float>(
        std::sin(2.0 * 3.14159265358979 * frequency * static_cast<double>(i) / rate));
    signal[static_cast<std::size_t>(i) * 2U] = s;
    signal[static_cast<std::size_t>(i) * 2U + 1U] = s;
  }
  return signal;
}

// A full-scale 100 Hz sine must be compressed overall (the low band gets the
// strong gain reduction; the mid band's gentle 2:1 keeps the LR4 band
// overlap coherent) while a 1 kHz sine only gets the gentle mid treatment.
bool LowBandCompressesMidPasses() {
  constexpr int kRate = 48000;
  constexpr int kFrames = 48000;
  dsp::MultibandConfig config;
  config.enabled = true;
  config.crossover_low_hz = 200.0;
  config.crossover_high_hz = 2000.0;

  dsp::MultibandCompressor compressor;
  compressor.configure(kRate, 2, config);
  if (!compressor.active()) {
    std::printf("not active\n");
    return false;
  }

  // Feed in device-sized chunks.
  const auto process_all = [&](std::vector<float>* data) {
    int offset = 0;
    while (offset < kFrames) {
      const int chunk = std::min(480, kFrames - offset);
      compressor.process(data->data() + static_cast<std::size_t>(offset) * 2U, chunk, 2);
      offset += chunk;
    }
  };

  std::vector<float> low = MakeSine(kRate, 100.0, kFrames);
  process_all(&low);
  if (!IsFinite(low)) {
    std::printf("low: non-finite output\n");
    return false;
  }
  const double low_rms = Rms(low, 48000, static_cast<std::size_t>(kFrames) * 2U);
  std::printf("multiband low: rms %.3f (input 0.707, want < 0.65)\n", low_rms);
  if (low_rms >= 0.65) {
    return false;
  }

  compressor.reset();
  std::vector<float> mid = MakeSine(kRate, 1000.0, kFrames);
  process_all(&mid);
  if (!IsFinite(mid)) {
    std::printf("mid: non-finite output\n");
    return false;
  }
  const double mid_rms = Rms(mid, 48000, static_cast<std::size_t>(kFrames) * 2U);
  std::printf("multiband mid: rms %.3f (input 0.707, want 0.42-0.62, gentle 2:1)\n", mid_rms);
  if (mid_rms < 0.42 || mid_rms > 0.62) {
    return false;
  }
  return true;
}

// With every threshold at 0 dB the LR sum must reconstruct the input (the
// phase-alignment property), and a live crossover swap must stay finite.
bool ReconstructionAndLiveCrossover() {
  constexpr int kRate = 48000;
  constexpr int kFrames = 24000;
  dsp::MultibandConfig config;
  config.enabled = true;
  config.crossover_low_hz = 200.0;
  config.crossover_high_hz = 2000.0;
  config.low.threshold_db = 0.0;
  config.mid.threshold_db = 0.0;
  config.high.threshold_db = 0.0;

  dsp::MultibandCompressor compressor;
  compressor.configure(kRate, 2, config);

  // Mixed sine content.
  std::vector<float> signal(static_cast<std::size_t>(kFrames) * 2U, 0.0f);
  for (int i = 0; i < kFrames; ++i) {
    const float s = static_cast<float>(
        std::sin(2.0 * 3.14159265358979 * 100.0 * static_cast<double>(i) / kRate) +
        0.5 * std::sin(2.0 * 3.14159265358979 * 1000.0 * static_cast<double>(i) / kRate) +
        0.25 * std::sin(2.0 * 3.14159265358979 * 4000.0 * static_cast<double>(i) / kRate));
    signal[static_cast<std::size_t>(i) * 2U] = s;
    signal[static_cast<std::size_t>(i) * 2U + 1U] = s;
  }
  std::vector<float> work = signal;
  int offset = 0;
  while (offset < kFrames) {
    const int chunk = std::min(480, kFrames - offset);
    if (offset == 9600) {
      compressor.set_crossovers(300.0, 3000.0);  // live swap mid-stream
    }
    compressor.process(work.data() + static_cast<std::size_t>(offset) * 2U, chunk, 2);
    offset += chunk;
  }
  if (!IsFinite(work)) {
    std::printf("reconstruction: non-finite output\n");
    return false;
  }
  // Skip the crossover-swap transient and the filter warm-up.
  const double in_rms = Rms(signal, 4800 * 2U, static_cast<std::size_t>(kFrames) * 2U);
  const double out_rms = Rms(work, 4800 * 2U, static_cast<std::size_t>(kFrames) * 2U);
  std::printf("multiband reconstruct: in %.3f out %.3f\n", in_rms, out_rms);
  return out_rms > in_rms * 0.9 && out_rms < in_rms * 1.1;
}

}  // namespace

int main() {
  bool ok = true;
  ok &= LowBandCompressesMidPasses();
  ok &= ReconstructionAndLiveCrossover();
  std::printf(ok ? "MultibandCompressorTest PASS\n" : "MultibandCompressorTest FAIL\n");
  return ok ? 0 : 1;
}
