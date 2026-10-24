// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/DspChain.h"

#include <algorithm>
#include <cstddef>

namespace dsp {

void DspChain::configure(int sample_rate, int channels, const DspChainConfig& config) {
  eq_enabled_ = config.eq_enabled;
  peq_.configure(sample_rate, channels, config.eq_enabled ? config.eq : PeqConfig{});

  // Loudness is always configured so the UI can change the volume while
  // playing; at volume 1.0 the stage is a no-op.
  loudness_.configure(sample_rate, channels);
  loudness_.set_volume(config.loudness_enabled ? config.loudness_volume : 1.0);

  stereo_width_ = std::clamp(config.stereo_width, 0.0, 1.5);

  crossfeed_enabled_ = config.crossfeed_enabled && config.crossfeed_width != 0.0;
  if (crossfeed_enabled_) {
    crossfeed_ =
        std::make_unique<Crossfeed>(sample_rate, config.crossfeed_preset, config.crossfeed_width);
    crossfeed_enabled_ = crossfeed_->valid();
  } else {
    crossfeed_.reset();
  }

  if (config.brir_enabled && !config.brir_path.empty()) {
    brir_.configure(sample_rate, channels, config.brir_path, config.brir_mix);
  } else {
    brir_ = BrirProcessor{};
  }

  limiter_enabled_ = config.limiter_enabled;
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
}

void DspChain::set_loudness_volume(double volume) noexcept { loudness_.set_volume(volume); }

void DspChain::process(float* interleaved, int frames, int channels) noexcept {
  if (interleaved == nullptr || frames <= 0) {
    return;
  }
  if (eq_enabled_ && peq_.active()) {
    peq_.process(interleaved, frames, channels);
  }
  if (loudness_.active()) {
    loudness_.process(interleaved, frames, channels);
  }
  if (stereo_width_ != 1.0 && channels >= 2) {
    for (int frame = 0; frame < frames; ++frame) {
      const std::size_t base = static_cast<std::size_t>(frame) * static_cast<std::size_t>(channels);
      const double left = interleaved[base];
      const double right = interleaved[base + 1];
      const double mid = 0.5 * (left + right);
      const double side = 0.5 * (left - right) * stereo_width_;
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
  if (limiter_enabled_ && limiter_.active()) {
    limiter_.process(interleaved, frames, channels);
  }
}

std::size_t DspChain::eq_band_count() const noexcept { return peq_.band_count(); }

bool DspChain::crossfeed_enabled() const noexcept { return crossfeed_enabled_; }

bool DspChain::limiter_enabled() const noexcept { return limiter_enabled_; }

bool DspChain::brir_active() const noexcept { return brir_.active(); }

}  // namespace dsp
