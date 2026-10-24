// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "audio/FfmpegDecoder.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libswresample/swresample.h>
}

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <utility>

#include "dsp/DffReader.h"

namespace audio {
namespace {

std::string AvErrorString(int error) {
  char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
  av_strerror(error, buffer, sizeof(buffer));
  return buffer;
}

}  // namespace

FfmpegDecoder::FfmpegDecoder() = default;

FfmpegDecoder::~FfmpegDecoder() { close(); }

bool FfmpegDecoder::open(const std::string& path) {
  close();

  if (dsp::IsDffFile(path)) {
    return open_dff(path);
  }

  int result = avformat_open_input(&format_, path.c_str(), nullptr, nullptr);
  if (result < 0) {
    set_error(result);
    close();
    return false;
  }

  result = avformat_find_stream_info(format_, nullptr);
  if (result < 0) {
    set_error(result);
    close();
    return false;
  }

  const AVCodec* codec = nullptr;
  stream_index_ = av_find_best_stream(format_, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
  if (stream_index_ < 0 || codec == nullptr) {
    error_ = "no audio stream";
    close();
    return false;
  }

  codec_ = avcodec_alloc_context3(codec);
  if (codec_ == nullptr) {
    error_ = "avcodec_alloc_context3 failed";
    close();
    return false;
  }

  result = avcodec_parameters_to_context(codec_, format_->streams[stream_index_]->codecpar);
  if (result < 0) {
    set_error(result);
    close();
    return false;
  }

  result = avcodec_open2(codec_, codec, nullptr);
  if (result < 0) {
    set_error(result);
    close();
    return false;
  }

  sample_rate_ = codec_->sample_rate;
  if (sample_rate_ <= 0) {
    sample_rate_ = format_->streams[stream_index_]->codecpar->sample_rate;
  }
  source_channels_ = codec_->ch_layout.nb_channels;
  if (format_->duration > 0) {
    duration_ms_ = format_->duration / (AV_TIME_BASE / 1000);
  }

  packet_ = av_packet_alloc();
  frame_ = av_frame_alloc();
  if (packet_ == nullptr || frame_ == nullptr) {
    error_ = "failed to allocate packet/frame";
    close();
    return false;
  }

  return true;
}

bool FfmpegDecoder::open_dff(const std::string& path) {
  dsp::DffInfo info;
  std::string error;
  if (!dsp::ProbeDffFile(path, &info, &error)) {
    error_ = error;
    return false;
  }
  if (info.dst_compressed) {
    error_ = "DST 压缩的 DFF 暂不支持";
    return false;
  }
  if (info.channels == 0 || info.channels > 8) {
    error_ = "不支持的 DFF 声道数";
    return false;
  }

  dff_file_.open(path, std::ios::binary);
  if (!dff_file_) {
    error_ = "无法打开 DFF 音频数据";
    return false;
  }
  dff_file_.seekg(static_cast<std::streamoff>(info.data_offset), std::ios::beg);
  if (!dff_file_) {
    error_ = "无法定位 DFF 音频数据";
    return false;
  }

  const AVCodec* codec = avcodec_find_decoder_by_name("dsd_msbf");
  if (codec == nullptr) {
    error_ = "FFmpeg 缺少 DSD 解码器";
    return false;
  }

  codec_ = avcodec_alloc_context3(codec);
  if (codec_ == nullptr) {
    error_ = "avcodec_alloc_context3 failed";
    return false;
  }
  codec_->sample_rate = static_cast<int>(info.sample_rate_hz / 8U);
  av_channel_layout_default(&codec_->ch_layout, static_cast<int>(info.channels));

  const int result = avcodec_open2(codec_, codec, nullptr);
  if (result < 0) {
    const std::string message = AvErrorString(result);
    close();
    error_ = message;
    return false;
  }

  sample_rate_ = codec_->sample_rate;
  source_channels_ = static_cast<int>(info.channels);
  if (sample_rate_ <= 0 || source_channels_ <= 0) {
    close();
    error_ = "DFF 参数无效";
    return false;
  }

  const std::uint64_t frames_per_channel = info.data_size / info.channels;
  duration_ms_ = static_cast<std::int64_t>(frames_per_channel * 1000ULL /
                                           static_cast<std::uint64_t>(sample_rate_));

  packet_ = av_packet_alloc();
  frame_ = av_frame_alloc();
  if (packet_ == nullptr || frame_ == nullptr) {
    close();
    error_ = "failed to allocate packet/frame";
    return false;
  }

  dff_remaining_ = info.data_size - (info.data_size % info.channels);
  dsd_mode_ = true;
  return true;
}

void FfmpegDecoder::close() {
  if (swr_ != nullptr) {
    swr_free(&swr_);
  }
  if (frame_ != nullptr) {
    av_frame_free(&frame_);
  }
  if (packet_ != nullptr) {
    av_packet_free(&packet_);
  }
  if (codec_ != nullptr) {
    avcodec_free_context(&codec_);
  }
  if (format_ != nullptr) {
    avformat_close_input(&format_);
  }

  stream_index_ = -1;
  sample_rate_ = 0;
  source_channels_ = 0;
  duration_ms_ = 0;
  demux_eof_ = false;
  drain_sent_ = false;
  dsd_mode_ = false;
  dff_remaining_ = 0;
  dff_buffer_.clear();
  if (dff_file_.is_open()) {
    dff_file_.close();
  }
  converted_.clear();
  error_.clear();
}

bool FfmpegDecoder::is_open() const noexcept {
  return codec_ != nullptr && (format_ != nullptr || dsd_mode_);
}

int FfmpegDecoder::sample_rate() const noexcept { return sample_rate_; }

int FfmpegDecoder::source_channels() const noexcept { return source_channels_; }

std::int64_t FfmpegDecoder::duration_ms() const noexcept { return duration_ms_; }

const std::string& FfmpegDecoder::last_error() const noexcept { return error_; }

bool FfmpegDecoder::read(std::vector<float>* out, int* frames_read) {
  if (out == nullptr || frames_read == nullptr || !is_open()) {
    return false;
  }

  if (dsd_mode_) {
    return read_dff(out, frames_read);
  }

  out->clear();
  *frames_read = 0;

  while (true) {
    int result = avcodec_receive_frame(codec_, frame_);
    if (result == 0) {
      if (!convert_current_frame()) {
        return false;
      }
      *frames_read = static_cast<int>(converted_.size() / 2);
      *out = converted_;
      av_frame_unref(frame_);
      return true;
    }

    if (result == AVERROR_EOF) {
      return false;
    }
    if (result != AVERROR(EAGAIN)) {
      set_error(result);
      return false;
    }

    if (demux_eof_) {
      return false;
    }

    result = av_read_frame(format_, packet_);
    if (result == AVERROR_EOF) {
      demux_eof_ = true;
      if (!drain_sent_) {
        drain_sent_ = true;
        avcodec_send_packet(codec_, nullptr);
      }
      continue;
    }
    if (result < 0) {
      set_error(result);
      return false;
    }

    if (packet_->stream_index != stream_index_) {
      av_packet_unref(packet_);
      continue;
    }

    result = avcodec_send_packet(codec_, packet_);
    av_packet_unref(packet_);
    if (result < 0) {
      // With this drain-before-send loop, EAGAIN should not happen. Treat it
      // as an error rather than silently dropping audio.
      set_error(result);
      return false;
    }
  }
}

bool FfmpegDecoder::read_dff(std::vector<float>* out, int* frames_read) {
  out->clear();
  *frames_read = 0;

  constexpr std::size_t kBytesPerChannelPerPacket = 4096;
  const auto channels = static_cast<std::size_t>(std::max(source_channels_, 1));

  while (true) {
    int result = avcodec_receive_frame(codec_, frame_);
    if (result == 0) {
      if (!convert_current_frame()) {
        return false;
      }
      *frames_read = static_cast<int>(converted_.size() / 2);
      *out = converted_;
      av_frame_unref(frame_);
      return true;
    }

    if (result == AVERROR_EOF) {
      return false;
    }
    if (result != AVERROR(EAGAIN)) {
      set_error(result);
      return false;
    }
    if (drain_sent_) {
      return false;
    }

    if (dff_remaining_ > 0) {
      std::size_t block = kBytesPerChannelPerPacket * channels;
      block = static_cast<std::size_t>(std::min<std::uint64_t>(block, dff_remaining_));
      block -= block % channels;
      if (block == 0) {
        dff_remaining_ = 0;
        continue;
      }

      dff_buffer_.resize(block);
      dff_file_.read(reinterpret_cast<char*>(dff_buffer_.data()),
                     static_cast<std::streamsize>(block));
      const auto got = static_cast<std::size_t>(std::max<std::streamsize>(dff_file_.gcount(), 0));
      if (got == 0) {
        dff_remaining_ = 0;
        continue;
      }
      dff_remaining_ -= got;

      const std::size_t usable = got - (got % channels);
      if (usable == 0) {
        continue;
      }

      if (av_new_packet(packet_, static_cast<int>(usable)) < 0) {
        error_ = "av_new_packet failed";
        return false;
      }
      std::memcpy(packet_->data, dff_buffer_.data(), usable);
      result = avcodec_send_packet(codec_, packet_);
      av_packet_unref(packet_);
      if (result < 0) {
        set_error(result);
        return false;
      }
      continue;
    }

    drain_sent_ = true;
    result = avcodec_send_packet(codec_, nullptr);
    if (result < 0 && result != AVERROR_EOF) {
      set_error(result);
      return false;
    }
  }
}

bool FfmpegDecoder::convert_current_frame() {
  if (frame_ == nullptr || frame_->nb_samples <= 0) {
    return false;
  }

  int input_rate = frame_->sample_rate;
  if (input_rate <= 0) {
    input_rate = sample_rate_;
  }
  const int input_channels = frame_->ch_layout.nb_channels;
  if (input_rate <= 0 || input_channels <= 0) {
    error_ = "invalid decoded frame parameters";
    return false;
  }

  if (swr_ == nullptr) {
    AVChannelLayout output_layout = AV_CHANNEL_LAYOUT_STEREO;
    const int result = swr_alloc_set_opts2(
        &swr_, &output_layout, AV_SAMPLE_FMT_FLT, input_rate, &frame_->ch_layout,
        static_cast<AVSampleFormat>(frame_->format), input_rate, 0, nullptr);
    if (result < 0 || swr_ == nullptr) {
      if (result < 0) {
        set_error(result);
      } else {
        error_ = "swr_alloc_set_opts2 failed";
      }
      return false;
    }
    const int init_result = swr_init(swr_);
    if (init_result < 0) {
      set_error(init_result);
      return false;
    }
  }

  const int output_capacity = static_cast<int>(av_rescale_rnd(
      swr_get_delay(swr_, input_rate) + frame_->nb_samples, input_rate, input_rate, AV_ROUND_UP));
  if (output_capacity <= 0) {
    return false;
  }

  converted_.assign(static_cast<std::size_t>(output_capacity) * 2, 0.0f);
  uint8_t* output_data[1] = {reinterpret_cast<uint8_t*>(converted_.data())};

  const int converted =
      swr_convert(swr_, output_data, output_capacity, frame_->extended_data, frame_->nb_samples);
  if (converted < 0) {
    set_error(converted);
    return false;
  }

  converted_.resize(static_cast<std::size_t>(converted) * 2);
  return true;
}

void FfmpegDecoder::set_error(int av_error) { error_ = AvErrorString(av_error); }

}  // namespace audio
