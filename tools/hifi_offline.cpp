// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

// Offline PC version of the playback DSP chain:
//   WAV -> optional libsamplerate conversion -> PEQ/preamp -> crossfeed ->
//   limiter -> float32 WAV.
//
// Used to verify the same core-dsp code that runs on the phone with REW or
// any other analyzer, without involving Android or a real-time audio device.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "dsp/AutoEqProfile.h"
#include "dsp/Crossfeed.h"
#include "dsp/DspChain.h"
#include "dsp/Resampler.h"

namespace {

constexpr std::size_t kBlockFrames = 4096;

struct WavFormat {
  int sample_rate = 0;
  int channels = 0;
  int bits_per_sample = 0;
  int format_tag = 0;  // 1 = PCM, 3 = IEEE float.
  std::size_t block_align = 0;
};

std::uint16_t ReadLe16(const std::uint8_t* data) {
  return static_cast<std::uint16_t>(data[0]) |
         static_cast<std::uint16_t>(static_cast<std::uint16_t>(data[1]) << 8);
}

std::uint32_t ReadLe32(const std::uint8_t* data) {
  return static_cast<std::uint32_t>(data[0]) | (static_cast<std::uint32_t>(data[1]) << 8) |
         (static_cast<std::uint32_t>(data[2]) << 16) | (static_cast<std::uint32_t>(data[3]) << 24);
}

class WavReader {
 public:
  bool open(const std::string& path, std::string* error) {
    file_.open(path, std::ios::binary);
    if (!file_) {
      *error = "无法打开输入 WAV";
      return false;
    }

    char header[12] = {};
    if (!file_.read(header, sizeof(header)) || std::memcmp(header, "RIFF", 4) != 0 ||
        std::memcmp(header + 8, "WAVE", 4) != 0) {
      *error = "不是 RIFF/WAVE 文件";
      return false;
    }

    std::uint64_t file_size = 0;
    file_.seekg(0, std::ios::end);
    file_size = static_cast<std::uint64_t>(file_.tellg());
    file_.seekg(12, std::ios::beg);

    bool have_format = false;
    bool have_data = false;
    while (!have_data && file_) {
      char chunk_id[4] = {};
      std::uint8_t size_bytes[4] = {};
      if (!file_.read(chunk_id, 4) || !file_.read(reinterpret_cast<char*>(size_bytes), 4)) {
        break;
      }
      const std::uint64_t chunk_size = ReadLe32(size_bytes);
      const std::uint64_t chunk_start = static_cast<std::uint64_t>(file_.tellg());
      if (chunk_start > file_size) {
        break;
      }

      if (std::memcmp(chunk_id, "fmt ", 4) == 0) {
        if (chunk_size < 16 || chunk_size > 4096 || chunk_start + chunk_size > file_size) {
          *error = "WAV fmt 块无效";
          return false;
        }
        std::vector<std::uint8_t> fmt(static_cast<std::size_t>(chunk_size));
        if (!file_.read(reinterpret_cast<char*>(fmt.data()),
                        static_cast<std::streamsize>(fmt.size()))) {
          *error = "读取 WAV fmt 块失败";
          return false;
        }
        format_.format_tag = ReadLe16(fmt.data());
        format_.channels = ReadLe16(fmt.data() + 2);
        format_.sample_rate = static_cast<int>(ReadLe32(fmt.data() + 4));
        format_.bits_per_sample = ReadLe16(fmt.data() + 14);
        if (format_.format_tag == 0xFFFE && fmt.size() >= 26) {
          format_.format_tag = ReadLe16(fmt.data() + 24);
        }
        have_format = true;
      } else if (std::memcmp(chunk_id, "data", 4) == 0) {
        if (!have_format) {
          *error = "WAV data 块出现在 fmt 之前";
          return false;
        }
        std::uint64_t data_size = chunk_size;
        const std::uint64_t remaining = file_size - chunk_start;
        if (data_size == 0 || data_size > remaining) {
          data_size = remaining;
        }
        format_.block_align = 0;  // Set below from the validated format/channel count.
        if (format_.format_tag == 1) {
          if (format_.bits_per_sample != 8 && format_.bits_per_sample != 16 &&
              format_.bits_per_sample != 24 && format_.bits_per_sample != 32) {
            *error = "不支持该 PCM 位深";
            return false;
          }
        } else if (format_.format_tag == 3) {
          if (format_.bits_per_sample != 32 && format_.bits_per_sample != 64) {
            *error = "不支持该浮点位深";
            return false;
          }
        } else {
          *error = "仅支持 PCM 或 IEEE float WAV";
          return false;
        }
        if (format_.channels != 1 && format_.channels != 2) {
          *error = "离线工具仅支持单声道或立体声 WAV";
          return false;
        }
        if (format_.sample_rate <= 0) {
          *error = "WAV 采样率无效";
          return false;
        }
        format_.block_align = static_cast<std::size_t>(format_.channels) *
                              static_cast<std::size_t>(format_.bits_per_sample / 8);
        data_remaining_ = data_size - (data_size % format_.block_align);
        total_frames_ = data_remaining_ / format_.block_align;
        buffer_.resize(kBlockFrames * format_.block_align);
        have_data = true;
      } else {
        if (chunk_size > file_size - chunk_start) {
          break;
        }
        file_.seekg(static_cast<std::streamoff>(chunk_size + (chunk_size & 1)), std::ios::cur);
      }

      if (!have_data) {
        if (chunk_size > file_size - chunk_start) {
          break;
        }
        file_.seekg(static_cast<std::streamoff>(chunk_start + chunk_size + (chunk_size & 1)),
                    std::ios::beg);
      }
    }

    if (!have_data) {
      *error = "WAV 缺少 data 块";
      return false;
    }
    return true;
  }

  [[nodiscard]] int sample_rate() const noexcept { return format_.sample_rate; }
  [[nodiscard]] int channels() const noexcept { return format_.channels; }
  [[nodiscard]] std::uint64_t total_frames() const noexcept { return total_frames_; }

  // Reads up to `frames` frames as interleaved stereo float.
  std::size_t read(float* out, std::size_t frames) {
    if (frames == 0 || data_remaining_ == 0) {
      return 0;
    }
    const std::size_t max_frames = static_cast<std::size_t>(data_remaining_ / format_.block_align);
    frames = std::min(frames, max_frames);
    if (frames == 0) {
      return 0;
    }

    const std::size_t bytes = frames * format_.block_align;
    if (!file_.read(reinterpret_cast<char*>(buffer_.data()), static_cast<std::streamsize>(bytes))) {
      frames = static_cast<std::size_t>(file_.gcount()) / format_.block_align;
      if (frames == 0) {
        data_remaining_ = 0;
        return 0;
      }
      return read_impl(out, frames);
    }
    data_remaining_ -= bytes;
    return read_impl(out, frames);
  }

 private:
  std::size_t read_impl(float* out, std::size_t frames) {
    const int channels = format_.channels;
    const int bytes_per_sample = format_.bits_per_sample / 8;
    for (std::size_t frame = 0; frame < frames; ++frame) {
      float left = 0.0f;
      float right = 0.0f;
      for (int channel = 0; channel < channels; ++channel) {
        const std::size_t offset =
            (frame * static_cast<std::size_t>(channels) + static_cast<std::size_t>(channel)) *
            static_cast<std::size_t>(bytes_per_sample);
        const std::uint8_t* sample = buffer_.data() + offset;
        const float value = decode_sample(sample);
        if (channel == 0) {
          left = value;
        } else {
          right = value;
        }
      }
      if (channels == 1) {
        right = left;
      }
      out[frame * 2] = left;
      out[frame * 2 + 1] = right;
    }
    return frames;
  }

  float decode_sample(const std::uint8_t* sample) const {
    if (format_.format_tag == 3) {
      if (format_.bits_per_sample == 32) {
        float value = 0.0f;
        std::memcpy(&value, sample, sizeof(value));
        return value;
      }
      double value = 0.0;
      std::memcpy(&value, sample, sizeof(value));
      return static_cast<float>(value);
    }
    switch (format_.bits_per_sample) {
      case 8:
        return (static_cast<float>(sample[0]) - 128.0f) / 128.0f;
      case 16: {
        const auto value = static_cast<std::int16_t>(static_cast<std::uint16_t>(sample[0]) |
                                                     (static_cast<std::uint16_t>(sample[1]) << 8));
        return static_cast<float>(value) / 32768.0f;
      }
      case 24: {
        std::int32_t value = static_cast<std::int32_t>(sample[0]) |
                             (static_cast<std::int32_t>(sample[1]) << 8) |
                             (static_cast<std::int32_t>(sample[2]) << 16);
        if ((value & 0x00800000) != 0) {
          value |= static_cast<std::int32_t>(0xFF000000);
        }
        return static_cast<float>(value) / 8388608.0f;
      }
      case 32: {
        const auto value = static_cast<std::int32_t>(ReadLe32(sample));
        return static_cast<float>(value) / 2147483648.0f;
      }
      default:
        return 0.0f;
    }
  }

  std::ifstream file_;
  WavFormat format_;
  std::uint64_t data_remaining_ = 0;
  std::uint64_t total_frames_ = 0;
  std::vector<std::uint8_t> buffer_;
};

class WavWriter {
 public:
  bool open(const std::string& path, int sample_rate, int channels, std::string* error) {
    file_.open(path, std::ios::binary);
    if (!file_) {
      *error = "无法创建输出 WAV";
      return false;
    }
    channels_ = channels;

    Write("RIFF", 4);
    WriteU32(0);
    Write("WAVE", 4);
    Write("fmt ", 4);
    WriteU32(16);
    WriteU16(3);  // IEEE float
    WriteU16(static_cast<std::uint16_t>(channels));
    WriteU32(static_cast<std::uint32_t>(sample_rate));
    WriteU32(static_cast<std::uint32_t>(sample_rate * channels * 4));
    WriteU16(static_cast<std::uint16_t>(channels * 4));
    WriteU16(32);
    Write("fact", 4);
    WriteU32(4);
    WriteU32(0);
    Write("data", 4);
    WriteU32(0);
    return static_cast<bool>(file_);
  }

  bool write(const float* interleaved, std::size_t frames) {
    if (frames == 0) {
      return true;
    }
    const std::size_t samples = frames * static_cast<std::size_t>(channels_);
    const std::size_t byte_count = samples * sizeof(float);
    if (byte_count > static_cast<std::size_t>(0xFFFFFFFFU - data_bytes_)) {
      return false;
    }
    const auto bytes = reinterpret_cast<const char*>(interleaved);
    file_.write(bytes, static_cast<std::streamsize>(byte_count));
    if (!file_) {
      return false;
    }
    data_bytes_ += static_cast<std::uint32_t>(byte_count);
    frames_ += frames;
    return true;
  }

  bool close() {
    if (!file_.is_open()) {
      return true;
    }
    file_.seekp(4, std::ios::beg);
    WriteU32(48U + data_bytes_);
    file_.seekp(44, std::ios::beg);
    WriteU32(static_cast<std::uint32_t>(frames_));
    file_.seekp(52, std::ios::beg);
    WriteU32(data_bytes_);
    file_.close();
    return static_cast<bool>(file_);
  }

 private:
  void Write(const char* data, std::size_t size) {
    file_.write(data, static_cast<std::streamsize>(size));
  }
  void WriteU16(std::uint16_t value) {
    const std::uint8_t bytes[2] = {static_cast<std::uint8_t>(value & 0xFF),
                                   static_cast<std::uint8_t>((value >> 8) & 0xFF)};
    Write(reinterpret_cast<const char*>(bytes), sizeof(bytes));
  }
  void WriteU32(std::uint32_t value) {
    const std::uint8_t bytes[4] = {static_cast<std::uint8_t>(value & 0xFF),
                                   static_cast<std::uint8_t>((value >> 8) & 0xFF),
                                   static_cast<std::uint8_t>((value >> 16) & 0xFF),
                                   static_cast<std::uint8_t>((value >> 24) & 0xFF)};
    Write(reinterpret_cast<const char*>(bytes), sizeof(bytes));
  }

  std::ofstream file_;
  int channels_ = 0;
  std::uint32_t data_bytes_ = 0;
  std::uint64_t frames_ = 0;
};

struct Options {
  std::string input;
  std::string output;
  std::string eq_path;
  std::string crossfeed = "off";
  std::string quality = "auto";
  double crossfeed_width = -1.0;
  double stereo_width = 1.0;
  double loudness_volume = 1.0;
  double limiter_threshold_db = -1.0;
  std::string brir_path;
  double brir_mix = 0.35;
  int target_rate = 0;
  int order = 1;
  bool slope_set = false;
  double slope = 1.0;
  bool limiter = false;
  bool dry = false;
};

void PrintUsage() {
  std::cout << "hifi_offline - run the player DSP chain on a WAV file\n\n"
            << "  --input <file.wav>        input WAV (PCM/float, mono or stereo)\n"
            << "  --output <file.wav>       output float32 WAV (stereo)\n"
            << "  --rate <hz>               output rate; default = input rate\n"
            << "  --eq <profile>            AutoEQ txt/CSV profile\n"
            << "  --order <1|2>             2nd-order or cascaded 4th-order bands\n"
            << "  --slope <0.05..1.0>       shelves use RBJ slope S instead of Q\n"
            << "  --crossfeed <off|weak|medium|strong>  default off\n"
            << "  --crossfeed-width <0..1>  continuous bs2b strength\n"
            << "  --width <0..1.5>          M/S stereo width (default 1.0)\n"
            << "  --loudness-volume <0..1>  loudness compensation volume (1 = off)\n"
            << "  --quality <auto|best|medium|fastest>  default auto\n"
            << "  --limiter                 enable the look-ahead limiter\n"
            << "  --limiter-threshold <db>  limiter threshold (-24..0, default -1)\n"
            << "  --brir <file.wav>         stereo BRIR impulse response\n"
            << "  --brir-mix <0..1>         BRIR wet mix (default 0.35)\n"
            << "  --dry                     bypass resampling and DSP\n";
}

bool ParseDouble(const std::string& text, double* value) {
  char* end = nullptr;
  const double parsed = std::strtod(text.c_str(), &end);
  if (end == text.c_str() || *end != '\0' || !std::isfinite(parsed)) {
    return false;
  }
  *value = parsed;
  return true;
}

bool ParseOptions(int argc, char** argv, Options* options) {
  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    auto need_value = [&](std::string* value) {
      if (i + 1 >= argc) {
        return false;
      }
      *value = argv[++i];
      return true;
    };

    if (arg == "--input") {
      if (!need_value(&options->input)) return false;
    } else if (arg == "--output") {
      if (!need_value(&options->output)) return false;
    } else if (arg == "--eq") {
      if (!need_value(&options->eq_path)) return false;
    } else if (arg == "--crossfeed") {
      if (!need_value(&options->crossfeed)) return false;
    } else if (arg == "--quality") {
      if (!need_value(&options->quality)) return false;
    } else if (arg == "--crossfeed-width") {
      std::string value;
      if (!need_value(&value) || !ParseDouble(value, &options->crossfeed_width) ||
          options->crossfeed_width < 0.0 || options->crossfeed_width > 1.0) {
        return false;
      }
    } else if (arg == "--width") {
      std::string value;
      if (!need_value(&value) || !ParseDouble(value, &options->stereo_width) ||
          options->stereo_width < 0.0 || options->stereo_width > 1.5) {
        return false;
      }
    } else if (arg == "--loudness-volume") {
      std::string value;
      if (!need_value(&value) || !ParseDouble(value, &options->loudness_volume) ||
          options->loudness_volume < 0.0 || options->loudness_volume > 1.0) {
        return false;
      }
    } else if (arg == "--rate") {
      std::string value;
      double parsed = 0.0;
      if (!need_value(&value) || !ParseDouble(value, &parsed) || parsed < 8000.0 ||
          parsed > 768000.0) {
        return false;
      }
      options->target_rate = static_cast<int>(parsed);
    } else if (arg == "--order") {
      std::string value;
      double parsed = 0.0;
      if (!need_value(&value) || !ParseDouble(value, &parsed) || (parsed != 1.0 && parsed != 2.0)) {
        return false;
      }
      options->order = static_cast<int>(parsed);
    } else if (arg == "--slope") {
      std::string value;
      if (!need_value(&value) || !ParseDouble(value, &options->slope) || options->slope <= 0.0 ||
          options->slope > 1.0) {
        return false;
      }
      options->slope_set = true;
    } else if (arg == "--limiter") {
      options->limiter = true;
    } else if (arg == "--brir") {
      if (!need_value(&options->brir_path)) return false;
    } else if (arg == "--brir-mix") {
      std::string value;
      if (!need_value(&value) || !ParseDouble(value, &options->brir_mix) ||
          options->brir_mix < 0.0 || options->brir_mix > 1.0) {
        return false;
      }
    } else if (arg == "--limiter-threshold") {
      std::string value;
      if (!need_value(&value) || !ParseDouble(value, &options->limiter_threshold_db) ||
          options->limiter_threshold_db < -24.0 || options->limiter_threshold_db > 0.0) {
        return false;
      }
    } else if (arg == "--dry") {
      options->dry = true;
    } else if (arg == "--help" || arg == "-h") {
      return false;
    } else {
      return false;
    }
  }
  return !options->input.empty() && !options->output.empty();
}

bool ParseCrossfeed(const std::string& text, dsp::CrossfeedPreset* preset, bool* enabled) {
  *enabled = true;
  if (text == "off") {
    *enabled = false;
    return true;
  }
  if (text == "weak") {
    *preset = dsp::CrossfeedPreset::kWeak;
    return true;
  }
  if (text == "medium") {
    *preset = dsp::CrossfeedPreset::kMedium;
    return true;
  }
  if (text == "strong") {
    *preset = dsp::CrossfeedPreset::kStrong;
    return true;
  }
  return false;
}

std::string FormatDb(double value) {
  char buffer[64] = {};
  std::snprintf(buffer, sizeof(buffer), "%.2f", value);
  return buffer;
}

}  // namespace

int main(int argc, char** argv) {
  Options options;
  if (!ParseOptions(argc, argv, &options)) {
    PrintUsage();
    return 1;
  }

  WavReader reader;
  std::string error;
  if (!reader.open(options.input, &error)) {
    std::cerr << "输入错误: " << error << "\n";
    return 1;
  }

  const int source_rate = reader.sample_rate();
  const int target_rate =
      options.dry ? source_rate : (options.target_rate > 0 ? options.target_rate : source_rate);

  dsp::CrossfeedPreset crossfeed_preset = dsp::CrossfeedPreset::kWeak;
  bool crossfeed_enabled = false;
  if (!ParseCrossfeed(options.crossfeed, &crossfeed_preset, &crossfeed_enabled)) {
    std::cerr << "--crossfeed 必须是 off/weak/medium/strong\n";
    return 1;
  }

  dsp::DspChainConfig chain_config;
  bool have_eq = false;
  dsp::AutoEqProfile profile;
  if (!options.eq_path.empty()) {
    dsp::AutoEqOptions eq_options;
    eq_options.order = options.order;
    eq_options.use_slope = options.slope_set;
    eq_options.slope = options.slope;
    if (!dsp::LoadAutoEqProfile(options.eq_path, &profile, &error, eq_options)) {
      std::cerr << "EQ 加载失败: " << error << "\n";
      return 1;
    }
    have_eq = true;
    chain_config.eq_enabled = true;
    chain_config.eq.preamp_db = profile.preamp_db;
    chain_config.eq.bands = profile.bands;
  }
  chain_config.crossfeed_enabled = crossfeed_enabled;
  chain_config.crossfeed_preset = crossfeed_preset;
  chain_config.crossfeed_width = options.crossfeed_width;
  chain_config.stereo_width = options.stereo_width;
  chain_config.loudness_enabled = options.loudness_volume < 1.0;
  chain_config.loudness_volume = options.loudness_volume;
  chain_config.limiter_enabled = options.limiter;
  chain_config.limiter_threshold_db = options.limiter_threshold_db;
  chain_config.brir_enabled = !options.brir_path.empty() && options.brir_mix > 0.0;
  chain_config.brir_path = options.brir_path;
  chain_config.brir_mix = options.brir_mix;

  dsp::DspChain chain;
  chain.configure(target_rate, 2, chain_config);

  dsp::ResamplerQuality resampler_quality = dsp::ResamplerQuality::kAuto;
  if (options.quality == "auto") {
    // Match the app: BEST for PCM up to 192 kHz, MEDIUM for DSD/higher rates.
    resampler_quality =
        source_rate > 192000 ? dsp::ResamplerQuality::kMedium : dsp::ResamplerQuality::kBest;
  } else if (options.quality == "best") {
    resampler_quality = dsp::ResamplerQuality::kBest;
  } else if (options.quality == "medium") {
    resampler_quality = dsp::ResamplerQuality::kMedium;
  } else if (options.quality == "fastest") {
    resampler_quality = dsp::ResamplerQuality::kFastest;
  } else {
    std::cerr << "--quality 必须是 auto/best/medium/fastest\n";
    return 1;
  }

  std::unique_ptr<dsp::Resampler> resampler;
  if (!options.dry && target_rate != source_rate) {
    resampler = std::make_unique<dsp::Resampler>(
        2, static_cast<double>(target_rate) / static_cast<double>(source_rate), resampler_quality);
    if (!resampler->valid()) {
      std::cerr << "重采样器初始化失败\n";
      return 1;
    }
  }

  WavWriter writer;
  if (!writer.open(options.output, target_rate, 2, &error)) {
    std::cerr << "输出错误: " << error << "\n";
    return 1;
  }

  std::vector<float> input(kBlockFrames * 2);
  std::vector<float> resampled;
  std::uint64_t frames_written = 0;
  double peak = 0.0;
  double sum_left_squared = 0.0;
  double sum_right_squared = 0.0;
  double sum_mono_squared = 0.0;

  auto process_and_write = [&](float* data, std::size_t frames) {
    if (frames == 0) {
      return true;
    }
    if (!options.dry) {
      chain.process(data, static_cast<int>(frames), 2);
    }
    for (std::size_t frame = 0; frame < frames; ++frame) {
      const double left = static_cast<double>(data[frame * 2]);
      const double right = static_cast<double>(data[frame * 2 + 1]);
      const double mono = 0.5 * (left + right);
      peak = std::max(peak, std::max(std::abs(left), std::abs(right)));
      sum_left_squared += left * left;
      sum_right_squared += right * right;
      sum_mono_squared += mono * mono;
    }
    if (!writer.write(data, frames)) {
      return false;
    }
    frames_written += frames;
    return true;
  };

  while (true) {
    const std::size_t frames = reader.read(input.data(), kBlockFrames);
    if (frames == 0) {
      break;
    }
    if (resampler) {
      if (!resampler->process(input.data(), frames, &resampled)) {
        std::cerr << "重采样失败\n";
        return 1;
      }
      if (!process_and_write(resampled.data(), resampled.size() / 2)) {
        std::cerr << "写入输出失败\n";
        return 1;
      }
    } else if (!process_and_write(input.data(), frames)) {
      std::cerr << "写入输出失败\n";
      return 1;
    }
  }

  if (resampler) {
    std::vector<float> tail;
    if (!resampler->flush(&tail)) {
      std::cerr << "重采样器 flush 失败\n";
      return 1;
    }
    if (!process_and_write(tail.data(), tail.size() / 2)) {
      std::cerr << "写入输出失败\n";
      return 1;
    }
  }

  if (!writer.close()) {
    std::cerr << "关闭输出 WAV 失败\n";
    return 1;
  }

  const double peak_db = peak > 0.0 ? 20.0 * std::log10(peak) : -999.0;
  std::cout << "输入: " << source_rate << " Hz, " << reader.channels() << " ch, "
            << reader.total_frames() << " frames\n";
  std::cout << "输出: " << target_rate << " Hz, " << (options.dry ? "dry" : "DSP") << ", "
            << frames_written << " frames\n";
  if (have_eq) {
    std::cout << "EQ: " << profile.bands.size() << " bands, preamp " << FormatDb(profile.preamp_db)
              << " dB, order " << options.order << ", shelf " << (options.slope_set ? "S" : "Q")
              << "\n";
  }
  const char* quality_label = "AUTO";
  switch (resampler_quality) {
    case dsp::ResamplerQuality::kBest:
      quality_label = "BEST";
      break;
    case dsp::ResamplerQuality::kMedium:
      quality_label = "MEDIUM";
      break;
    case dsp::ResamplerQuality::kFastest:
      quality_label = "FASTEST";
      break;
    case dsp::ResamplerQuality::kAuto:
      quality_label = "AUTO";
      break;
  }
  std::cout << "Resampler: " << quality_label << "\n";
  std::cout << "Crossfeed: " << (crossfeed_enabled ? options.crossfeed : "off")
            << ", crossfeed width: " << options.crossfeed_width
            << ", M/S width: " << options.stereo_width
            << ", loudness volume: " << options.loudness_volume
            << ", limiter: " << (options.limiter ? "on" : "off") << " ("
            << options.limiter_threshold_db << " dBFS)"
            << ", BRIR: " << (chain_config.brir_enabled ? options.brir_path : "off") << " (mix "
            << options.brir_mix << ")\n";
  std::cout << "Peak after processing: " << FormatDb(peak_db) << " dBFS\n";
  if (peak > 1.0) {
    std::cout << "警告: 峰值超过 0 dBFS；测量时请降低信号电平或开启限制器。\n";
  }
  if (frames_written > 0) {
    const double average_channel = std::sqrt((sum_left_squared + sum_right_squared) /
                                             (2.0 * static_cast<double>(frames_written)));
    const double mono = std::sqrt(sum_mono_squared / static_cast<double>(frames_written));
    const double mono_db =
        average_channel > 0.0 ? 20.0 * std::log10(std::max(mono, 1.0e-12) / average_channel) : 0.0;
    std::cout << "Mono compatibility: " << FormatDb(mono_db)
              << " dB (0 = correlated, -3 = uncorrelated)\n";
    if (mono_db < -6.0) {
      std::cout << "警告: 单声道混合电平明显下降，检查 Crossfeed/M-S/相位。\n";
    }
  }
  return 0;
}
