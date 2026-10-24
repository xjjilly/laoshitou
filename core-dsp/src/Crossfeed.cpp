// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Crossfeed.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>

extern "C" {
#include "bs2b.h"
}

namespace dsp {

struct Crossfeed::Impl {
  t_bs2bdp state = nullptr;
};

Crossfeed::Crossfeed(int sample_rate, CrossfeedPreset preset, double width)
    : impl_(std::make_unique<Impl>()) {
  impl_->state = bs2b_open();
  if (impl_->state == nullptr) {
    return;
  }

  uint32_t level = BS2B_DEFAULT_CLEVEL;
  switch (preset) {
    case CrossfeedPreset::kWeak:
      level = BS2B_DEFAULT_CLEVEL;
      break;
    case CrossfeedPreset::kMedium:
      level = BS2B_CMOY_CLEVEL;
      break;
    case CrossfeedPreset::kStrong:
      level = BS2B_JMEIER_CLEVEL;
      break;
  }

  bs2b_set_level(impl_->state, level);
  if (width >= 0.0) {
    const double clamped = std::clamp(width, 0.0, 1.0);
    const int feed = static_cast<int>(std::lround(10.0 + clamped * 85.0));
    const int cutoff = static_cast<int>(std::lround(700.0 - clamped * 50.0));
    bs2b_set_level_feed(impl_->state, feed);
    bs2b_set_level_fcut(impl_->state, cutoff);
  }
  const int rate = std::clamp(sample_rate, BS2B_MINSRATE, BS2B_MAXSRATE);
  bs2b_set_srate(impl_->state, static_cast<uint32_t>(rate));
}

Crossfeed::~Crossfeed() {
  if (impl_ != nullptr && impl_->state != nullptr) {
    bs2b_close(impl_->state);
    impl_->state = nullptr;
  }
}

Crossfeed::Crossfeed(Crossfeed&&) noexcept = default;

Crossfeed& Crossfeed::operator=(Crossfeed&&) noexcept = default;

bool Crossfeed::valid() const noexcept { return impl_ != nullptr && impl_->state != nullptr; }

void Crossfeed::process(float* interleaved, int frames, int channels) noexcept {
  if (impl_ == nullptr || impl_->state == nullptr || interleaved == nullptr || frames <= 0 ||
      channels != 2) {
    return;
  }
  bs2b_cross_feed_f(impl_->state, interleaved, frames);
}

void Crossfeed::reset() noexcept {
  if (impl_ != nullptr && impl_->state != nullptr) {
    bs2b_clear(impl_->state);
  }
}

}  // namespace dsp
