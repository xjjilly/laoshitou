// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "audio/OboeOutput.h"

#include <android/log.h>

#include <cstddef>
#include <cstring>
#include <vector>

#include "dsp/DspChain.h"

namespace audio {
namespace {

constexpr char kLogTag[] = "OboeOutput";

}  // namespace

OboeOutput::~OboeOutput() { stop(); }

bool OboeOutput::open(int requested_sample_rate, int channels) {
  stop();

  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Output);
  builder.setSharingMode(oboe::SharingMode::Exclusive);
  builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
  builder.setFormat(oboe::AudioFormat::Float);
  builder.setFormatConversionAllowed(false);
  builder.setChannelCount(channels);
  builder.setSampleRate(requested_sample_rate);
  builder.setUsage(oboe::Usage::Media);
  builder.setContentType(oboe::ContentType::Music);
  builder.setDataCallback(this);
  builder.setErrorCallback(this);

  const oboe::Result result = builder.openStream(stream_);
  if (result != oboe::Result::OK) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "openStream failed: %d",
                        static_cast<int>(result));
    stream_.reset();
    return false;
  }

  const oboe::AudioFormat format = stream_->getFormat();
  const int actual_channels = stream_->getChannelCount();
  const int actual_rate = stream_->getSampleRate();
  const oboe::SharingMode sharing = stream_->getSharingMode();

  __android_log_print(ANDROID_LOG_INFO, kLogTag, "opened: sharing=%s format=%d rate=%d channels=%d",
                      sharing == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
                      static_cast<int>(format), actual_rate, actual_channels);

  if (format != oboe::AudioFormat::Float || actual_channels != channels) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "unsupported stream: format=%d channels=%d",
                        static_cast<int>(format), actual_channels);
    stream_->close();
    stream_.reset();
    return false;
  }

  channels_.store(actual_channels, std::memory_order_relaxed);
  actual_sample_rate_.store(actual_rate, std::memory_order_relaxed);
  exclusive_.store(sharing == oboe::SharingMode::Exclusive, std::memory_order_relaxed);
  disconnected_.store(false, std::memory_order_relaxed);
  silence_expected_.store(false, std::memory_order_relaxed);
  paused_.store(false, std::memory_order_relaxed);
  current_gain_ = 0.0f;
  return true;
}

void OboeOutput::set_ring(dsp::AudioRingBuffer* ring) noexcept {
  ring_.store(ring, std::memory_order_release);
}

void OboeOutput::set_dsp_chain(dsp::DspChain* chain) noexcept {
  dsp_chain_.store(chain, std::memory_order_release);
}

bool OboeOutput::start() {
  if (!stream_ || ring_.load(std::memory_order_acquire) == nullptr) {
    return false;
  }
  current_gain_ = 0.0f;
  const oboe::Result result = stream_->requestStart();
  if (result != oboe::Result::OK) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "requestStart failed: %d",
                        static_cast<int>(result));
    return false;
  }
  return true;
}

void OboeOutput::stop() {
  dsp_chain_.store(nullptr, std::memory_order_release);
  ring_.store(nullptr, std::memory_order_release);
  if (stream_) {
    stream_->requestStop();
    stream_->close();
    stream_.reset();
  }
  channels_.store(0, std::memory_order_relaxed);
  exclusive_.store(false, std::memory_order_relaxed);
  disconnected_.store(false, std::memory_order_relaxed);
  silence_expected_.store(false, std::memory_order_relaxed);
  paused_.store(false, std::memory_order_relaxed);
}

void OboeOutput::set_target_gain(float gain) noexcept {
  target_gain_.store(gain, std::memory_order_relaxed);
}

void OboeOutput::set_paused(bool paused) noexcept {
  paused_.store(paused, std::memory_order_relaxed);
}

void OboeOutput::set_silence_expected(bool expected) noexcept {
  silence_expected_.store(expected, std::memory_order_relaxed);
}

bool OboeOutput::is_open() const noexcept { return stream_ != nullptr; }

bool OboeOutput::is_disconnected() const noexcept {
  return disconnected_.load(std::memory_order_relaxed);
}

bool OboeOutput::is_exclusive() const noexcept {
  return exclusive_.load(std::memory_order_relaxed);
}

int OboeOutput::actual_sample_rate() const noexcept {
  return actual_sample_rate_.load(std::memory_order_relaxed);
}

int OboeOutput::actual_channels() const noexcept {
  return channels_.load(std::memory_order_relaxed);
}

int32_t OboeOutput::xrun_count() const noexcept {
  if (!stream_) {
    return 0;
  }
  const oboe::ResultWithValue<int32_t> result = stream_->getXRunCount();
  return result.error() == oboe::Result::OK ? result.value() : 0;
}

int64_t OboeOutput::underrun_count() const noexcept {
  return underruns_.load(std::memory_order_relaxed);
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream* /*stream*/, void* audio_data,
                                                  int32_t num_frames) {
  const int channels = channels_.load(std::memory_order_relaxed);
  if (audio_data == nullptr || channels <= 0 || num_frames <= 0) {
    return oboe::DataCallbackResult::Continue;
  }

  const auto frames = static_cast<std::size_t>(num_frames);
  const auto channel_count = static_cast<std::size_t>(channels);
  auto* out = static_cast<float*>(audio_data);

  const bool paused = paused_.load(std::memory_order_relaxed);
  if (paused && current_gain_ <= 1.0e-4f) {
    std::memset(out, 0, frames * channel_count * sizeof(float));
    return oboe::DataCallbackResult::Continue;
  }

  dsp::AudioRingBuffer* ring = ring_.load(std::memory_order_acquire);
  if (ring == nullptr) {
    std::memset(out, 0, frames * channel_count * sizeof(float));
    return oboe::DataCallbackResult::Continue;
  }

  const std::size_t frames_read = ring->read(out, frames);
  if (frames_read < frames) {
    const std::size_t missing_frames = frames - frames_read;
    std::memset(out + frames_read * channel_count, 0,
                missing_frames * channel_count * sizeof(float));
    if (!silence_expected_.load(std::memory_order_relaxed)) {
      underruns_.fetch_add(static_cast<int64_t>(missing_frames), std::memory_order_relaxed);
    }
  }

  dsp::DspChain* chain = dsp_chain_.load(std::memory_order_acquire);
  if (chain != nullptr) {
    chain->process(out, num_frames, channels);
  }

  const float target = paused ? 0.0f : target_gain_.load(std::memory_order_relaxed);
  const float step = (target - current_gain_) / static_cast<float>(frames);
  float gain = current_gain_;
  for (std::size_t frame = 0; frame < frames; ++frame) {
    gain += step;
    const std::size_t base = frame * channel_count;
    for (std::size_t channel = 0; channel < channel_count; ++channel) {
      out[base + channel] *= gain;
    }
  }
  current_gain_ = gain;

  return oboe::DataCallbackResult::Continue;
}

void OboeOutput::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
  disconnected_.store(true, std::memory_order_relaxed);
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "stream closed: %d", static_cast<int>(error));
}

}  // namespace audio
