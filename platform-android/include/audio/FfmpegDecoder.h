// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

struct AVFormatContext;
struct AVCodecContext;
struct AVPacket;
struct AVFrame;
struct SwrContext;

namespace audio {

// Non-real-time FFmpeg decoder. It decodes one audio frame at a time and
// converts it to interleaved stereo float at the source sample rate. Rate
// conversion to the Oboe output rate is handled separately by dsp::Resampler
// on the same supplier thread.
class FfmpegDecoder {
 public:
  FfmpegDecoder();
  ~FfmpegDecoder();

  FfmpegDecoder(const FfmpegDecoder&) = delete;
  FfmpegDecoder& operator=(const FfmpegDecoder&) = delete;

  bool open(const std::string& path);
  void close();

  [[nodiscard]] bool is_open() const noexcept;
  [[nodiscard]] int sample_rate() const noexcept;
  [[nodiscard]] int source_channels() const noexcept;
  [[nodiscard]] std::int64_t duration_ms() const noexcept;
  [[nodiscard]] const std::string& last_error() const noexcept;

  // Decodes the next frame. On success, `out` holds interleaved stereo float
  // samples and `frames_read` is the number of frames. Returns false at EOS or
  // on error (check last_error()).
  bool read(std::vector<float>* out, int* frames_read);

 private:
  bool open_dff(const std::string& path);
  bool read_dff(std::vector<float>* out, int* frames_read);
  bool convert_current_frame();
  void set_error(int av_error);
  void free();

  AVFormatContext* format_ = nullptr;
  AVCodecContext* codec_ = nullptr;
  SwrContext* swr_ = nullptr;
  AVPacket* packet_ = nullptr;
  AVFrame* frame_ = nullptr;
  int stream_index_ = -1;
  int sample_rate_ = 0;
  int source_channels_ = 0;
  std::int64_t duration_ms_ = 0;
  bool demux_eof_ = false;
  bool drain_sent_ = false;
  bool dsd_mode_ = false;
  std::ifstream dff_file_;
  std::uint64_t dff_remaining_ = 0;
  std::vector<std::uint8_t> dff_buffer_;
  std::string error_;
  std::vector<float> converted_;
};

}  // namespace audio
