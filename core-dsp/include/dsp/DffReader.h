// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstdint>
#include <istream>
#include <string>

namespace dsp {

// Header information from a DSDIFF (.dff) container.
struct DffInfo {
  std::uint32_t sample_rate_hz = 0;  // Raw DSD rate, e.g. 2822400 (DSD64).
  std::uint32_t channels = 0;
  std::uint64_t data_offset = 0;  // Absolute byte offset of the DSD/DST chunk.
  std::uint64_t data_size = 0;    // Chunk payload size in bytes.
  bool dst_compressed = false;    // DST-compressed audio is not decoded yet.
};

// Parses the FRM8/DSDIFF header. The stream is rewound to the beginning.
bool ParseDffHeader(std::istream& input, DffInfo* info, std::string* error);

// Opens [path] and parses its DSDIFF header.
bool ProbeDffFile(const std::string& path, DffInfo* info, std::string* error);

// True when the file starts with an "FRM8" container whose form type is
// "DSD ". Only the signature is checked, not the rest of the header.
bool IsDffFile(const std::string& path);

}  // namespace dsp
