// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/Resampler.h"

#include <cmath>
#include <limits>
#include <memory>
#include <vector>

extern "C" {
#include <samplerate.h>
}

namespace dsp {

struct Resampler::Impl {
  SRC_STATE* state = nullptr;
  int channels = 0;
  double ratio = 1.0;
};

Resampler::Resampler(int channels, double ratio, ResamplerQuality quality)
    : impl_(std::make_unique<Impl>()) {
  if (channels <= 0 || ratio <= 0.0) {
    return;
  }

  int converter = SRC_SINC_BEST_QUALITY;
  switch (quality) {
    case ResamplerQuality::kBest:
      converter = SRC_SINC_BEST_QUALITY;
      break;
    case ResamplerQuality::kMedium:
      converter = SRC_SINC_MEDIUM_QUALITY;
      break;
    case ResamplerQuality::kFastest:
      converter = SRC_SINC_FASTEST;
      break;
    case ResamplerQuality::kAuto:
      // SRC_SINC_BEST_QUALITY is very expensive for large downsampling ratios
      // (e.g. DSD 352.8 kHz -> 48/192 kHz). Use the medium converter there;
      // it keeps up on mobile, while 44.1/48 kHz content still gets BEST.
      converter = ratio < 0.75 ? SRC_SINC_MEDIUM_QUALITY : SRC_SINC_BEST_QUALITY;
      break;
  }
  int error = 0;
  impl_->state = src_new(converter, channels, &error);
  if (impl_->state == nullptr) {
    return;
  }
  impl_->channels = channels;
  impl_->ratio = ratio;
}

Resampler::~Resampler() {
  if (impl_ != nullptr && impl_->state != nullptr) {
    src_delete(impl_->state);
    impl_->state = nullptr;
  }
}

Resampler::Resampler(Resampler&&) noexcept = default;

Resampler& Resampler::operator=(Resampler&&) noexcept = default;

bool Resampler::valid() const noexcept { return impl_ != nullptr && impl_->state != nullptr; }

double Resampler::ratio() const noexcept { return impl_ != nullptr ? impl_->ratio : 0.0; }

bool Resampler::process(const float* input, std::size_t input_frames, std::vector<float>* out) {
  if (!valid() || input == nullptr || out == nullptr) {
    return false;
  }
  if (input_frames == 0) {
    out->clear();
    return true;
  }
  if (input_frames > static_cast<std::size_t>(std::numeric_limits<long>::max())) {
    return false;
  }

  const auto channels = static_cast<std::size_t>(impl_->channels);
  const double expected = static_cast<double>(input_frames) * impl_->ratio;
  const auto capacity = static_cast<long>(std::ceil(expected)) + 128;
  out->assign(static_cast<std::size_t>(capacity) * channels, 0.0f);

  SRC_DATA data{};
  data.data_in = const_cast<float*>(input);
  data.input_frames = static_cast<long>(input_frames);
  data.data_out = out->data();
  data.output_frames = capacity;
  data.src_ratio = impl_->ratio;
  data.end_of_input = 0;

  const int error = src_process(impl_->state, &data);
  if (error != 0) {
    out->clear();
    return false;
  }
  if (data.output_frames_gen < 0) {
    out->clear();
    return false;
  }

  out->resize(static_cast<std::size_t>(data.output_frames_gen) * channels);
  return true;
}

bool Resampler::flush(std::vector<float>* out) {
  if (!valid() || out == nullptr) {
    return false;
  }

  const auto channels = static_cast<std::size_t>(impl_->channels);
  std::vector<float> result;
  constexpr long kBlockFrames = 4096;

  for (int iteration = 0; iteration < 100000; ++iteration) {
    std::vector<float> block(static_cast<std::size_t>(kBlockFrames) * channels, 0.0f);
    float dummy = 0.0f;
    SRC_DATA data{};
    data.data_in = &dummy;
    data.input_frames = 0;
    data.data_out = block.data();
    data.output_frames = kBlockFrames;
    data.src_ratio = impl_->ratio;
    data.end_of_input = 1;

    const int error = src_process(impl_->state, &data);
    if (error != 0) {
      return false;
    }
    if (data.output_frames_gen <= 0) {
      break;
    }
    result.insert(result.end(), block.begin(),
                  block.begin() + static_cast<std::size_t>(data.output_frames_gen) * channels);
  }

  *out = std::move(result);
  return true;
}

}  // namespace dsp
