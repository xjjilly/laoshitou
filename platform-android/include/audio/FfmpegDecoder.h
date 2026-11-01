// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <atomic>
#include <cstdint>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

struct AVFormatContext;
struct AVCodecContext;
struct AVIOContext;
struct AVPacket;
struct AVFrame;
struct SwrContext;

namespace audio {

class MediaSource;

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
  // Opens a decoder input backed by a random-access MediaSource. The source
  // is owned by the decoder and released on close(). FFmpeg is built with
  // --disable-network; the source performs all I/O, so the LAN-only policy
  // stays in Kotlin (JavaMediaSource -> VettedMediaDataSource).
  bool open_source(std::unique_ptr<MediaSource> source);
  void close();

  // Seeks to a position. The DFF/DSD custom parser does not support seek;
  // callers should fall back for those files.
  bool seek_ms(std::int64_t position_ms);

  [[nodiscard]] bool is_open() const noexcept;
  [[nodiscard]] int sample_rate() const noexcept;
  [[nodiscard]] int source_channels() const noexcept;
  [[nodiscard]] std::int64_t duration_ms() const noexcept;
  [[nodiscard]] std::int64_t position_ms() const noexcept;
  [[nodiscard]] const std::string& last_error() const noexcept;

  // Decodes the next frame. On success, `out` holds interleaved stereo float
  // samples and `frames_read` is the number of frames. Returns false at EOS or
  // on error (check last_error()).
  bool read(std::vector<float>* out, int* frames_read);

 private:
  bool finish_open();
  bool open_dff(const std::string& path);
  bool read_dff(std::vector<float>* out, int* frames_read);
  bool convert_current_frame();
  void set_error(int av_error);
  void free();

  static int read_packet(void* opaque, std::uint8_t* buffer, int size);
  static std::int64_t seek(void* opaque, std::int64_t offset, int whence);

  struct AvioState {
    MediaSource* source = nullptr;
    std::int64_t position = 0;
  };

  std::unique_ptr<MediaSource> source_;
  AVIOContext* avio_ = nullptr;
  AvioState avio_state_{};
  std::atomic<std::int64_t> position_ms_{0};
  std::int64_t decoded_frames_ = 0;

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
