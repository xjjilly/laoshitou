// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/IrLoader.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace dsp {
namespace {

std::uint16_t ReadLe16(const std::uint8_t* data) {
  return static_cast<std::uint16_t>(data[0]) |
         static_cast<std::uint16_t>(static_cast<std::uint16_t>(data[1]) << 8);
}

std::uint32_t ReadLe32(const std::uint8_t* data) {
  return static_cast<std::uint32_t>(data[0]) | (static_cast<std::uint32_t>(data[1]) << 8) |
         (static_cast<std::uint32_t>(data[2]) << 16) | (static_cast<std::uint32_t>(data[3]) << 24);
}

float PcmToFloat(const std::uint8_t* sample, int bits) {
  if (bits == 16) {
    const auto value = static_cast<std::int16_t>(static_cast<std::uint16_t>(sample[0]) |
                                                 (static_cast<std::uint16_t>(sample[1]) << 8));
    return static_cast<float>(value) / 32768.0f;
  }
  if (bits == 24) {
    std::int32_t value = static_cast<std::int32_t>(sample[0]) |
                         (static_cast<std::int32_t>(sample[1]) << 8) |
                         (static_cast<std::int32_t>(sample[2]) << 16);
    if ((value & 0x00800000) != 0) {
      value |= static_cast<std::int32_t>(0xFF000000);
    }
    return static_cast<float>(value) / 8388608.0f;
  }
  if (bits == 32) {
    const auto value = static_cast<std::int32_t>(ReadLe32(sample));
    return static_cast<float>(value) / 2147483648.0f;
  }
  return 0.0f;
}

}  // namespace

bool LoadIrWav(const std::string& path, IrData* ir, std::string* error) {
  if (ir == nullptr) {
    return false;
  }
  *ir = IrData{};

  std::ifstream file(path, std::ios::binary);
  if (!file) {
    if (error != nullptr) {
      *error = "无法打开 IR WAV 文件";
    }
    return false;
  }

  std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)),
                                  std::istreambuf_iterator<char>());
  if (bytes.size() < 12 || std::memcmp(bytes.data(), "RIFF", 4) != 0 ||
      std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
    if (error != nullptr) {
      *error = "不是 RIFF/WAVE 文件";
    }
    return false;
  }

  int format_tag = 0;
  int channels = 0;
  int sample_rate = 0;
  int bits = 0;
  std::size_t data_offset = 0;
  std::size_t data_size = 0;

  std::size_t position = 12;
  while (position + 8 <= bytes.size()) {
    char id[5] = {};
    std::memcpy(id, bytes.data() + position, 4);
    const std::uint32_t chunk_size = ReadLe32(bytes.data() + position + 4);
    const std::size_t body = position + 8;
    if (body > bytes.size()) {
      break;
    }

    if (std::strcmp(id, "fmt ") == 0) {
      if (chunk_size < 16 || body + chunk_size > bytes.size()) {
        break;
      }
      format_tag = ReadLe16(bytes.data() + body);
      channels = ReadLe16(bytes.data() + body + 2);
      sample_rate = static_cast<int>(ReadLe32(bytes.data() + body + 4));
      bits = ReadLe16(bytes.data() + body + 14);
      if (format_tag == 0xFFFE && chunk_size >= 26) {
        format_tag = ReadLe16(bytes.data() + body + 24);
      }
    } else if (std::strcmp(id, "data") == 0) {
      data_offset = body;
      data_size = std::min<std::size_t>(chunk_size, bytes.size() - body);
      break;
    }

    position = body + chunk_size + (chunk_size & 1U);
  }

  if (data_offset == 0 || data_size == 0 || channels <= 0 || sample_rate <= 0) {
    if (error != nullptr) {
      *error = "IR WAV 缺少 fmt/data 块";
    }
    return false;
  }
  if (channels != 1 && channels != 2) {
    if (error != nullptr) {
      *error = "IR 只支持单声道或立体声 WAV";
    }
    return false;
  }
  if (format_tag == 1) {
    if (bits != 16 && bits != 24 && bits != 32) {
      if (error != nullptr) {
        *error = "IR PCM 位深只支持 16/24/32";
      }
      return false;
    }
  } else if (format_tag == 3) {
    if (bits != 32 && bits != 64) {
      if (error != nullptr) {
        *error = "IR 浮点位深只支持 32/64";
      }
      return false;
    }
  } else {
    if (error != nullptr) {
      *error = "IR 只支持 PCM 或 IEEE float WAV";
    }
    return false;
  }

  const std::size_t bytes_per_sample = static_cast<std::size_t>(bits / 8);
  const std::size_t frame_bytes = static_cast<std::size_t>(channels) * bytes_per_sample;
  const std::size_t frames = data_size / frame_bytes;
  if (frames == 0) {
    if (error != nullptr) {
      *error = "IR WAV 没有音频数据";
    }
    return false;
  }

  ir->sample_rate = sample_rate;
  ir->frames = frames;
  ir->left.resize(frames);
  ir->right.resize(frames);

  for (std::size_t frame = 0; frame < frames; ++frame) {
    const std::uint8_t* base = bytes.data() + data_offset + frame * frame_bytes;
    auto decode = [&](int channel) {
      const std::uint8_t* sample = base + static_cast<std::size_t>(channel) * bytes_per_sample;
      if (format_tag == 3) {
        if (bits == 32) {
          float value = 0.0f;
          std::memcpy(&value, sample, sizeof(value));
          return value;
        }
        double value = 0.0;
        std::memcpy(&value, sample, sizeof(value));
        return static_cast<float>(value);
      }
      return PcmToFloat(sample, bits);
    };

    if (channels == 1) {
      const float value = decode(0);
      ir->left[frame] = value;
      ir->right[frame] = value;
    } else {
      ir->left[frame] = decode(0);
      ir->right[frame] = decode(1);
    }
  }

  return true;
}

}  // namespace dsp
