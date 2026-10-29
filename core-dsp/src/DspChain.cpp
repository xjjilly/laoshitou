// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/DspChain.h"

#include <algorithm>
#include <cstddef>

namespace dsp {

void DspChain::configure(int sample_rate, int channels, const DspChainConfig& config) {
  sample_rate_ = sample_rate;
  channels_ = channels;
  eq_enabled_ = config.eq_enabled;
  eq_linear_phase_ = config.eq_enabled && config.eq_linear_phase;
  // Above 96 kHz the partitioned FIR costs more CPU than the stability
  // budget allows: degrade silently to the minimum-phase IIR path.
  fir_degraded_ = false;
  if (eq_linear_phase_ && sample_rate > 96000) {
    eq_linear_phase_ = false;
    fir_degraded_ = true;
  }
  peq_.configure(sample_rate, channels, config.eq_enabled ? config.eq : PeqConfig{});

  // Linear-phase mode: design the FIR kernel from the same band config and
  // pre-allocate the partitioned convolvers (2048 taps, 512-frame blocks =>
  // ~35 ms latency at 44.1 kHz).
  if (eq_linear_phase_) {
    constexpr std::size_t kKernelSize = 2048;
    constexpr std::size_t kBlockSize = 512;
    std::string error;
    if (!DesignLinearPhaseEq(config.eq, sample_rate, kKernelSize, &fir_kernel_, &error)) {
      eq_linear_phase_ = false;
    } else {
      fir_eq_.configure(sample_rate, channels, kKernelSize, kBlockSize);
      if (fir_eq_.valid()) {
        fir_eq_.set_kernel(fir_kernel_.data());
      } else {
        eq_linear_phase_ = false;
      }
    }
  }

  multiband_.configure(sample_rate, channels, config.multiband);

  // Loudness is always configured so the UI can change the volume while
  // playing; at volume 1.0 the stage is a no-op.
  loudness_.configure(sample_rate, channels);
  loudness_.set_volume(config.loudness_enabled ? config.loudness_volume : 1.0);

  stereo_width_.store(std::clamp(config.stereo_width, 0.0, 1.5), std::memory_order_relaxed);

  crossfeed_enabled_ = config.crossfeed_enabled && config.crossfeed_width != 0.0;
  if (crossfeed_enabled_) {
    crossfeed_ =
        std::make_unique<Crossfeed>(sample_rate, config.crossfeed_preset, config.crossfeed_width);
    crossfeed_enabled_ = crossfeed_->valid();
  } else {
    crossfeed_.reset();
  }

  if (config.brir_enabled && !config.brir_path.empty()) {
    brir_.configure(sample_rate, channels, config.brir_path, config.brir_mix, config.brir_mode,
                    config.brir_block_size);
  } else {
    // Empty path clears activity without reallocating (BrirProcessor holds
    // atomics and is not copy-assignable).
    brir_.configure(sample_rate, channels, "", config.brir_mix, config.brir_mode,
                    config.brir_block_size);
  }

  limiter_enabled_ = config.limiter_enabled && !config.multiband.enabled;
  if (limiter_enabled_) {
    limiter_.configure(sample_rate, channels, config.limiter_threshold_db);
  } else {
    limiter_.reset();
  }

  reset();
}

void DspChain::reset() noexcept {
  peq_.reset();
  loudness_.reset();
  if (crossfeed_ != nullptr) {
    crossfeed_->reset();
  }
  brir_.reset();
  limiter_.reset();
  fir_eq_.reset();
  multiband_.reset();
}

void DspChain::set_loudness_volume(double volume) noexcept { loudness_.set_volume(volume); }

void DspChain::update_peq(const PeqConfig& config) noexcept {
  // A live edit can arrive after the stream started without EQ (the editor
  // was neutral at start): enable the stage so the new bands are audible.
  // An empty config is a no-op because Peq::active() stays false.
  eq_enabled_ = true;
  if (eq_linear_phase_ && fir_eq_.valid()) {
    // Redesign the kernel in place (fir_kernel_ keeps its allocation) and
    // hot-swap it; the next convolution block picks it up.
    std::string error;
    if (DesignLinearPhaseEq(config, sample_rate_, fir_kernel_.size(), &fir_kernel_, &error)) {
      fir_eq_.set_kernel(fir_kernel_.data());
    }
  } else {
    peq_.configure(sample_rate_, channels_, config);
  }
}

void DspChain::set_stereo_width(double width) noexcept {
  stereo_width_.store(std::clamp(width, 0.0, 1.5), std::memory_order_relaxed);
}

void DspChain::set_brir_mix(double mix) noexcept { brir_.set_mix(mix); }

void DspChain::set_multiband_enabled(bool enabled) noexcept { multiband_.set_enabled(enabled); }

void DspChain::set_multiband_threshold_db(int band, double db) noexcept {
  multiband_.set_threshold_db(band, db);
}

void DspChain::set_multiband_ratio(int band, double ratio) noexcept {
  multiband_.set_ratio(band, ratio);
}

void DspChain::set_multiband_crossovers(double low_hz, double high_hz) noexcept {
  multiband_.set_crossovers(low_hz, high_hz);
}

void DspChain::process(float* interleaved, int frames, int channels) noexcept {
  if (interleaved == nullptr || frames <= 0) {
    return;
  }
  if (eq_enabled_ && eq_linear_phase_ && fir_eq_.valid()) {
    fir_eq_.process(interleaved, frames, channels);
  } else if (eq_enabled_ && peq_.active()) {
    peq_.process(interleaved, frames, channels);
  }
  if (loudness_.active()) {
    loudness_.process(interleaved, frames, channels);
  }
  const double width = stereo_width_.load(std::memory_order_relaxed);
  if (width != 1.0 && channels >= 2) {
    for (int frame = 0; frame < frames; ++frame) {
      const std::size_t base = static_cast<std::size_t>(frame) * static_cast<std::size_t>(channels);
      const double left = interleaved[base];
      const double right = interleaved[base + 1];
      const double mid = 0.5 * (left + right);
      const double side = 0.5 * (left - right) * width;
      interleaved[base] = static_cast<float>(mid + side);
      interleaved[base + 1] = static_cast<float>(mid - side);
    }
  }
  if (crossfeed_enabled_ && crossfeed_ != nullptr) {
    crossfeed_->process(interleaved, frames, channels);
  }
  if (brir_.active()) {
    brir_.process(interleaved, frames, channels);
  }
  if (multiband_.active()) {
    multiband_.process(interleaved, frames, channels);
  } else if (limiter_enabled_ && limiter_.active()) {
    limiter_.process(interleaved, frames, channels);
  }
}

std::size_t DspChain::eq_band_count() const noexcept { return peq_.band_count(); }

bool DspChain::crossfeed_enabled() const noexcept { return crossfeed_enabled_; }

bool DspChain::limiter_enabled() const noexcept { return limiter_enabled_; }

bool DspChain::brir_active() const noexcept { return brir_.active(); }

}  // namespace dsp
