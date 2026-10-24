// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/DffReader.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace dsp {
namespace {

constexpr std::uint64_t kMaxPropSize = 16U * 1024U * 1024U;

bool ReadBytes(std::istream& input, void* data, std::size_t size) {
  if (size == 0) {
    return true;
  }
  return static_cast<bool>(
      input.read(static_cast<char*>(data), static_cast<std::streamsize>(size)));
}

bool ReadFourCc(std::istream& input, char output[5]) {
  output[4] = '\0';
  return ReadBytes(input, output, 4);
}

std::uint32_t ReadU32Be(std::istream& input) {
  std::array<unsigned char, 4> bytes{};
  if (!ReadBytes(input, bytes.data(), bytes.size())) {
    return 0;
  }
  return (static_cast<std::uint32_t>(bytes[0]) << 24) |
         (static_cast<std::uint32_t>(bytes[1]) << 16) |
         (static_cast<std::uint32_t>(bytes[2]) << 8) | static_cast<std::uint32_t>(bytes[3]);
}

std::uint64_t ReadU64Be(std::istream& input) {
  std::array<unsigned char, 8> bytes{};
  if (!ReadBytes(input, bytes.data(), bytes.size())) {
    return 0;
  }
  std::uint64_t value = 0;
  for (const unsigned char byte : bytes) {
    value = (value << 8) | static_cast<std::uint64_t>(byte);
  }
  return value;
}

bool SeekTo(std::istream& input, std::uint64_t position) {
  if (position > static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max())) {
    return false;
  }
  input.seekg(static_cast<std::streamoff>(position), std::ios::beg);
  return static_cast<bool>(input);
}

bool ParsePropChunk(const std::vector<char>& payload, DffInfo* info, std::string* error) {
  if (payload.size() < 4) {
    if (error != nullptr) {
      *error = "DSDIFF PROP 块太短";
    }
    return false;
  }

  std::string body(payload.begin() + 4, payload.end());
  std::istringstream input(body);
  std::size_t offset = 0;
  while (offset + 12 <= body.size()) {
    input.seekg(static_cast<std::streamoff>(offset));
    char id[5] = {};
    if (!ReadFourCc(input, id)) {
      break;
    }
    const std::uint64_t size = ReadU64Be(input);
    const std::size_t data_start = offset + 12;
    if (size > body.size() - data_start) {
      if (error != nullptr) {
        *error = std::string("DSDIFF PROP 子块损坏：") + id;
      }
      return false;
    }

    if (std::strcmp(id, "FS  ") == 0 && size >= 4) {
      info->sample_rate_hz = ReadU32Be(input);
    } else if (std::strcmp(id, "CHNL") == 0 && size >= 2) {
      std::array<unsigned char, 2> channels{};
      if (ReadBytes(input, channels.data(), channels.size())) {
        info->channels = (static_cast<std::uint32_t>(channels[0]) << 8) |
                         static_cast<std::uint32_t>(channels[1]);
      }
    } else if (std::strcmp(id, "CMPR") == 0 && size >= 4) {
      char compression[5] = {};
      if (ReadFourCc(input, compression)) {
        info->dst_compressed = std::strcmp(compression, "DST ") == 0;
      }
    }

    offset = data_start + static_cast<std::size_t>(size);
    if ((size & 1U) != 0U) {
      ++offset;
    }
  }
  return true;
}

}  // namespace

bool ParseDffHeader(std::istream& input, DffInfo* info, std::string* error) {
  if (info == nullptr) {
    return false;
  }
  *info = DffInfo{};

  input.seekg(0, std::ios::end);
  const std::streamoff end = input.tellg();
  input.seekg(0, std::ios::beg);
  if (end <= 0) {
    if (error != nullptr) {
      *error = "无法读取 DSDIFF 文件大小";
    }
    return false;
  }
  const std::uint64_t file_size = static_cast<std::uint64_t>(end);

  char id[5] = {};
  if (!ReadFourCc(input, id) || std::strcmp(id, "FRM8") != 0) {
    if (error != nullptr) {
      *error = "不是 DSDIFF (FRM8) 文件";
    }
    return false;
  }
  ReadU64Be(input);  // Container size; chunk bounds are validated below.

  char form_type[5] = {};
  if (!ReadFourCc(input, form_type) || std::strcmp(form_type, "DSD ") != 0) {
    if (error != nullptr) {
      *error = "DSDIFF 表单类型不是 DSD";
    }
    return false;
  }

  std::uint64_t position = 16;
  while (position + 12 <= file_size) {
    if (!SeekTo(input, position)) {
      break;
    }
    char chunk_id[5] = {};
    if (!ReadFourCc(input, chunk_id)) {
      break;
    }
    const std::uint64_t chunk_size = ReadU64Be(input);
    const std::uint64_t data_start = position + 12;
    if (data_start > file_size) {
      break;
    }
    const std::uint64_t remaining = file_size - data_start;

    if (std::strcmp(chunk_id, "PROP") == 0) {
      if (chunk_size > kMaxPropSize || chunk_size > remaining) {
        if (error != nullptr) {
          *error = "DSDIFF PROP 块大小无效";
        }
        return false;
      }
      std::vector<char> payload(static_cast<std::size_t>(chunk_size));
      if (!ReadBytes(input, payload.data(), payload.size())) {
        if (error != nullptr) {
          *error = "读取 DSDIFF PROP 块失败";
        }
        return false;
      }
      if (!ParsePropChunk(payload, info, error)) {
        return false;
      }
    } else if (std::strcmp(chunk_id, "DSD ") == 0 || std::strcmp(chunk_id, "DST ") == 0) {
      info->dst_compressed = std::strcmp(chunk_id, "DST ") == 0;
      info->data_offset = data_start;
      info->data_size = std::min(chunk_size, remaining);
      break;
    }

    if (chunk_size > remaining) {
      break;
    }
    position = data_start + chunk_size;
    if ((chunk_size & 1U) != 0U) {
      ++position;
    }
    if (position > file_size) {
      break;
    }
  }

  if (info->sample_rate_hz == 0 || info->channels == 0) {
    if (error != nullptr) {
      *error = "DSDIFF 缺少 FS 或 CHNL 信息";
    }
    return false;
  }
  if ((info->sample_rate_hz % 8U) != 0U) {
    if (error != nullptr) {
      *error = "DSDIFF 采样率不是 8 的整数倍";
    }
    return false;
  }
  if (info->data_size == 0) {
    if (error != nullptr) {
      *error = "DSDIFF 缺少 DSD 音频数据";
    }
    return false;
  }
  return true;
}

bool ProbeDffFile(const std::string& path, DffInfo* info, std::string* error) {
  std::ifstream file(path, std::ios::binary);
  if (!file) {
    if (error != nullptr) {
      *error = "无法打开 DFF 文件";
    }
    return false;
  }
  return ParseDffHeader(file, info, error);
}

bool IsDffFile(const std::string& path) {
  std::ifstream file(path, std::ios::binary);
  if (!file) {
    return false;
  }
  char header[16] = {};
  if (!ReadBytes(file, header, sizeof(header))) {
    return false;
  }
  return std::memcmp(header, "FRM8", 4) == 0 && std::memcmp(header + 12, "DSD ", 4) == 0;
}

}  // namespace dsp
