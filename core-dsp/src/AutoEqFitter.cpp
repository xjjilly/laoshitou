// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/AutoEqFitter.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <string>
#include <vector>

#include "dsp/Biquad.h"
#include "dsp/Peq.h"

namespace dsp {
namespace {

constexpr int kFittingSampleRate = 48000;
constexpr double kMinFrequency = 20.0;
constexpr double kMaxFrequency = 20000.0;
constexpr std::size_t kGridPoints = 240;
constexpr std::size_t kBandCount = 12;
constexpr int kMaxIterations = 12;
constexpr double kGainLimitDb = 24.0;
constexpr double kStepLimitDb = 5.0;
constexpr double kDerivativeStepDb = 0.25;
constexpr double kRegularization = 0.05;
constexpr double kMinimumBandGainDb = 0.075;
constexpr double kConvergenceDb = 0.005;

struct BandSpec {
  PeqFilterType type;
  double frequency_hz;
  double q;
};

// AutoEQ-style band layout: shelves at both ends plus ten peaking bands.
constexpr std::array<BandSpec, kBandCount> kBandLayout{{
    {PeqFilterType::kLowShelf, 105.0, 0.70},
    {PeqFilterType::kPeaking, 31.5, 1.41},
    {PeqFilterType::kPeaking, 63.0, 1.41},
    {PeqFilterType::kPeaking, 125.0, 1.41},
    {PeqFilterType::kPeaking, 250.0, 1.41},
    {PeqFilterType::kPeaking, 500.0, 1.41},
    {PeqFilterType::kPeaking, 1000.0, 1.41},
    {PeqFilterType::kPeaking, 2000.0, 1.41},
    {PeqFilterType::kPeaking, 4000.0, 1.41},
    {PeqFilterType::kPeaking, 8000.0, 1.41},
    {PeqFilterType::kPeaking, 16000.0, 1.41},
    {PeqFilterType::kHighShelf, 10000.0, 0.70},
}};

PeqBandConfig MakeBand(const BandSpec& spec, double gain_db, const AutoEqOptions& options) {
  PeqBandConfig band{spec.type, spec.frequency_hz, gain_db, spec.q, true};
  band.order = std::clamp(options.order, 1, 2);
  if (spec.type != PeqFilterType::kPeaking && options.use_slope) {
    band.use_slope = true;
    band.slope = std::clamp(options.slope, 0.05, 1.0);
  }
  return band;
}

bool BuildGrid(const std::vector<FrPoint>& curve, std::vector<double>* frequencies,
               std::vector<double>* target, std::string* error) {
  std::vector<FrPoint> points;
  points.reserve(curve.size());
  for (const FrPoint& point : curve) {
    if (std::isfinite(point.frequency_hz) && std::isfinite(point.gain_db) &&
        point.frequency_hz >= kMinFrequency && point.frequency_hz <= kMaxFrequency) {
      points.push_back(point);
    }
  }

  if (points.size() < 4) {
    if (error != nullptr) {
      *error = "频响曲线的有效点太少（至少需要 4 个 20-20000 Hz 的点）";
    }
    return false;
  }

  std::sort(points.begin(), points.end(), [](const FrPoint& left, const FrPoint& right) {
    return left.frequency_hz < right.frequency_hz;
  });

  std::vector<FrPoint> unique;
  unique.reserve(points.size());
  for (const FrPoint& point : points) {
    if (!unique.empty() && std::abs(point.frequency_hz - unique.back().frequency_hz) < 1.0e-9) {
      unique.back() = point;
    } else {
      unique.push_back(point);
    }
  }

  const double first = unique.front().frequency_hz;
  const double last = unique.back().frequency_hz;
  if (last < first * 2.0) {
    if (error != nullptr) {
      *error = "频响曲线的频率覆盖范围太窄";
    }
    return false;
  }

  frequencies->resize(kGridPoints);
  target->resize(kGridPoints);

  const double log_first = std::log10(first);
  const double log_last = std::log10(last);
  for (std::size_t i = 0; i < kGridPoints; ++i) {
    const double position = static_cast<double>(i) / static_cast<double>(kGridPoints - 1);
    const double frequency = std::pow(10.0, log_first + (log_last - log_first) * position);
    (*frequencies)[i] = frequency;

    const auto upper = std::lower_bound(
        unique.begin(), unique.end(), frequency,
        [](const FrPoint& point, double value) { return point.frequency_hz < value; });
    if (upper == unique.begin()) {
      (*target)[i] = upper->gain_db;
    } else if (upper == unique.end()) {
      (*target)[i] = unique.back().gain_db;
    } else {
      const FrPoint& high = *upper;
      const FrPoint& low = *(upper - 1);
      const double denominator = std::log10(high.frequency_hz) - std::log10(low.frequency_hz);
      const double fraction =
          denominator > 0.0 ? (std::log10(frequency) - std::log10(low.frequency_hz)) / denominator
                            : 0.0;
      (*target)[i] = low.gain_db + (high.gain_db - low.gain_db) * fraction;
    }
  }

  return true;
}

void AccumulateBand(const PeqBandConfig& band, const std::vector<double>& frequencies,
                    std::vector<double>* response) {
  const BiquadCoefficients coefficients = Peq::DesignBand(band, kFittingSampleRate);
  const double order_scale = static_cast<double>(std::clamp(band.order, 1, 2));
  for (std::size_t i = 0; i < frequencies.size(); ++i) {
    (*response)[i] +=
        order_scale * BiquadMagnitudeDb(coefficients, frequencies[i], kFittingSampleRate);
  }
}

void EvaluateModel(const std::array<double, kBandCount>& gains,
                   const std::vector<double>& frequencies, const AutoEqOptions& options,
                   std::vector<double>* response) {
  response->assign(frequencies.size(), 0.0);
  for (std::size_t band = 0; band < kBandCount; ++band) {
    AccumulateBand(MakeBand(kBandLayout[band], gains[band], options), frequencies, response);
  }
}

bool SolveNormalEquations(std::array<std::array<double, kBandCount>, kBandCount> matrix,
                          std::array<double, kBandCount> rhs,
                          std::array<double, kBandCount>* solution) {
  for (std::size_t column = 0; column < kBandCount; ++column) {
    std::size_t pivot = column;
    double pivot_magnitude = std::abs(matrix[column][column]);
    for (std::size_t row = column + 1; row < kBandCount; ++row) {
      const double candidate = std::abs(matrix[row][column]);
      if (candidate > pivot_magnitude) {
        pivot_magnitude = candidate;
        pivot = row;
      }
    }
    if (pivot_magnitude < 1.0e-12) {
      return false;
    }
    if (pivot != column) {
      std::swap(matrix[pivot], matrix[column]);
      std::swap(rhs[pivot], rhs[column]);
    }

    const double diagonal = matrix[column][column];
    for (std::size_t row = column + 1; row < kBandCount; ++row) {
      const double factor = matrix[row][column] / diagonal;
      if (factor == 0.0) {
        continue;
      }
      for (std::size_t k = column; k < kBandCount; ++k) {
        matrix[row][k] -= factor * matrix[column][k];
      }
      rhs[row] -= factor * rhs[column];
    }
  }

  for (std::size_t i = kBandCount; i-- > 0;) {
    double sum = rhs[i];
    for (std::size_t k = i + 1; k < kBandCount; ++k) {
      sum -= matrix[i][k] * (*solution)[k];
    }
    (*solution)[i] = sum / matrix[i][i];
  }
  return true;
}

// --- 16-band visual editor fitting -----------------------------------------

constexpr std::array<double, kEditorBandCount> kEditorFrequencies{{
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
constexpr double kEditorQ = 1.41;
constexpr int kEditorIterations = 8;
constexpr double kEditorDerivativeStepDb = 0.25;
constexpr double kEditorRegularization = 0.05;
constexpr double kEditorGainLimitDb = 18.0;
constexpr double kEditorStepLimitDb = 5.0;

PeqBandConfig MakeEditorBand(std::size_t index, double gain_db) {
  return PeqBandConfig{PeqFilterType::kPeaking, kEditorFrequencies[index], gain_db, kEditorQ, true};
}

void EvaluateEditorModel(const std::array<double, kEditorBandCount>& gains,
                         std::array<double, kEditorBandCount>* response) {
  response->fill(0.0);
  for (std::size_t band = 0; band < kEditorBandCount; ++band) {
    const BiquadCoefficients coefficients =
        Peq::DesignBand(MakeEditorBand(band, gains[band]), kFittingSampleRate);
    for (std::size_t point = 0; point < kEditorBandCount; ++point) {
      (*response)[point] +=
          BiquadMagnitudeDb(coefficients, kEditorFrequencies[point], kFittingSampleRate);
    }
  }
}

bool SolveEditorSystem(std::array<std::array<double, kEditorBandCount>, kEditorBandCount> matrix,
                       std::array<double, kEditorBandCount> rhs,
                       std::array<double, kEditorBandCount>* solution) {
  for (std::size_t column = 0; column < kEditorBandCount; ++column) {
    std::size_t pivot = column;
    double pivot_magnitude = std::abs(matrix[column][column]);
    for (std::size_t row = column + 1; row < kEditorBandCount; ++row) {
      const double candidate = std::abs(matrix[row][column]);
      if (candidate > pivot_magnitude) {
        pivot_magnitude = candidate;
        pivot = row;
      }
    }
    if (pivot_magnitude < 1.0e-12) {
      return false;
    }
    if (pivot != column) {
      std::swap(matrix[pivot], matrix[column]);
      std::swap(rhs[pivot], rhs[column]);
    }
    const double diagonal = matrix[column][column];
    for (std::size_t row = column + 1; row < kEditorBandCount; ++row) {
      const double factor = matrix[row][column] / diagonal;
      if (factor == 0.0) {
        continue;
      }
      for (std::size_t k = column; k < kEditorBandCount; ++k) {
        matrix[row][k] -= factor * matrix[column][k];
      }
      rhs[row] -= factor * rhs[column];
    }
  }
  for (std::size_t i = kEditorBandCount; i-- > 0;) {
    double sum = rhs[i];
    for (std::size_t k = i + 1; k < kEditorBandCount; ++k) {
      sum -= matrix[i][k] * (*solution)[k];
    }
    (*solution)[i] = sum / matrix[i][i];
  }
  return true;
}

}  // namespace

bool FitProfileToEditorBands(const AutoEqProfile& profile,
                             std::array<double, kEditorBandCount>* gains, double* preamp_db,
                             std::string* error) {
  if (gains == nullptr || preamp_db == nullptr) {
    return false;
  }

  std::array<double, kEditorBandCount> target{};
  for (std::size_t point = 0; point < kEditorBandCount; ++point) {
    double value = 0.0;
    for (const PeqBandConfig& band : profile.bands) {
      if (band.enabled) {
        value += Peq::BandResponseDb(band, kEditorFrequencies[point], kFittingSampleRate);
      }
    }
    if (!std::isfinite(value)) {
      if (error != nullptr) {
        *error = "EQ 曲线响应无效";
      }
      return false;
    }
    target[point] = value;
  }

  gains->fill(0.0);
  std::array<double, kEditorBandCount> model{};
  std::array<std::array<double, kEditorBandCount>, kEditorBandCount> normal{};
  std::array<double, kEditorBandCount> rhs{};
  std::array<double, kEditorBandCount> delta{};

  for (int iteration = 0; iteration < kEditorIterations; ++iteration) {
    EvaluateEditorModel(*gains, &model);
    std::array<std::array<double, kEditorBandCount>, kEditorBandCount> jacobian{};
    for (std::size_t point = 0; point < kEditorBandCount; ++point) {
      for (std::size_t band = 0; band < kEditorBandCount; ++band) {
        std::array<double, kEditorBandCount> plus = *gains;
        std::array<double, kEditorBandCount> minus = *gains;
        plus[band] += kEditorDerivativeStepDb;
        minus[band] -= kEditorDerivativeStepDb;
        jacobian[point][band] =
            (Peq::BandResponseDb(MakeEditorBand(band, plus[band]), kEditorFrequencies[point],
                                 kFittingSampleRate) -
             Peq::BandResponseDb(MakeEditorBand(band, minus[band]), kEditorFrequencies[point],
                                 kFittingSampleRate)) /
            (2.0 * kEditorDerivativeStepDb);
      }
    }
    normal = {};
    rhs = {};
    for (std::size_t point = 0; point < kEditorBandCount; ++point) {
      const double residual = target[point] - model[point];
      for (std::size_t row = 0; row < kEditorBandCount; ++row) {
        rhs[row] += jacobian[point][row] * residual;
        for (std::size_t column = 0; column < kEditorBandCount; ++column) {
          normal[row][column] += jacobian[point][row] * jacobian[point][column];
        }
      }
    }
    for (std::size_t band = 0; band < kEditorBandCount; ++band) {
      normal[band][band] += kEditorRegularization;
    }
    delta.fill(0.0);
    if (!SolveEditorSystem(normal, rhs, &delta)) {
      break;
    }
    double largest_step = 0.0;
    for (std::size_t band = 0; band < kEditorBandCount; ++band) {
      const double step = std::clamp(delta[band], -kEditorStepLimitDb, kEditorStepLimitDb);
      (*gains)[band] = std::clamp((*gains)[band] + step, -kEditorGainLimitDb, kEditorGainLimitDb);
      largest_step = std::max(largest_step, std::abs(step));
    }
    if (largest_step < 0.005) {
      break;
    }
  }

  *preamp_db = profile.preamp_db;
  return true;
}

bool FitPeqToCurve(const std::vector<FrPoint>& curve, AutoEqProfile* profile, std::string* error,
                   const AutoEqOptions& options) {
  if (profile == nullptr) {
    return false;
  }

  *profile = AutoEqProfile{};

  std::vector<double> frequencies;
  std::vector<double> target;
  if (!BuildGrid(curve, &frequencies, &target, error)) {
    return false;
  }

  std::array<double, kBandCount> gains{};
  for (std::size_t band = 0; band < kBandCount; ++band) {
    std::size_t nearest = 0;
    double best_distance = std::abs(frequencies[0] - kBandLayout[band].frequency_hz);
    for (std::size_t i = 1; i < frequencies.size(); ++i) {
      const double distance = std::abs(frequencies[i] - kBandLayout[band].frequency_hz);
      if (distance < best_distance) {
        best_distance = distance;
        nearest = i;
      }
    }
    gains[band] = std::clamp(target[nearest], -12.0, 12.0);
  }

  std::vector<double> model(frequencies.size(), 0.0);
  std::vector<double> residual(frequencies.size(), 0.0);
  std::vector<std::array<double, kBandCount>> jacobian(frequencies.size());

  for (int iteration = 0; iteration < kMaxIterations; ++iteration) {
    EvaluateModel(gains, frequencies, options, &model);
    for (std::size_t i = 0; i < frequencies.size(); ++i) {
      residual[i] = target[i] - model[i];
    }

    for (std::size_t band = 0; band < kBandCount; ++band) {
      std::array<double, kBandCount> plus = gains;
      std::array<double, kBandCount> minus = gains;
      plus[band] += kDerivativeStepDb;
      minus[band] -= kDerivativeStepDb;
      const BiquadCoefficients plus_coefficients =
          Peq::DesignBand(MakeBand(kBandLayout[band], plus[band], options), kFittingSampleRate);
      const BiquadCoefficients minus_coefficients =
          Peq::DesignBand(MakeBand(kBandLayout[band], minus[band], options), kFittingSampleRate);
      const double order_scale = static_cast<double>(std::clamp(options.order, 1, 2));
      for (std::size_t i = 0; i < frequencies.size(); ++i) {
        const double difference =
            order_scale *
            (BiquadMagnitudeDb(plus_coefficients, frequencies[i], kFittingSampleRate) -
             BiquadMagnitudeDb(minus_coefficients, frequencies[i], kFittingSampleRate));
        jacobian[i][band] = difference / (2.0 * kDerivativeStepDb);
      }
    }

    std::array<std::array<double, kBandCount>, kBandCount> normal{};
    std::array<double, kBandCount> rhs{};
    for (std::size_t i = 0; i < frequencies.size(); ++i) {
      for (std::size_t row = 0; row < kBandCount; ++row) {
        rhs[row] += jacobian[i][row] * residual[i];
        for (std::size_t column = 0; column < kBandCount; ++column) {
          normal[row][column] += jacobian[i][row] * jacobian[i][column];
        }
      }
    }
    for (std::size_t band = 0; band < kBandCount; ++band) {
      normal[band][band] += kRegularization;
    }

    std::array<double, kBandCount> delta{};
    if (!SolveNormalEquations(normal, rhs, &delta)) {
      break;
    }

    double largest_step = 0.0;
    for (std::size_t band = 0; band < kBandCount; ++band) {
      const double step = std::clamp(delta[band], -kStepLimitDb, kStepLimitDb);
      gains[band] = std::clamp(gains[band] + step, -kGainLimitDb, kGainLimitDb);
      largest_step = std::max(largest_step, std::abs(step));
    }
    if (largest_step < kConvergenceDb) {
      break;
    }
  }

  for (std::size_t band = 0; band < kBandCount; ++band) {
    if (std::abs(gains[band]) >= kMinimumBandGainDb) {
      profile->bands.push_back(MakeBand(kBandLayout[band], gains[band], options));
    }
  }

  std::vector<double> fitted(frequencies.size(), 0.0);
  for (const PeqBandConfig& band : profile->bands) {
    AccumulateBand(band, frequencies, &fitted);
  }
  double peak_gain = 0.0;
  for (const double value : fitted) {
    peak_gain = std::max(peak_gain, value);
  }
  profile->preamp_db = -peak_gain;
  return true;
}

}  // namespace dsp
