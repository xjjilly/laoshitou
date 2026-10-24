// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <string>
#include <vector>

#include "dsp/AutoEqFitter.h"
#include "dsp/Biquad.h"
#include "dsp/Peq.h"

namespace {

int g_failures = 0;

#define CHECK(condition)                                                                \
  do {                                                                                  \
    if (!(condition)) {                                                                 \
      std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #condition << "\n"; \
      ++g_failures;                                                                     \
    }                                                                                   \
  } while (false)

constexpr int kRate = 48000;

const std::vector<dsp::PeqBandConfig> kTargetBands{
    {dsp::PeqFilterType::kLowShelf, 100.0, 5.0, 0.7, true},
    {dsp::PeqFilterType::kPeaking, 1000.0, -4.0, 1.2, true},
    {dsp::PeqFilterType::kHighShelf, 10000.0, -3.0, 0.7, true},
};

double TargetAt(double frequency_hz) {
  double gain = 0.0;
  for (const dsp::PeqBandConfig& band : kTargetBands) {
    gain += dsp::Peq::BandResponseDb(band, frequency_hz, kRate);
  }
  return gain;
}

double FittedResponse(const dsp::AutoEqProfile& profile, double frequency_hz) {
  double gain = 0.0;
  for (const dsp::PeqBandConfig& band : profile.bands) {
    gain += dsp::Peq::BandResponseDb(band, frequency_hz, kRate);
  }
  return gain;
}

void TestBandMagnitudeResponse() {
  const dsp::PeqBandConfig peak{dsp::PeqFilterType::kPeaking, 1000.0, 6.0, 1.0, true};
  CHECK(std::abs(dsp::Peq::BandResponseDb(peak, 1000.0, kRate) - 6.0) < 0.01);
  CHECK(std::abs(dsp::Peq::BandResponseDb(peak, 20.0, kRate)) < 0.05);

  const dsp::PeqBandConfig low_shelf{dsp::PeqFilterType::kLowShelf, 100.0, 6.0, 0.7, true};
  CHECK(dsp::Peq::BandResponseDb(low_shelf, 20.0, kRate) > 5.5);
  CHECK(std::abs(dsp::Peq::BandResponseDb(low_shelf, 10000.0, kRate)) < 0.1);

  const dsp::PeqBandConfig high_shelf{dsp::PeqFilterType::kHighShelf, 10000.0, -6.0, 0.7, true};
  CHECK(dsp::Peq::BandResponseDb(high_shelf, 20000.0, kRate) < -5.5);
  CHECK(std::abs(dsp::Peq::BandResponseDb(high_shelf, 100.0, kRate)) < 0.1);
}

std::vector<dsp::FrPoint> BuildTargetCurve() {
  constexpr std::size_t kPoints = 241;
  std::vector<dsp::FrPoint> curve;
  curve.reserve(kPoints);
  for (std::size_t i = 0; i < kPoints; ++i) {
    const double position = static_cast<double>(i) / static_cast<double>(kPoints - 1);
    const double frequency = 20.0 * std::pow(1000.0, position);
    curve.push_back(dsp::FrPoint{frequency, TargetAt(frequency)});
  }
  return curve;
}

void TestFitRoundTrip() {
  const std::vector<dsp::FrPoint> curve = BuildTargetCurve();

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::FitPeqToCurve(curve, &profile, &error));
  CHECK(error.empty());
  CHECK(!profile.bands.empty());
  CHECK(profile.bands.size() <= 16U);
  CHECK(profile.preamp_db <= 0.01);
  CHECK(profile.preamp_db >= -24.0);

  constexpr std::size_t kChecks = 200;
  double sum_squared = 0.0;
  double max_error = 0.0;
  for (std::size_t i = 0; i < kChecks; ++i) {
    const double position = static_cast<double>(i) / static_cast<double>(kChecks - 1);
    const double frequency = 20.0 * std::pow(1000.0, position);
    const double error_db = TargetAt(frequency) - FittedResponse(profile, frequency);
    sum_squared += error_db * error_db;
    max_error = std::max(max_error, std::abs(error_db));
  }
  const double rms = std::sqrt(sum_squared / static_cast<double>(kChecks));
  CHECK(rms < 1.0);
  CHECK(max_error < 2.5);
}

void TestFitOrderTwoRoundTrip() {
  std::vector<dsp::PeqBandConfig> target_bands = kTargetBands;
  for (dsp::PeqBandConfig& band : target_bands) {
    band.order = 2;
  }

  std::vector<dsp::FrPoint> curve;
  constexpr std::size_t kPoints = 241;
  curve.reserve(kPoints);
  for (std::size_t i = 0; i < kPoints; ++i) {
    const double position = static_cast<double>(i) / static_cast<double>(kPoints - 1);
    const double frequency = 20.0 * std::pow(1000.0, position);
    double gain = 0.0;
    for (const dsp::PeqBandConfig& band : target_bands) {
      gain += dsp::Peq::BandResponseDb(band, frequency, kRate);
    }
    curve.push_back(dsp::FrPoint{frequency, gain});
  }

  dsp::AutoEqOptions options;
  options.order = 2;
  options.use_slope = true;
  options.slope = 1.0;

  dsp::AutoEqProfile profile;
  std::string error;
  CHECK(dsp::FitPeqToCurve(curve, &profile, &error, options));
  CHECK(!profile.bands.empty());
  for (const dsp::PeqBandConfig& band : profile.bands) {
    CHECK(band.order == 2);
    if (band.type != dsp::PeqFilterType::kPeaking) {
      CHECK(band.use_slope);
    }
  }

  double sum_squared = 0.0;
  double max_error = 0.0;
  for (std::size_t i = 0; i < kPoints; ++i) {
    const double position = static_cast<double>(i) / static_cast<double>(kPoints - 1);
    const double frequency = 20.0 * std::pow(1000.0, position);
    double target = 0.0;
    for (const dsp::PeqBandConfig& band : target_bands) {
      target += dsp::Peq::BandResponseDb(band, frequency, kRate);
    }
    const double error_db = target - FittedResponse(profile, frequency);
    sum_squared += error_db * error_db;
    max_error = std::max(max_error, std::abs(error_db));
  }
  CHECK(std::sqrt(sum_squared / static_cast<double>(kPoints)) < 1.5);
  CHECK(max_error < 3.0);
}

void TestFitProfileToEditorBands() {
  dsp::AutoEqProfile profile;
  profile.preamp_db = -3.0;
  profile.bands = {
      {dsp::PeqFilterType::kLowShelf, 100.0, 5.0, 0.7, true},
      {dsp::PeqFilterType::kPeaking, 1000.0, -4.0, 1.2, true},
      {dsp::PeqFilterType::kHighShelf, 10000.0, -3.0, 0.7, true},
  };

  std::array<double, dsp::kEditorBandCount> gains{};
  double preamp = 0.0;
  CHECK(dsp::FitProfileToEditorBands(profile, &gains, &preamp, nullptr));
  CHECK(std::abs(preamp + 3.0) < 1.0e-9);

  const std::array<double, dsp::kEditorBandCount> frequencies{{
      25.0,
      40.0,
      63.0,
      100.0,
      160.0,
      250.0,
      400.0,
      630.0,
      1000.0,
      1600.0,
      2500.0,
      4000.0,
      6300.0,
      10000.0,
      16000.0,
      20000.0,
  }};
  double max_error = 0.0;
  for (std::size_t point = 0; point < dsp::kEditorBandCount; ++point) {
    double target = 0.0;
    for (const dsp::PeqBandConfig& band : profile.bands) {
      target += dsp::Peq::BandResponseDb(band, frequencies[point], kRate);
    }
    double model = 0.0;
    for (std::size_t band = 0; band < dsp::kEditorBandCount; ++band) {
      const dsp::PeqBandConfig editor{dsp::PeqFilterType::kPeaking, frequencies[band], gains[band],
                                      1.41, true};
      model += dsp::Peq::BandResponseDb(editor, frequencies[point], kRate);
    }
    max_error = std::max(max_error, std::abs(target - model));
  }
  CHECK(max_error < 2.5);
  for (const double gain : gains) {
    CHECK(std::isfinite(gain));
  }
}

void TestRejectsTinyCurve() {
  dsp::AutoEqProfile profile;
  std::string error;
  const std::vector<dsp::FrPoint> curve{
      {100.0, 1.0},
      {200.0, 0.5},
  };
  CHECK(!dsp::FitPeqToCurve(curve, &profile, &error));
  CHECK(!error.empty());
}

}  // namespace

int main() {
  TestBandMagnitudeResponse();
  TestFitRoundTrip();
  TestFitOrderTwoRoundTrip();
  TestFitProfileToEditorBands();
  TestRejectsTinyCurve();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
