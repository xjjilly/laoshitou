// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "audio/AudioEngine.h"

#include <android/log.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <utility>
#include <vector>

#include "dsp/AutoEqProfile.h"

namespace audio {
namespace {

constexpr char kLogTag[] = "AudioEngine";
constexpr double kTwoPi = 6.283185307179586476925286766559;
constexpr double kToneHz = 440.0;
constexpr float kAmplitude = 0.1f;
constexpr int kChannels = 2;
constexpr std::size_t kBlockFrames = 480;

// 同采样率族内、低于目标率的候选采样率（降序），用于目标率拿不到独占时
// 逐级降档重试：44.1 族与 48 族各三档。
std::vector<int> exclusive_fallback_rates(int source_rate, int policy_rate) {
  const bool family_44 = source_rate % 8000 != 0;
  const std::vector<int> family =
      family_44 ? std::vector<int>{176400, 88200, 44100} : std::vector<int>{192000, 96000, 48000};
  std::vector<int> out;
  for (int rate : family) {
    if (rate < policy_rate) {
      out.push_back(rate);
    }
  }
  return out;
}

}  // namespace

AudioEngine::~AudioEngine() { stop(); }

bool AudioEngine::start_test_tone(int requested_sample_rate) {
  stop();
  test_tone_ = true;
  options_ = PlaybackOptions{};
  options_.mode = dsp::OutputMode::kDirect;
  requested_sample_rate_ = requested_sample_rate;

  try {
    output_ = std::make_unique<OboeOutput>();
    if (!output_->open(requested_sample_rate, kChannels)) {
      output_.reset();
      return false;
    }

    const int actual_rate = output_->actual_sample_rate();
    if (actual_rate <= 0) {
      output_->stop();
      output_.reset();
      return false;
    }

    ring_ = std::make_unique<dsp::AudioRingBuffer>(static_cast<std::size_t>(actual_rate) * 2U,
                                                   kChannels);
    output_->set_ring(ring_.get());
    output_->set_dsp_chain(nullptr);
    output_->set_silence_expected(false);
    output_->set_paused(false);
    output_->set_target_gain(1.0f);
    source_eof_.store(false, std::memory_order_release);
    paused_ = false;
    gain_ = 1.0f;
    processing_mode_ = false;
    eq_loaded_ = false;
    crossfeed_enabled_ = false;
    limiter_enabled_ = false;

    // Prefill 200 ms so the first callback does not underrun.
    double phase = 0.0;
    fill_tone(actual_rate, static_cast<std::size_t>(actual_rate) / 5U, &phase);

    if (!output_->start()) {
      output_->stop();
      output_.reset();
      ring_.reset();
      return false;
    }

    running_.store(true, std::memory_order_release);
    producer_ = std::thread(&AudioEngine::producer_loop, this, actual_rate, phase);
    actual_sample_rate_ = actual_rate;
    source_sample_rate_ = actual_rate;
    target_sample_rate_ = actual_rate;
    cross_family_fallback_ = false;
    non_integer_ratio_ = false;
    device_limited_ = false;
    return true;
  } catch (...) {
    stop();
    return false;
  }
}

bool AudioEngine::start_file(const std::string& path, const PlaybackOptions& options) {
  stop();
  options_ = options;
  test_tone_ = false;

  try {
    decoder_ = std::make_unique<FfmpegDecoder>();
    if (!decoder_->open(path)) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag, "open failed: %s",
                          decoder_->last_error().c_str());
      decoder_.reset();
      return false;
    }

    const int source_rate = decoder_->sample_rate();
    if (source_rate <= 0) {
      decoder_.reset();
      return false;
    }

    const dsp::RatePlan plan = dsp::MakeRatePlan(source_rate, options.mode, options.device);
    const int policy_rate = plan.target_rate > 0 ? plan.target_rate : source_rate;
    int requested_rate = policy_rate;
    if (options.forced_sample_rate >= 8000 && options.forced_sample_rate <= 768000) {
      requested_rate = options.forced_sample_rate;
    } else if (options.usb_dac_detected && options.auto_usb_192k && source_rate > 0 &&
               source_rate <= 192000 && source_rate % 8000 == 0) {
      // 48 kHz-family PCM fed to a USB DAC: prefer its 192 kHz path when the
      // platform reports only 48 kHz. 44.1-family content is left to the
      // policy (the tested dongle has no 88.2/176.4 support). Manual output
      // rate overrides always win.
      requested_rate = 192000;
    }

    output_ = std::make_unique<OboeOutput>();
    bool opened = output_->open(requested_rate, kChannels);
    if (opened && !output_->is_exclusive() && options.forced_sample_rate <= 0) {
      // 优先保住独占（处理与直通模式都适用）：目标采样率只能以共享模式打开时
      // （很多 DAC 的 MMAP 不支持 88.2/176.4k），在同族内逐级降档重试，拿到
      // 独占为止。共享模式同样无法 bit-perfect（系统混音器参与），因此直通
      // 模式下用同族整数降档 + 独占也优于共享混音。全族都拿不到独占时回到
      // 最初的请求率，保持原有行为。
      const int first_rate = requested_rate;
      for (int candidate : exclusive_fallback_rates(source_rate, policy_rate)) {
        output_->stop();
        if (!output_->open(candidate, kChannels)) {
          output_->stop();
          opened = output_->open(first_rate, kChannels);
          break;
        }
        if (output_->is_exclusive()) {
          requested_rate = candidate;
          break;
        }
      }
      if (opened && !output_->is_exclusive()) {
        output_->stop();
        opened = output_->open(first_rate, kChannels);
      }
    }
    if (!opened && requested_rate != source_rate) {
      // The policy target may be supported by another output route but not by
      // the current one. A forced rate that the USB device rejects can make it
      // re-enumerate; give the route a moment to settle before retrying.
      if (options.forced_sample_rate > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
      }
      requested_rate = source_rate;
      opened = output_->open(requested_rate, kChannels);
    }
    if (!opened && options.device.native_rate > 0 && requested_rate != options.device.native_rate) {
      requested_rate = options.device.native_rate;
      opened = output_->open(requested_rate, kChannels);
    }
    if (!opened) {
      output_.reset();
      decoder_.reset();
      return false;
    }

    const int actual_rate = output_->actual_sample_rate();
    if (actual_rate <= 0) {
      output_->stop();
      output_.reset();
      decoder_.reset();
      return false;
    }

    requested_sample_rate_ = requested_rate;
    target_sample_rate_ = requested_rate;
    update_rate_flags(source_rate, actual_rate);

    ring_ = std::make_unique<dsp::AudioRingBuffer>(static_cast<std::size_t>(actual_rate) * 2U,
                                                   kChannels);
    output_->set_ring(ring_.get());
    output_->set_silence_expected(false);
    output_->set_paused(false);
    output_->set_target_gain(1.0f);
    source_eof_.store(false, std::memory_order_release);
    paused_ = false;
    gain_ = 1.0f;

    if (!configure_resampler(source_rate, actual_rate, options.resampler_quality)) {
      output_->stop();
      output_.reset();
      decoder_.reset();
      ring_.reset();
      return false;
    }

    source_sample_rate_ = source_rate;
    actual_sample_rate_ = actual_rate;
    configure_processing(actual_rate, options);

    // Let the supplier thread fill part of the ring before starting the
    // stream, so playback does not begin with an underrun.
    running_.store(true, std::memory_order_release);
    producer_ = std::thread(&AudioEngine::file_producer_loop, this);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    if (!output_->start()) {
      stop();
      return false;
    }
    return true;
  } catch (...) {
    stop();
    return false;
  }
}

void AudioEngine::stop() {
  running_.store(false, std::memory_order_release);
  if (producer_.joinable()) {
    producer_.join();
  }

  if (output_) {
    // Let the callback ramp down before closing the stream.
    output_->set_dsp_chain(nullptr);
    output_->set_target_gain(0.0f);
    std::this_thread::sleep_for(std::chrono::milliseconds(30));
    output_->stop();
  }

  output_.reset();
  ring_.reset();
  decoder_.reset();
  resampler_.reset();
  dsp_chain_.reset();
  source_eof_.store(false, std::memory_order_release);
  paused_ = false;
  gain_ = 1.0f;
  options_ = PlaybackOptions{};
  test_tone_ = false;
  requested_sample_rate_ = 0;
  actual_sample_rate_ = 0;
  source_sample_rate_ = 0;
  target_sample_rate_ = 0;
  cross_family_fallback_ = false;
  non_integer_ratio_ = false;
  device_limited_ = false;
  processing_mode_ = false;
  eq_loaded_ = false;
  crossfeed_enabled_ = false;
  limiter_enabled_ = false;
}

void AudioEngine::set_paused(bool paused) noexcept {
  paused_ = paused;
  if (output_) {
    output_->set_paused(paused);
  }
}

void AudioEngine::set_gain(float gain) noexcept {
  gain_ = gain;
  if (output_) {
    output_->set_target_gain(gain);
  }
}

void AudioEngine::set_loudness_volume(double volume) noexcept {
  dsp_chain_.set_loudness_volume(volume);
}

bool AudioEngine::update_eq_live(const dsp::PeqConfig& config) noexcept {
  if (!processing_mode_) {
    return false;
  }
  dsp_chain_.update_peq(config);
  eq_loaded_ = true;
  return true;
}

bool AudioEngine::eq_linear_phase_active() const noexcept {
  return processing_mode_ && dsp_chain_.eq_linear_phase_active();
}

bool AudioEngine::fir_degraded() const noexcept {
  return processing_mode_ && dsp_chain_.fir_degraded();
}

bool AudioEngine::is_paused() const noexcept { return paused_; }

bool AudioEngine::is_running() const noexcept {
  if (!running_.load(std::memory_order_acquire) || output_ == nullptr) {
    return false;
  }
  // While the platform has closed the stream, keep reporting running so the
  // foreground service and the UI wait for recovery instead of stopping.
  return output_->is_open() || output_->is_disconnected();
}

bool AudioEngine::needs_output_recovery() const noexcept {
  return running_.load(std::memory_order_acquire) && output_ != nullptr &&
         output_->is_disconnected();
}

bool AudioEngine::recover_output() {
  try {
    return recover_output_impl();
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "reopen failed: exception");
    stop();
    return false;
  }
}

bool AudioEngine::recover_output_impl() {
  if (!needs_output_recovery()) {
    return true;
  }

  const int old_actual = actual_sample_rate_;
  const int source_rate = source_sample_rate_;
  const int old_requested = requested_sample_rate_;
  const bool was_paused = paused_;

  __android_log_print(ANDROID_LOG_WARN, kLogTag,
                      "output disconnected; reopening (requested=%d actual=%d)", old_requested,
                      old_actual);

  // Prefer the original request and the previous actual rate, then the source
  // rate and the platform native rate. Opening the same rate keeps the
  // producer, ring and DSP state.
  const std::array<int, 4> candidates{old_requested, old_actual, source_rate,
                                      options_.device.native_rate};
  std::array<int, 4> tried{};
  std::size_t tried_count = 0;
  int reopened_request = 0;
  bool opened = false;
  for (const int rate : candidates) {
    if (rate <= 0) {
      continue;
    }
    bool duplicate = false;
    for (std::size_t i = 0; i < tried_count; ++i) {
      if (tried[i] == rate) {
        duplicate = true;
        break;
      }
    }
    if (duplicate) {
      continue;
    }
    tried[tried_count++] = rate;
    if (output_->open(rate, kChannels)) {
      reopened_request = rate;
      opened = true;
      break;
    }
    // A device that just re-enumerated (USB) may need a moment before it
    // accepts a stream again.
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }

  if (!opened) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "reopen failed: no usable output rate");
    running_.store(false, std::memory_order_release);
    return false;
  }

  const int new_actual = output_->actual_sample_rate();
  if (new_actual <= 0) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "reopen failed: invalid actual rate");
    running_.store(false, std::memory_order_release);
    return false;
  }

  requested_sample_rate_ = reopened_request;
  target_sample_rate_ = reopened_request;
  update_rate_flags(source_rate, new_actual);

  // Use the public EOF view (source finished and ring drained), not the raw
  // producer flag, so a reconnect near the end of a track keeps the tail.
  const bool eof_now = source_eof();
  if (new_actual == old_actual && ring_ != nullptr && !eof_now) {
    // Same rate: the supplier keeps filling the existing ring while the stream
    // is closed, so playback resumes at the same position.
    output_->set_ring(ring_.get());
    output_->set_dsp_chain(processing_mode_ ? &dsp_chain_ : nullptr);
    output_->set_paused(was_paused);
    output_->set_target_gain(gain_);
    output_->set_silence_expected(false);
    if (!output_->start()) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "reopen failed: requestStart after fast path");
      running_.store(false, std::memory_order_release);
      return false;
    }
    actual_sample_rate_ = new_actual;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "output reopened: requested=%d actual=%d (ring preserved)",
                        reopened_request, new_actual);
    return true;
  }

  // The new route runs at a different rate, or the source ended while the
  // stream was down. Rebuild every rate-dependent stage.
  running_.store(false, std::memory_order_release);
  if (producer_.joinable()) {
    producer_.join();
  }
  if (!test_tone_ && eof_now) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "source ended during reconnect; not restarting");
    return false;
  }

  ring_ =
      std::make_unique<dsp::AudioRingBuffer>(static_cast<std::size_t>(new_actual) * 2U, kChannels);
  if (!configure_resampler(source_rate, new_actual, options_.resampler_quality)) {
    output_->stop();
    output_.reset();
    ring_.reset();
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "reopen failed: cannot rebuild resampler for %d Hz", new_actual);
    return false;
  }

  source_eof_.store(false, std::memory_order_release);
  actual_sample_rate_ = new_actual;
  configure_processing(new_actual, options_);

  output_->set_ring(ring_.get());
  output_->set_dsp_chain(processing_mode_ ? &dsp_chain_ : nullptr);
  output_->set_paused(was_paused);
  output_->set_target_gain(gain_);
  output_->set_silence_expected(false);

  running_.store(true, std::memory_order_release);
  if (test_tone_) {
    producer_ = std::thread(&AudioEngine::producer_loop, this, new_actual, 0.0);
  } else {
    producer_ = std::thread(&AudioEngine::file_producer_loop, this);
  }
  std::this_thread::sleep_for(std::chrono::milliseconds(100));

  if (!output_->start()) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "reopen failed: requestStart after rebuild");
    running_.store(false, std::memory_order_release);
    return false;
  }

  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "output rebuilt: requested=%d actual=%d (pipeline reconfigured)",
                      reopened_request, new_actual);
  return true;
}

int AudioEngine::actual_sample_rate() const noexcept { return actual_sample_rate_; }

int AudioEngine::source_sample_rate() const noexcept { return source_sample_rate_; }

int AudioEngine::target_sample_rate() const noexcept { return target_sample_rate_; }

bool AudioEngine::cross_family_fallback() const noexcept { return cross_family_fallback_; }

bool AudioEngine::non_integer_ratio() const noexcept { return non_integer_ratio_; }

bool AudioEngine::device_limited() const noexcept { return device_limited_; }

bool AudioEngine::is_exclusive() const noexcept {
  return output_ != nullptr && output_->is_exclusive();
}

bool AudioEngine::source_eof() const noexcept {
  if (!source_eof_.load(std::memory_order_acquire)) {
    return false;
  }
  // The producer can reach the end of the file while up to two seconds of
  // audio are still queued. Only report EOF once the ring has drained, so
  // playlist auto-advance does not cut off the tail of the track.
  if (ring_ == nullptr) {
    return true;
  }
  return ring_->available_read_frames() == 0;
}

bool AudioEngine::processing_mode() const noexcept { return processing_mode_; }

int AudioEngine::eq_band_count() const noexcept {
  return (processing_mode_ && eq_loaded_) ? static_cast<int>(dsp_chain_.eq_band_count()) : 0;
}

bool AudioEngine::crossfeed_enabled() const noexcept { return crossfeed_enabled_; }

bool AudioEngine::limiter_enabled() const noexcept { return limiter_enabled_; }

bool AudioEngine::brir_active() const noexcept {
  return processing_mode_ && dsp_chain_.brir_active();
}

int64_t AudioEngine::underruns() const noexcept {
  return output_ != nullptr ? output_->underrun_count() : 0;
}

bool AudioEngine::configure_resampler(int source_rate, int actual_rate, int quality_option) {
  resampler_.reset();
  if (source_rate <= 0 || actual_rate <= 0) {
    return false;
  }
  if (source_rate == actual_rate) {
    return true;
  }

  dsp::ResamplerQuality quality = dsp::ResamplerQuality::kAuto;
  if (quality_option == 0) {
    // Auto: BEST is affordable for PCM sources up to 192 kHz (including
    // 192k -> 48k, measured with 0 underruns on the test phone). DSD
    // (352.8 kHz and above) needs MEDIUM to keep up in real time.
    quality = source_rate > 192000 ? dsp::ResamplerQuality::kMedium : dsp::ResamplerQuality::kBest;
  } else {
    switch (quality_option) {
      case 1:
        quality = dsp::ResamplerQuality::kBest;
        break;
      case 2:
        quality = dsp::ResamplerQuality::kMedium;
        break;
      case 3:
        quality = dsp::ResamplerQuality::kFastest;
        break;
      default:
        break;
    }
  }

  resampler_ = std::make_unique<dsp::Resampler>(
      kChannels, static_cast<double>(actual_rate) / static_cast<double>(source_rate), quality);
  return resampler_->valid();
}

void AudioEngine::configure_processing(int actual_rate, const PlaybackOptions& options) {
  processing_mode_ = (options.mode == dsp::OutputMode::kProcessing);
  eq_loaded_ = false;
  crossfeed_enabled_ = false;
  limiter_enabled_ = false;

  if (!processing_mode_) {
    output_->set_dsp_chain(nullptr);
    return;
  }

  dsp::DspChainConfig chain_config;
  chain_config.crossfeed_enabled = options.crossfeed_enabled;
  chain_config.crossfeed_preset = options.crossfeed_preset;
  chain_config.crossfeed_width = options.crossfeed_width;
  chain_config.stereo_width = options.stereo_width;
  chain_config.loudness_enabled = options.loudness_enabled;
  chain_config.loudness_volume = options.loudness_volume;
  chain_config.limiter_enabled = options.limiter_enabled;
  chain_config.limiter_threshold_db = options.limiter_threshold_db;
  chain_config.brir_enabled = options.brir_enabled;
  chain_config.brir_path = options.brir_path;
  chain_config.brir_mix = options.brir_mix;
  chain_config.eq_linear_phase = options.eq_linear_phase;
  chain_config.multiband = options.multiband;

  if (!options.eq_profile_path.empty()) {
    dsp::AutoEqProfile profile;
    std::string error;
    dsp::AutoEqOptions eq_options;
    eq_options.order = options.eq_cascade ? 2 : 1;
    eq_options.use_slope = options.eq_use_slope;
    eq_options.slope = options.eq_slope;
    if (dsp::LoadAutoEqProfile(options.eq_profile_path, &profile, &error, eq_options)) {
      chain_config.eq_enabled = true;
      chain_config.eq.preamp_db = profile.preamp_db;
      chain_config.eq.bands = std::move(profile.bands);
      eq_loaded_ = true;
    } else {
      __android_log_print(ANDROID_LOG_WARN, kLogTag, "EQ load failed: %s", error.c_str());
    }
  }

  const bool brir_requested = chain_config.brir_enabled && !chain_config.brir_path.empty();
  dsp_chain_.configure(actual_rate, kChannels, chain_config);
  output_->set_dsp_chain(&dsp_chain_);
  crossfeed_enabled_ = dsp_chain_.crossfeed_enabled();
  limiter_enabled_ = dsp_chain_.limiter_enabled();
  if (brir_requested) {
    __android_log_print(dsp_chain_.brir_active() ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, kLogTag,
                        "BRIR %s: %s (mix=%.2f)", dsp_chain_.brir_active() ? "active" : "failed",
                        chain_config.brir_path.c_str(), options.brir_mix);
  }
}

void AudioEngine::update_rate_flags(int source_rate, int actual_rate) noexcept {
  cross_family_fallback_ =
      dsp::ClassifyRateFamily(source_rate) != dsp::ClassifyRateFamily(actual_rate);
  non_integer_ratio_ = source_rate != actual_rate && !dsp::IsIntegerRatio(source_rate, actual_rate);
  device_limited_ = actual_rate < source_rate;
}

void AudioEngine::fill_tone(int sample_rate, std::size_t frames, double* phase) {
  if (sample_rate <= 0 || phase == nullptr || !ring_) {
    return;
  }

  const double increment = kTwoPi * kToneHz / static_cast<double>(sample_rate);
  std::vector<float> block(kBlockFrames * static_cast<std::size_t>(kChannels));

  std::size_t remaining = frames;
  while (remaining > 0) {
    const std::size_t count = std::min(remaining, kBlockFrames);
    for (std::size_t i = 0; i < count; ++i) {
      const float sample = kAmplitude * static_cast<float>(std::sin(*phase));
      const std::size_t base = i * static_cast<std::size_t>(kChannels);
      block[base] = sample;
      block[base + 1] = sample;
      *phase += increment;
      if (*phase >= kTwoPi) {
        *phase -= kTwoPi;
      }
    }

    std::size_t offset = 0;
    while (offset < count) {
      const std::size_t written =
          ring_->write(&block[offset * static_cast<std::size_t>(kChannels)], count - offset);
      if (written == 0) {
        return;
      }
      offset += written;
    }
    remaining -= count;
  }
}

void AudioEngine::producer_loop(int sample_rate, double phase) {
  if (sample_rate <= 0) {
    return;
  }

  const double increment = kTwoPi * kToneHz / static_cast<double>(sample_rate);
  const int block_frames = std::max(sample_rate / 100, 1);
  std::vector<float> block(static_cast<std::size_t>(block_frames) *
                           static_cast<std::size_t>(kChannels));

  while (running_.load(std::memory_order_relaxed)) {
    for (int i = 0; i < block_frames; ++i) {
      const float sample = kAmplitude * static_cast<float>(std::sin(phase));
      const std::size_t base = static_cast<std::size_t>(i) * static_cast<std::size_t>(kChannels);
      block[base] = sample;
      block[base + 1] = sample;
      phase += increment;
      if (phase >= kTwoPi) {
        phase -= kTwoPi;
      }
    }

    std::size_t offset = 0;
    const auto frames_to_write = static_cast<std::size_t>(block_frames);
    while (offset < frames_to_write && running_.load(std::memory_order_relaxed)) {
      const std::size_t written = ring_->write(&block[offset * static_cast<std::size_t>(kChannels)],
                                               frames_to_write - offset);
      if (written == 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
      } else {
        offset += written;
      }
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
}

void AudioEngine::file_producer_loop() {
  std::vector<float> decoded;
  std::vector<float> resampled;
  int frames = 0;

  while (running_.load(std::memory_order_relaxed) && decoder_ != nullptr) {
    if (!decoder_->read(&decoded, &frames)) {
      if (!decoder_->last_error().empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "decode failed: %s",
                            decoder_->last_error().c_str());
      }
      break;
    }
    if (frames <= 0) {
      break;
    }

    const float* data = decoded.data();
    auto frame_count = static_cast<std::size_t>(frames);

    if (resampler_) {
      if (!resampler_->process(decoded.data(), frame_count, &resampled)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "resampler process failed");
        break;
      }
      data = resampled.data();
      frame_count = resampled.size() / static_cast<std::size_t>(kChannels);
    }

    if (frame_count > 0 && !write_to_ring(data, frame_count)) {
      break;
    }
  }

  if (resampler_) {
    std::vector<float> tail;
    if (resampler_->flush(&tail) && !tail.empty()) {
      write_to_ring(tail.data(), tail.size() / static_cast<std::size_t>(kChannels));
    }
  }

  source_eof_.store(true, std::memory_order_release);
  if (output_) {
    output_->set_silence_expected(true);
  }
}

bool AudioEngine::write_to_ring(const float* data, std::size_t frames) {
  if (data == nullptr || ring_ == nullptr) {
    return false;
  }

  std::size_t offset = 0;
  while (offset < frames && running_.load(std::memory_order_relaxed)) {
    const std::size_t written =
        ring_->write(data + offset * static_cast<std::size_t>(kChannels), frames - offset);
    if (written == 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
    } else {
      offset += written;
    }
  }
  return offset == frames;
}

}  // namespace audio
