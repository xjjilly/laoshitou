// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/FirEqProcessor.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>

#include "dsp/Biquad.h"

namespace dsp {
namespace {

bool IsPowerOfTwo(std::size_t value) { return value != 0 && (value & (value - 1)) == 0; }

}  // namespace

void FirEqProcessor::configure(int sample_rate, int channels, std::size_t kernel_size,
                               std::size_t block_size) {
  valid_ = false;
  kernel_size_ = 0;
  block_size_ = 0;
  if (sample_rate <= 0 || channels < 2 || kernel_size < 256 || block_size < 64 ||
      !IsPowerOfTwo(kernel_size) || !IsPowerOfTwo(block_size) || kernel_size < block_size) {
    return;
  }
  channels_ = channels;
  kernel_size_ = kernel_size;
  block_size_ = static_cast<int>(block_size);

  std::vector<float> identity(kernel_size_, 0.0f);
  identity[0] = 1.0f;
  convolver_l_.configure(identity.data(), kernel_size_, block_size);
  convolver_r_.configure(identity.data(), kernel_size_, block_size);
  if (!convolver_l_.valid() || !convolver_r_.valid()) {
    return;
  }

  in_capacity_ = static_cast<int>(block_size) * 2;
  // One process() call can produce up to kMaxProcessFrames of output; the
  // output ring must hold a whole call (plus a block of carry-over), otherwise
  // the tail of each call overwrites the head and the stream plays back
  // repeated fragments (MP3/FLAC decode blocks are 1152/4096 frames).
  out_capacity_ = kMaxProcessFrames + static_cast<int>(block_size) * 2;
  in_ring_.assign(static_cast<std::size_t>(in_capacity_) * 2U, 0.0f);
  out_ring_.assign(static_cast<std::size_t>(out_capacity_) * 2U, 0.0f);
  in_l_.assign(static_cast<std::size_t>(block_size_), 0.0f);
  in_r_.assign(static_cast<std::size_t>(block_size_), 0.0f);
  out_l_.assign(static_cast<std::size_t>(block_size_), 0.0f);
  out_r_.assign(static_cast<std::size_t>(block_size_), 0.0f);
  stage_.assign(static_cast<std::size_t>(kMaxProcessFrames) * 2U, 0.0f);
  reset();
  valid_ = true;
}

void FirEqProcessor::reset() noexcept {
  if (!valid_) {
    return;
  }
  convolver_l_.reset();
  convolver_r_.reset();
  std::fill(in_ring_.begin(), in_ring_.end(), 0.0f);
  std::fill(out_ring_.begin(), out_ring_.end(), 0.0f);
  in_read_ = 0;
  in_write_ = 0;
  in_count_ = 0;
  out_read_ = 0;
  out_write_ = 0;
  out_count_ = 0;
}

void FirEqProcessor::set_kernel(const float* kernel) noexcept {
  if (!valid_ || kernel == nullptr) {
    return;
  }
  convolver_l_.set_ir(kernel);
  convolver_r_.set_ir(kernel);
}

void FirEqProcessor::process(float* interleaved, int frames, int channels) noexcept {
  if (!valid_ || interleaved == nullptr || frames <= 0 || channels < 2) {
    return;
  }
  // Decoders hand over whole packets (MP3 1152, FLAC/WAV 4096); process them
  // in bounded chunks so staging and ring capacities stay valid.
  int offset = 0;
  while (offset < frames) {
    const int count = std::min(frames - offset, kMaxProcessFrames);
    process_chunk(interleaved + static_cast<std::size_t>(offset) * 2U, count);
    offset += count;
  }
}

void FirEqProcessor::process_chunk(float* interleaved, int count) noexcept {
  // Stage the whole chunk first: processing is in-place, and the output
  // (which lags by one block) would otherwise overwrite input that the
  // block pipeline has not consumed yet.
  std::copy_n(interleaved, static_cast<std::size_t>(count) * 2U, stage_.begin());

  // Push input through the block pipeline and serve finished output as soon
  // as it exists. The input and output rings decouple production (one block
  // per block_size frames) from consumption (count frames per call), so
  // non-aligned chunk sizes neither drop nor duplicate audio, and the output
  // ring never has to hold more than one in-flight chunk.
  int pushed = 0;
  int served = 0;
  while (pushed < count) {
    const int space = in_capacity_ - in_count_;
    const int take = std::min(space, count - pushed);
    for (int i = 0; i < take; ++i) {
      const int pos = (in_write_ + i) % in_capacity_;
      in_ring_[static_cast<std::size_t>(pos) * 2U] =
          stage_[static_cast<std::size_t>(pushed + i) * 2U];
      in_ring_[static_cast<std::size_t>(pos) * 2U + 1U] =
          stage_[static_cast<std::size_t>(pushed + i) * 2U + 1U];
    }
    in_write_ = (in_write_ + take) % in_capacity_;
    in_count_ += take;
    pushed += take;

    // Drain complete blocks into the partitioned convolvers.
    while (in_count_ >= block_size_) {
      for (int i = 0; i < block_size_; ++i) {
        const int pos = (in_read_ + i) % in_capacity_;
        in_l_[static_cast<std::size_t>(i)] = in_ring_[static_cast<std::size_t>(pos) * 2U];
        in_r_[static_cast<std::size_t>(i)] = in_ring_[static_cast<std::size_t>(pos) * 2U + 1U];
      }
      in_read_ = (in_read_ + block_size_) % in_capacity_;
      in_count_ -= block_size_;
      convolver_l_.process_block(in_l_.data(), out_l_.data());
      convolver_r_.process_block(in_r_.data(), out_r_.data());
      for (int i = 0; i < block_size_; ++i) {
        const int pos = (out_write_ + i) % out_capacity_;
        out_ring_[static_cast<std::size_t>(pos) * 2U] = out_l_[static_cast<std::size_t>(i)];
        out_ring_[static_cast<std::size_t>(pos) * 2U + 1U] = out_r_[static_cast<std::size_t>(i)];
      }
      out_write_ = (out_write_ + block_size_) % out_capacity_;
      out_count_ += block_size_;
    }

    // Serve whatever finished output is available; the remainder (latency
    // pre-roll) is zero-filled below.
    const int give = std::min(count - served, out_count_);
    for (int i = 0; i < give; ++i) {
      const int pos = (out_read_ + i) % out_capacity_;
      interleaved[static_cast<std::size_t>(served + i) * 2U] =
          out_ring_[static_cast<std::size_t>(pos) * 2U];
      interleaved[static_cast<std::size_t>(served + i) * 2U + 1U] =
          out_ring_[static_cast<std::size_t>(pos) * 2U + 1U];
    }
    out_read_ = (out_read_ + give) % out_capacity_;
    out_count_ -= give;
    served += give;
  }

  for (int i = served; i < count; ++i) {
    interleaved[static_cast<std::size_t>(i) * 2U] = 0.0f;
    interleaved[static_cast<std::size_t>(i) * 2U + 1U] = 0.0f;
  }
}

bool DesignLinearPhaseEq(const PeqConfig& config, int sample_rate, std::size_t kernel_size,
                         std::vector<float>* kernel, std::string* error) {
  if (kernel == nullptr || sample_rate <= 0 || kernel_size < 16 || !IsPowerOfTwo(kernel_size)) {
    if (error != nullptr) {
      *error = "invalid FIR EQ design parameters";
    }
    return false;
  }

  Fft fft(kernel_size);
  if (!fft.valid()) {
    if (error != nullptr) {
      *error = "FFT plan failed";
    }
    return false;
  }

  // Sample the RBJ magnitude response of the same bands on the FFT grid.
  std::vector<std::complex<float>> spectrum(kernel_size);
  const std::size_t half = kernel_size / 2;
  for (std::size_t k = 0; k <= half; ++k) {
    const double f = static_cast<double>(k) * static_cast<double>(sample_rate) /
                     static_cast<double>(kernel_size);
    double db = config.preamp_db;
    for (const PeqBandConfig& band : config.bands) {
      if (!band.enabled || std::abs(band.gain_db) < 0.001) {
        continue;
      }
      db += BiquadMagnitudeDb(Peq::DesignBand(band, sample_rate), f, sample_rate);
    }
    const float magnitude = static_cast<float>(std::pow(10.0, db / 20.0));
    spectrum[k] = std::complex<float>(magnitude, 0.0f);
    if (k > 0 && k < half) {
      spectrum[kernel_size - k] = std::complex<float>(magnitude, 0.0f);
    }
  }

  // Inverse FFT (Fft applies the 1/N scale itself) -> real symmetric kernel;
  // rotate so the peak sits at the centre (linear phase with kernel_size/2
  // group delay).
  fft.transform(spectrum.data(), true);
  kernel->resize(kernel_size);
  const std::size_t centre = kernel_size / 2;
  for (std::size_t i = 0; i < kernel_size; ++i) {
    (*kernel)[i] = spectrum[(i + centre) % kernel_size].real();
  }
  return true;
}

}  // namespace dsp
