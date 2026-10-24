// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace dsp {

// A two-channel impulse response (mono files are duplicated to both ears).
struct IrData {
  int sample_rate = 0;
  std::size_t frames = 0;
  std::vector<float> left;
  std::vector<float> right;
};

// Loads a PCM 16/24/32-bit or IEEE float WAV impulse response. Only mono and
// stereo files are accepted. This is a control-thread operation.
bool LoadIrWav(const std::string& path, IrData* ir, std::string* error);

}  // namespace dsp
