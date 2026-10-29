// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <vector>

#include "dsp/FirEqProcessor.h"
#include "dsp/Peq.h"

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

// A 1 kHz sine with a +6 dB peaking band must come out ~2x after the
// latency window, with no dropped or duplicated audio.
bool SineThroughFirGainsUp() {
  constexpr int kRate = 48000;
  dsp::PeqConfig config;
  dsp::PeqBandConfig band;
  band.type = dsp::PeqFilterType::kPeaking;
  band.frequency = 1000.0;
  band.gain_db = 6.0;
  band.q = 1.41;
  band.enabled = true;
  config.bands.push_back(band);

  std::vector<float> kernel;
  std::string error;
  if (!dsp::DesignLinearPhaseEq(config, kRate, 2048, &kernel, &error)) {
    std::printf("design failed: %s\n", error.c_str());
    return false;
  }
  // Verify the designed kernel's magnitude response at 1 kHz directly.
  {
    dsp::Fft fft(2048);
    std::vector<std::complex<float>> spectrum(2048);
    for (std::size_t i = 0; i < 2048; ++i) {
      spectrum[i] = std::complex<float>(kernel[i], 0.0f);
    }
    fft.transform(spectrum.data(), false);
    for (int bin = 40; bin <= 46; ++bin) {
      std::printf("kernel |H| bin %d (%.0f Hz): %.4f (%.2f dB)\n", bin,
                  static_cast<double>(bin) * kRate / 2048.0, std::abs(spectrum[bin]),
                  20.0 * std::log10(std::abs(spectrum[bin])));
    }
    // Exact magnitude at 1000 Hz via direct DFT (not grid-limited).
    {
      std::complex<double> sum(0.0, 0.0);
      const double omega = 2.0 * 3.14159265358979 * 1000.0 / kRate;
      for (std::size_t n = 0; n < kernel.size(); ++n) {
        const double phase = omega * static_cast<double>(n);
        sum += std::complex<double>(kernel[n] * std::cos(phase), -kernel[n] * std::sin(phase));
      }
      std::printf("kernel |H| at exactly 1000 Hz: %.4f (%.2f dB)\n", std::abs(sum),
                  20.0 * std::log10(std::abs(sum)));
    }
  }

  dsp::FirEqProcessor processor;
  processor.configure(kRate, 2, 2048, 512);
  if (!processor.valid()) {
    std::printf("configure failed\n");
    return false;
  }
  processor.set_kernel(kernel.data());

  // Feed non-block-aligned chunks (480 frames, like WASAPI shared mode).
  constexpr int kFrames = 48000;
  std::vector<float> signal(kFrames * 2, 0.0f);
  for (int i = 0; i < kFrames; ++i) {
    const float s = static_cast<float>(
        std::sin(2.0 * 3.14159265358979 * 1000.0 * static_cast<double>(i) / kRate));
    signal[static_cast<std::size_t>(i) * 2U] = s;
    signal[static_cast<std::size_t>(i) * 2U + 1U] = s;
  }
  std::vector<float> work = signal;
  int offset = 0;
  while (offset < kFrames) {
    const int chunk = std::min(480, kFrames - offset);
    processor.process(work.data() + static_cast<std::size_t>(offset) * 2U, chunk, 2);
    offset += chunk;
  }
  if (!IsFinite(work)) {
    std::printf("non-finite output\n");
    return false;
  }
  const int latency = processor.latency_frames();
  // Steady-state RMS after the latency + settle window.
  const std::size_t begin = static_cast<std::size_t>(latency + 4096);
  const std::size_t end = static_cast<std::size_t>(kFrames - 1024);
  const double out_rms = Rms(work, begin * 2U, end * 2U);
  const double in_rms = Rms(signal, begin * 2U, end * 2U);
  const double gain = out_rms / in_rms;
  std::printf("FIR EQ: in %.3f out %.3f gain %.3f (want ~2.0)\n", in_rms, out_rms, gain);
  return gain > 1.7 && gain < 2.3;
}

// The output must be a delayed copy of the input when the EQ is neutral.
bool IdentityKernelDelaysOnly() {
  constexpr int kRate = 48000;
  dsp::FirEqProcessor processor;
  processor.configure(kRate, 2, 2048, 512);
  if (!processor.valid()) {
    return false;
  }
  std::vector<float> kernel(2048, 0.0f);
  kernel[0] = 1.0f;
  processor.set_kernel(kernel.data());

  constexpr int kFrames = 8192;
  std::vector<float> signal(kFrames * 2, 0.0f);
  for (int i = 0; i < kFrames; ++i) {
    const float s = static_cast<float>(
        std::sin(2.0 * 3.14159265358979 * 440.0 * static_cast<double>(i) / kRate));
    signal[static_cast<std::size_t>(i) * 2U] = s;
  }
  std::vector<float> work = signal;
  int offset = 0;
  while (offset < kFrames) {
    const int chunk = std::min(512, kFrames - offset);
    processor.process(work.data() + static_cast<std::size_t>(offset) * 2U, chunk, 2);
    offset += chunk;
  }
  const int latency = processor.latency_frames();
  const std::size_t begin = static_cast<std::size_t>(latency + 2048);
  const std::size_t end = static_cast<std::size_t>(kFrames - 1024);
  const double out_rms = Rms(work, begin * 2U, end * 2U);
  const double in_rms = Rms(signal, begin * 2U, end * 2U);
  std::printf("FIR identity: in %.3f out %.3f\n", in_rms, out_rms);
  return out_rms > in_rms * 0.9 && out_rms < in_rms * 1.1;
}

// Decoders hand over whole packets (MP3 1152, FLAC/WAV 4096). For every
// realistic chunk size the output must stay a clean delayed copy of the
// input: no dropped, duplicated or reordered samples. This is the regression
// for the "linear-phase FIR plays at the wrong speed" report.
bool ChunkSizesPreserveStream() {
  constexpr int kRate = 48000;
  constexpr int kTotal = 96000;
  bool all_ok = true;
  for (const int chunk : {480, 512, 1024, 1152, 1536, 2048, 4096, 8192}) {
    dsp::FirEqProcessor processor;
    processor.configure(kRate, 2, 2048, 512);
    if (!processor.valid()) {
      std::printf("chunk %5d: configure failed\n", chunk);
      return false;
    }
    std::vector<float> kernel(2048, 0.0f);
    kernel[0] = 1.0f;
    processor.set_kernel(kernel.data());

    std::vector<float> work(static_cast<std::size_t>(kTotal) * 2U, 0.0f);
    for (int i = 0; i < kTotal; ++i) {
      work[static_cast<std::size_t>(i) * 2U] = static_cast<float>(i);
      work[static_cast<std::size_t>(i) * 2U + 1U] = static_cast<float>(i);
    }
    int offset = 0;
    while (offset < kTotal) {
      const int n = std::min(chunk, kTotal - offset);
      processor.process(work.data() + static_cast<std::size_t>(offset) * 2U, n, 2);
      offset += n;
    }

    // The stream may settle at a chunk-dependent latency, but it must stay a
    // contiguous ramp: every consecutive pair advances by exactly one sample.
    // Zeros (gaps), repeats (duplication) and reordering all break this.
    int bad = 0;
    int backward = 0;
    double max_err = 0.0;
    const int start = 8192;
    const int end = kTotal - 4096;
    for (int n = start; n < end; ++n) {
      const double out = work[static_cast<std::size_t>(n) * 2U];
      const double prev = work[static_cast<std::size_t>(n - 1) * 2U];
      const double step = out - prev;
      const double err = std::fabs(step - 1.0);
      if (err > 0.5) {
        ++bad;
        max_err = std::max(max_err, err);
      }
      if (step < 0.5) {
        ++backward;
      }
    }
    const bool ok = bad == 0 && backward == 0;
    std::printf("chunk %5d: bad=%d backward=%d max_step_err=%.0f %s\n", chunk, bad, backward,
                max_err, ok ? "OK" : "BROKEN");
    all_ok = all_ok && ok;
  }
  return all_ok;
}

}  // namespace

int main() {
  bool ok = true;
  ok &= SineThroughFirGainsUp();
  ok &= IdentityKernelDelaysOnly();
  ok &= ChunkSizesPreserveStream();
  std::printf(ok ? "FirEqProcessorTest PASS\n" : "FirEqProcessorTest FAIL\n");
  return ok ? 0 : 1;
}
