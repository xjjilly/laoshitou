// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>

#include "audio/FfmpegDecoder.h"
#include "audio/OboeOutput.h"
#include "dsp/AudioRingBuffer.h"
#include "dsp/DspChain.h"
#include "dsp/Resampler.h"
#include "dsp/SampleRatePolicy.h"

namespace audio {

struct PlaybackOptions {
  dsp::OutputMode mode = dsp::OutputMode::kProcessing;
  dsp::DeviceRates device;
  std::string eq_profile_path;
  bool eq_cascade = false;
  bool eq_use_slope = false;
  double eq_slope = 1.0;
  // 0 = use the sample-rate policy; otherwise try this rate first.
  int forced_sample_rate = 0;
  // 0 = auto, 1 = BEST, 2 = MEDIUM, 3 = FASTEST.
  int resampler_quality = 0;
  bool usb_dac_detected = false;
  bool auto_usb_192k = false;
  bool brir_enabled = false;
  std::string brir_path;
  double brir_mix = 0.35;
  bool crossfeed_enabled = true;
  dsp::CrossfeedPreset crossfeed_preset = dsp::CrossfeedPreset::kWeak;
  double crossfeed_width = -1.0;
  double stereo_width = 1.0;
  bool loudness_enabled = false;
  double loudness_volume = 1.0;
  bool limiter_enabled = true;
  double limiter_threshold_db = -1.0;
};

// Owns the playback pipeline:
//   FFmpeg decode -> stereo float at source rate -> libsamplerate ->
//   SPSC ring -> Oboe callback.
//
// The test-tone path is kept as a hardware validation mode. File decoding
// runs on a supplier thread; the Oboe callback only touches the ring and the
// gain ramp.
class AudioEngine {
 public:
  AudioEngine() = default;
  ~AudioEngine();

  AudioEngine(const AudioEngine&) = delete;
  AudioEngine& operator=(const AudioEngine&) = delete;

  bool start_test_tone(int requested_sample_rate);
  bool start_file(const std::string& path, const PlaybackOptions& options);
  void stop();

  // True when Oboe reported that the output stream was closed by the platform
  // (device change, USB unplug, Bluetooth route change). The owner should call
  // recover_output() from a control thread, typically the playback monitor.
  [[nodiscard]] bool needs_output_recovery() const noexcept;
  // Reopens the output after a platform-initiated disconnect. Keeps the
  // decoder position. When the new route keeps the same sample rate the ring,
  // resampler and DSP state are reused; otherwise the rate-dependent pipeline
  // is rebuilt. Returns false if no stream could be opened.
  bool recover_output();

  void set_paused(bool paused) noexcept;
  void set_gain(float gain) noexcept;
  // Updates loudness compensation while playing. 1.0 = no compensation.
  void set_loudness_volume(double volume) noexcept;
  [[nodiscard]] bool is_paused() const noexcept;

  [[nodiscard]] bool is_running() const noexcept;
  [[nodiscard]] int actual_sample_rate() const noexcept;
  [[nodiscard]] int source_sample_rate() const noexcept;
  [[nodiscard]] int target_sample_rate() const noexcept;
  [[nodiscard]] bool cross_family_fallback() const noexcept;
  [[nodiscard]] bool non_integer_ratio() const noexcept;
  [[nodiscard]] bool device_limited() const noexcept;
  [[nodiscard]] bool is_exclusive() const noexcept;
  [[nodiscard]] bool source_eof() const noexcept;
  [[nodiscard]] bool processing_mode() const noexcept;
  [[nodiscard]] int eq_band_count() const noexcept;
  [[nodiscard]] bool crossfeed_enabled() const noexcept;
  [[nodiscard]] bool limiter_enabled() const noexcept;
  [[nodiscard]] bool brir_active() const noexcept;
  [[nodiscard]] int64_t underruns() const noexcept;

 private:
  void fill_tone(int sample_rate, std::size_t frames, double* phase);
  void producer_loop(int sample_rate, double phase);
  void file_producer_loop();
  bool write_to_ring(const float* data, std::size_t frames);
  bool configure_resampler(int source_rate, int actual_rate, int quality_option);
  void configure_processing(int actual_rate, const PlaybackOptions& options);
  void update_rate_flags(int source_rate, int actual_rate) noexcept;
  bool recover_output_impl();

  std::unique_ptr<dsp::AudioRingBuffer> ring_;
  std::unique_ptr<OboeOutput> output_;
  std::unique_ptr<FfmpegDecoder> decoder_;
  std::unique_ptr<dsp::Resampler> resampler_;
  dsp::DspChain dsp_chain_;
  std::thread producer_;
  std::atomic<bool> running_{false};
  std::atomic<bool> source_eof_{false};
  PlaybackOptions options_;
  bool test_tone_ = false;
  int requested_sample_rate_ = 0;
  int actual_sample_rate_ = 0;
  int source_sample_rate_ = 0;
  int target_sample_rate_ = 0;
  bool cross_family_fallback_ = false;
  bool non_integer_ratio_ = false;
  bool device_limited_ = false;
  bool processing_mode_ = false;
  bool eq_loaded_ = false;
  bool crossfeed_enabled_ = false;
  bool limiter_enabled_ = false;
  bool paused_ = false;
  float gain_ = 1.0f;
};

}  // namespace audio
