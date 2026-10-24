// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cstdint>
#include <iostream>
#include <sstream>
#include <string>

#include "dsp/DffReader.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

void AppendFourCc(std::string* out, const char* value) { out->append(value, 4); }

void AppendU16Be(std::string* out, std::uint16_t value) {
  out->push_back(static_cast<char>((value >> 8) & 0xFF));
  out->push_back(static_cast<char>(value & 0xFF));
}

void AppendU32Be(std::string* out, std::uint32_t value) {
  for (int shift = 24; shift >= 0; shift -= 8) {
    out->push_back(static_cast<char>((value >> shift) & 0xFF));
  }
}

void AppendU64Be(std::string* out, std::uint64_t value) {
  for (int shift = 56; shift >= 0; shift -= 8) {
    out->push_back(static_cast<char>((value >> shift) & 0xFF));
  }
}

void AppendChunk(std::string* out, const char* id, const std::string& payload) {
  AppendFourCc(out, id);
  AppendU64Be(out, payload.size());
  out->append(payload);
  if ((payload.size() & 1U) != 0U) {
    out->push_back('\0');
  }
}

std::string BuildDff(bool dst_compressed) {
  std::string prop;
  AppendFourCc(&prop, "SND ");

  std::string fs;
  AppendU32Be(&fs, 2822400);
  AppendChunk(&prop, "FS  ", fs);

  std::string channels;
  AppendU16Be(&channels, 2);
  AppendFourCc(&channels, "SLFT");
  AppendFourCc(&channels, "SRGT");
  AppendChunk(&prop, "CHNL", channels);

  std::string compression;
  AppendFourCc(&compression, dst_compressed ? "DST " : "DSD ");
  compression.push_back(14);  // Pascal string: "not compressed"
  compression.append("not compressed");
  compression.push_back('\0');
  AppendChunk(&prop, "CMPR", compression);

  std::string file;
  AppendFourCc(&file, "FRM8");
  AppendU64Be(&file, 0);
  AppendFourCc(&file, "DSD ");

  std::string version;
  AppendU32Be(&version, 0x01050000);
  AppendChunk(&file, "FVER", version);
  AppendChunk(&file, "PROP", prop);

  AppendFourCc(&file, dst_compressed ? "DST " : "DSD ");
  AppendU64Be(&file, 8);
  file.append(8, '\x69');
  return file;
}

void TestParseUncompressedDff() {
  const std::string bytes = BuildDff(false);
  std::istringstream input(bytes);

  dsp::DffInfo info;
  std::string error;
  CHECK(dsp::ParseDffHeader(input, &info, &error));
  CHECK(error.empty());
  CHECK(info.sample_rate_hz == 2822400U);
  CHECK(info.channels == 2U);
  CHECK(!info.dst_compressed);
  CHECK(info.data_size == 8U);
  CHECK(info.data_offset > 0U);
  CHECK(info.data_offset + info.data_size == bytes.size());
}

void TestParseDstDff() {
  const std::string bytes = BuildDff(true);
  std::istringstream input(bytes);

  dsp::DffInfo info;
  std::string error;
  CHECK(dsp::ParseDffHeader(input, &info, &error));
  CHECK(info.dst_compressed);
  CHECK(info.sample_rate_hz == 2822400U);
  CHECK(info.data_size == 8U);
}

void TestRejectsInvalidHeader() {
  std::istringstream input(std::string("NOPE0000000000000000"));
  dsp::DffInfo info;
  std::string error;
  CHECK(!dsp::ParseDffHeader(input, &info, &error));
  CHECK(!error.empty());
}

void TestRejectsMissingChannelInfo() {
  std::string file;
  AppendFourCc(&file, "FRM8");
  AppendU64Be(&file, 0);
  AppendFourCc(&file, "DSD ");

  std::string prop;
  AppendFourCc(&prop, "SND ");
  std::string fs;
  AppendU32Be(&fs, 2822400);
  AppendChunk(&prop, "FS  ", fs);
  AppendChunk(&file, "PROP", prop);

  AppendFourCc(&file, "DSD ");
  AppendU64Be(&file, 8);
  file.append(8, '\x69');

  std::istringstream input(file);
  dsp::DffInfo info;
  std::string error;
  CHECK(!dsp::ParseDffHeader(input, &info, &error));
  CHECK(!error.empty());
}

}  // namespace

int main() {
  TestParseUncompressedDff();
  TestParseDstDff();
  TestRejectsInvalidHeader();
  TestRejectsMissingChannelInfo();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
