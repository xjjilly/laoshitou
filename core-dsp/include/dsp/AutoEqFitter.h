// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <array>
#include <string>
#include <vector>

#include "dsp/AutoEqProfile.h"

namespace dsp {

// One point of a frequency-response correction curve. Frequencies are in Hz
// and gains are the correction in dB.
struct FrPoint {
  double frequency_hz = 0.0;
  double gain_db = 0.0;
};

// Fits a fixed-band PK/LSC/HSC AutoEQ profile to [curve].
//
// The curve is interpolated on a 1/24-octave log grid between 20 Hz and
// 20 kHz. The fit uses a low/high shelf pair, ten peaking bands and a
// damped Gauss-Newton solve for the band gains (Fc and Q stay fixed). The
// preamp is set to the negative of the fitted peak gain so boosted regions
// cannot clip. Bands whose final gain is below 0.075 dB are dropped.
// [options] selects 2nd/4th-order bands (cascade) and shelf slope vs Q for
// the fitted profile.
bool FitPeqToCurve(const std::vector<FrPoint>& curve, AutoEqProfile* profile, std::string* error,
                   const AutoEqOptions& options = AutoEqOptions{});

// Fits a loaded profile to the app's fixed 16-band visual editor (peaking
// bands at 25 Hz..20 kHz, Q=1.41). The fitted gains approximate the profile's
// response at the 16 band centers; the profile preamp is returned separately.
constexpr std::size_t kEditorBandCount = 16;
bool FitProfileToEditorBands(const AutoEqProfile& profile,
                             std::array<double, kEditorBandCount>* gains, double* preamp_db,
                             std::string* error);

}  // namespace dsp
