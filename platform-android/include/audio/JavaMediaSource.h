// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <jni.h>

#include <cstdint>
#include <string>

#include "audio/MediaSource.h"

namespace audio {

// MediaSource backed by an android.media.MediaDataSource supplied by Kotlin.
//
// The DMR uses this to feed the native decoder from the LAN-only
// VettedMediaDataSource (HTTP Range, every redirect hop checked). FFmpeg is
// built with --disable-network; the JNI bridge keeps URL policy in Kotlin.
class JavaMediaSource final : public MediaSource {
 public:
  JavaMediaSource(JNIEnv* env, jobject data_source);
  ~JavaMediaSource() override;

  JavaMediaSource(const JavaMediaSource&) = delete;
  JavaMediaSource& operator=(const JavaMediaSource&) = delete;

  int read_at(std::int64_t position, void* buffer, int size) override;
  std::int64_t size() override;
  [[nodiscard]] std::string last_error() const override;

 private:
  static constexpr int kBufferBytes = 64 * 1024;

  JavaVM* vm_ = nullptr;
  jobject data_source_ = nullptr;
  jmethodID read_at_ = nullptr;
  jmethodID get_size_ = nullptr;
  jmethodID close_ = nullptr;
  jbyteArray buffer_ = nullptr;
  std::string error_;
};

}  // namespace audio
