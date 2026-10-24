// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <string>
#include <vector>

#include "dsp/Peq.h"

namespace dsp {

struct AutoEqProfile {
  double preamp_db = 0.0;
  std::vector<PeqBandConfig> bands;
};

// Global overrides used when loading a profile and when fitting a response
// curve. The default preserves the file's own settings.
struct AutoEqOptions {
  // 1 = one biquad per band (2nd order), 2 = cascaded pair (4th order).
  int order = 1;
  // When true, shelf bands use the RBJ slope S instead of Q.
  bool use_slope = false;
  double slope = 1.0;
};

// Parses an AutoEQ-style correction profile. Supported inputs:
//
//   ParametricEQ.txt / FixedBandEQ.txt:
//     Preamp: -6.0 dB
//     Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.0
//     Filter 2: ON LSC Fc 100 Hz Gain 3.0 dB Q 0.7
//     Filter 3: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.7
//
//   Parametric CSV (header names are flexible):
//     Type,Frequency,Gain,Q,Enabled
//     PK,1000,6,1.0,true
//     LS,100,3,0.7,on
//     HS,10000,-2,0.7,1
//
//   Response CSV (AutoEQ measurement exports or any frequency/gain curve):
//     frequency,raw,smoothed,error,equalization
//     ...
//   The equalization column is used directly, error is negated, and a plain
//   frequency,gain pair is treated as the correction curve.
//
//   GraphicEQ text:
//     GraphicEQ: 20 -3.0; 21 -3.1; ...; 20000 -1.0
//
// Response curves are fitted to a 12-band PK/LSC/HSC profile. Parametric
// inputs are used as-is (up to the engine's 16-band limit).
bool ParseAutoEqProfile(const std::string& text, AutoEqProfile* profile, std::string* error,
                        const AutoEqOptions& options = AutoEqOptions{});

bool LoadAutoEqProfile(const std::string& path, AutoEqProfile* profile, std::string* error,
                       const AutoEqOptions& options = AutoEqOptions{});

}  // namespace dsp
