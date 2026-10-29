// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>

#include "dsp/AudioRingBuffer.h"

namespace dsp {
class DspChain;
}

namespace audio {

// Owns the Oboe output stream and the real-time callback.
//
// Threading:
//   - open()/start()/stop() are control-thread only.
//   - onAudioReady() runs on the Oboe audio thread and must not allocate,
//     lock, log, call JNI, or sleep.
class OboeOutput final : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
 public:
  OboeOutput() = default;
  ~OboeOutput() override;

  OboeOutput(const OboeOutput&) = delete;
  OboeOutput& operator=(const OboeOutput&) = delete;

  // Requests Exclusive + LowLatency + Float. Returns false if the stream could
  // not be opened with a float format and the requested channel count.
  bool open(int requested_sample_rate, int channels);

  // Must be called before start(). The ring must outlive the stream.
  void set_ring(dsp::AudioRingBuffer* ring) noexcept;

  // Must be called before start(). The chain must outlive the stream. Pass
  // nullptr for Direct mode (bit-perfect bypass).
  void set_dsp_chain(dsp::DspChain* chain) noexcept;

  bool start();
  void stop();

  void set_target_gain(float gain) noexcept;

  // Pause stops consuming the ring after a short fade. Resume continues from
  // the same ring position.
  void set_paused(bool paused) noexcept;

  // When the source has reached EOS, the callback still zero-fills the ring
  // but must not count the deliberate silence as an underrun.
  void set_silence_expected(bool expected) noexcept;

  [[nodiscard]] bool is_open() const noexcept;
  // True after Oboe reports that the stream was closed by the platform
  // (device change, USB unplug, Bluetooth route change, ...). The owner must
  // reopen the stream from a control thread.
  [[nodiscard]] bool is_disconnected() const noexcept;
  [[nodiscard]] bool is_exclusive() const noexcept;
  [[nodiscard]] int actual_sample_rate() const noexcept;
  [[nodiscard]] int actual_channels() const noexcept;
  [[nodiscard]] int32_t xrun_count() const noexcept;
  [[nodiscard]] int64_t underrun_count() const noexcept;

  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audio_data,
                                        int32_t num_frames) override;
  void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

 private:
  std::shared_ptr<oboe::AudioStream> stream_;
  std::atomic<dsp::AudioRingBuffer*> ring_{nullptr};
  std::atomic<dsp::DspChain*> dsp_chain_{nullptr};
  std::atomic<float> target_gain_{1.0f};
  std::atomic<int> channels_{0};
  std::atomic<int> actual_sample_rate_{0};
  std::atomic<bool> exclusive_{false};
  std::atomic<int64_t> underruns_{0};
  std::atomic<bool> disconnected_{false};
  std::atomic<bool> silence_expected_{false};
  std::atomic<bool> paused_{false};

  // Only touched by the audio callback.
  float current_gain_ = 0.0f;
};

}  // namespace audio
