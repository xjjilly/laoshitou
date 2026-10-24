// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <cmath>
#include <iostream>
#include <string>

#include "dsp/AutoEqProfile.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

void TestParseValidProfile() {
  const std::string text =
      "Preamp: -6.0 dB\n"
      "Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.0\n"
      "Filter 2: ON LSC Fc 100 Hz Gain 3.0 dB Q 0.7\n"
      "Filter 3: OFF HSC Fc 10000 Hz Gain -2.0 dB Q 0.7\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(error.empty());
  CHECK(std::abs(profile.preamp_db + 6.0) < 1.0e-9);
  CHECK(profile.bands.size() == 3U);

  CHECK(profile.bands[0].type == dsp::PeqFilterType::kPeaking);
  CHECK(profile.bands[0].enabled);
  CHECK(std::abs(profile.bands[0].frequency - 1000.0) < 1.0e-9);
  CHECK(std::abs(profile.bands[0].gain_db - 6.0) < 1.0e-9);
  CHECK(std::abs(profile.bands[0].q - 1.0) < 1.0e-9);

  CHECK(profile.bands[1].type == dsp::PeqFilterType::kLowShelf);
  CHECK(profile.bands[1].enabled);
  CHECK(std::abs(profile.bands[1].frequency - 100.0) < 1.0e-9);

  CHECK(profile.bands[2].type == dsp::PeqFilterType::kHighShelf);
  CHECK(!profile.bands[2].enabled);
  CHECK(std::abs(profile.bands[2].gain_db + 2.0) < 1.0e-9);
}

void TestInvalidLines() {
  dsp::AutoEqProfile profile;
  std::string error;

  CHECK(!dsp::ParseAutoEqProfile("Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB\n", &profile, &error));
  CHECK(!error.empty());

  CHECK(!dsp::ParseAutoEqProfile("Filter 1: ON XYZ Fc 1000 Hz Gain 6.0 dB Q 1.0\n", &profile,
                                 &error));
  CHECK(!error.empty());

  CHECK(!dsp::ParseAutoEqProfile("Preamp: not-a-number dB\n", &profile, &error));
  CHECK(!error.empty());
}

void TestParseParametricCsv() {
  const std::string text =
      "Type,Frequency,Gain,Q,Enabled\n"
      "PK,1000,6.0,1.0,true\n"
      "LS,100,3.0,0.7,on\n"
      "HS,10000,-2.0,0.7,1\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(error.empty());
  CHECK(profile.bands.size() == 3U);
  CHECK(profile.bands[0].type == dsp::PeqFilterType::kPeaking);
  CHECK(profile.bands[1].type == dsp::PeqFilterType::kLowShelf);
  CHECK(profile.bands[2].type == dsp::PeqFilterType::kHighShelf);
  CHECK(profile.bands[0].enabled);
  CHECK(profile.bands[1].enabled);
  CHECK(profile.bands[2].enabled);
  CHECK(std::abs(profile.bands[1].frequency - 100.0) < 1.0e-9);
  CHECK(std::abs(profile.bands[1].gain_db - 3.0) < 1.0e-9);
  CHECK(std::abs(profile.bands[1].q - 0.7) < 1.0e-9);
}

void TestParseFilterIndexCsv() {
  const std::string text =
      "Filter,Type,Fc,Gain,Q\n"
      "1,PK,1000,6.0,1.0\n"
      "2,LS,100,3.0,0.7\n"
      "3,HS,10000,-2.0,0.7\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(profile.bands.size() == 3U);
  CHECK(profile.bands[1].type == dsp::PeqFilterType::kLowShelf);
  CHECK(profile.bands[2].type == dsp::PeqFilterType::kHighShelf);
}

void TestParseSlopeAndOrderText() {
  const std::string text =
      "Preamp: -2.0 dB\n"
      "Filter 1: ON LSC Fc 100 Hz Gain 6.0 dB Slope 0.8 Order 2\n"
      "Filter 2: ON PK Fc 1000 Hz Gain -3.0 dB Q 1.2\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(profile.bands.size() == 2U);
  CHECK(profile.bands[0].use_slope);
  CHECK(std::abs(profile.bands[0].slope - 0.8) < 1.0e-9);
  CHECK(profile.bands[0].order == 2);
  CHECK(!profile.bands[1].use_slope);
  CHECK(profile.bands[1].order == 1);
}

void TestParseSlopeOrderCsv() {
  const std::string text =
      "Type,Frequency,Gain,Q,Slope,Order\n"
      "LSC,100,6,0.7,0.5,2\n"
      "PK,1000,-3,1.2,,1\n"
      "HSC,10000,-2,,1.0,2\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(profile.bands.size() == 3U);
  CHECK(profile.bands[0].use_slope);
  CHECK(std::abs(profile.bands[0].slope - 0.5) < 1.0e-9);
  CHECK(profile.bands[0].order == 2);
  CHECK(!profile.bands[1].use_slope);
  CHECK(profile.bands[1].order == 1);
  CHECK(profile.bands[2].use_slope);
  CHECK(std::abs(profile.bands[2].slope - 1.0) < 1.0e-9);
  CHECK(profile.bands[2].order == 2);
}

void TestOptionsOverride() {
  const std::string text =
      "Filter 1: ON LSC Fc 100 Hz Gain 3.0 dB Q 0.7\n"
      "Filter 2: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.0\n";

  dsp::AutoEqOptions options;
  options.order = 2;
  options.use_slope = true;
  options.slope = 0.6;

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error, options));
  CHECK(profile.bands.size() == 2U);
  CHECK(profile.bands[0].order == 2);
  CHECK(profile.bands[1].order == 2);
  CHECK(profile.bands[0].use_slope);
  CHECK(std::abs(profile.bands[0].slope - 0.6) < 1.0e-9);
  CHECK(!profile.bands[1].use_slope);
}

void TestParseHighPassFilter() {
  const std::string text =
      "Filter 1: ON HPF Fc 20 Hz Q 0.707\n"
      "Filter 2: ON LP Fc 18000 Hz Q 0.707\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(profile.bands.size() == 2U);
  CHECK(profile.bands[0].type == dsp::PeqFilterType::kHighPass);
  CHECK(profile.bands[1].type == dsp::PeqFilterType::kLowPass);
}

void TestParseNoHeaderParametricCsv() {
  const std::string text =
      "PK,1000,6.0,1.0\n"
      "LSC,100,3.0,0.7\n"
      "HSC,10000,-2.0,0.7\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(profile.bands.size() == 3U);
  CHECK(profile.bands[1].type == dsp::PeqFilterType::kLowShelf);
  CHECK(profile.bands[2].type == dsp::PeqFilterType::kHighShelf);
}

const char* kResponseRows =
    "20,-4.0\n"
    "30,-3.8\n"
    "50,-3.2\n"
    "80,-2.5\n"
    "125,-1.8\n"
    "200,-1.0\n"
    "315,-0.4\n"
    "500,0.0\n"
    "800,0.4\n"
    "1250,0.2\n"
    "2000,-0.6\n"
    "3150,-1.6\n"
    "5000,-2.4\n"
    "8000,-3.0\n"
    "12500,-3.2\n"
    "20000,-3.4\n";

void TestParseResponseCsv() {
  const std::string text = std::string("frequency,gain\n") + kResponseRows;

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(error.empty());
  CHECK(!profile.bands.empty());
  CHECK(profile.bands.size() <= 16U);
  CHECK(profile.preamp_db <= 0.0);
  CHECK(profile.preamp_db >= -24.0);
  for (const dsp::PeqBandConfig& band : profile.bands) {
    CHECK(std::isfinite(band.frequency));
    CHECK(std::isfinite(band.gain_db));
    CHECK(std::isfinite(band.q));
    CHECK(band.q > 0.0);
  }
}

void TestParseAutoEqMeasurementCsv() {
  std::string text = "frequency,raw,smoothed,error,equalization\n";
  text += "20,4,4,4,-4\n";
  text += "50,2,2,2,-2\n";
  text += "100,0,0,0,0\n";
  text += "200,-1,-1,-1,1\n";
  text += "500,-2,-2,-2,2\n";
  text += "1000,-1,-1,-1,1\n";
  text += "2000,0,0,0,0\n";
  text += "5000,1,1,1,-1\n";
  text += "10000,2,2,2,-2\n";
  text += "20000,3,3,3,-3\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(!profile.bands.empty());
}

void TestParseErrorCsv() {
  std::string text = "frequency,error\n";
  text += "20,4\n50,2\n100,0\n200,-1\n500,-2\n";
  text += "1000,-1\n2000,0\n5000,1\n10000,2\n20000,3\n";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(!profile.bands.empty());
}

void TestParseGraphicEq() {
  const std::string text =
      "GraphicEQ: 20 -4.0; 50 -3.2; 100 -2.0; 200 -1.0; 500 0.0; "
      "1000 0.0; 2000 -0.6; 5000 -2.4; 10000 -3.0; 20000 -3.4";

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::ParseAutoEqProfile(text, &profile, &error));
  CHECK(!profile.bands.empty());
}

void TestInvalidCsv() {
  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(!dsp::ParseAutoEqProfile("frequency,raw,smoothed\n20,0,0\n100,0,0\n1000,0,0\n10000,0,0\n",
                                 &profile, &error));
  CHECK(!error.empty());
}

}  // namespace

int main() {
  TestParseValidProfile();
  TestInvalidLines();
  TestParseParametricCsv();
  TestParseFilterIndexCsv();
  TestParseSlopeAndOrderText();
  TestParseSlopeOrderCsv();
  TestOptionsOverride();
  TestParseHighPassFilter();
  TestParseNoHeaderParametricCsv();
  TestParseResponseCsv();
  TestParseAutoEqMeasurementCsv();
  TestParseErrorCsv();
  TestParseGraphicEq();
  TestInvalidCsv();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
