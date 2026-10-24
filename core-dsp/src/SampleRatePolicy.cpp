// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/SampleRatePolicy.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <limits>

namespace dsp {
namespace {

constexpr std::array<int, 3> k44Ladder{44100, 88200, 176400};
constexpr std::array<int, 3> k48Ladder{48000, 96000, 192000};

[[nodiscard]] bool Contains(const std::vector<int>& rates, int rate) noexcept {
  return std::find(rates.begin(), rates.end(), rate) != rates.end();
}

[[nodiscard]] int AbsDiff(int a, int b) noexcept {
  return a >= b ? a - b : b - a;
}

[[nodiscard]] int MaxRate(const std::vector<int>& rates) noexcept {
  int max_rate = 0;
  for (const int rate : rates) {
    if (rate > max_rate) {
      max_rate = rate;
    }
  }
  return max_rate;
}

[[nodiscard]] int ClosestRate(const std::vector<int>& rates, int rate) noexcept {
  int best = 0;
  int best_distance = std::numeric_limits<int>::max();
  for (const int candidate : rates) {
    if (candidate <= 0) {
      continue;
    }
    const int distance = AbsDiff(candidate, rate);
    if (distance < best_distance) {
      best_distance = distance;
      best = candidate;
    }
  }
  return best;
}

}  // namespace

RateFamily ClassifyRateFamily(int rate) noexcept {
  if (rate <= 0) {
    return RateFamily::kUnknown;
  }
  if (rate % 11025 == 0) {
    return RateFamily::k44k;
  }
  if (rate % 8000 == 0 || rate % 12000 == 0) {
    return RateFamily::k48k;
  }
  return RateFamily::kUnknown;
}

std::span<const int> FamilyLadder(RateFamily family) noexcept {
  switch (family) {
    case RateFamily::k44k:
      return std::span<const int>(k44Ladder.data(), k44Ladder.size());
    case RateFamily::k48k:
      return std::span<const int>(k48Ladder.data(), k48Ladder.size());
    case RateFamily::kUnknown:
      break;
  }
  return {};
}

bool IsIntegerRatio(int a, int b) noexcept {
  if (a <= 0 || b <= 0) {
    return false;
  }
  return (a % b == 0) || (b % a == 0);
}

RatePlan MakeRatePlan(int source_rate, OutputMode mode,
                      const DeviceRates& device) noexcept {
  RatePlan plan;
  plan.source_rate = source_rate;
  plan.family = ClassifyRateFamily(source_rate);

  const std::vector<int>& supported = device.supported_rates;
  const bool have_supported = !supported.empty();

  // A rate is usable when the platform reported it or when it is the device
  // native rate. Native rate is authoritative even if the list is incomplete.
  const auto is_supported = [&](int rate) {
    if (rate <= 0) {
      return false;
    }
    if (rate == device.native_rate) {
      return true;
    }
    return have_supported && Contains(supported, rate);
  };

  const auto finish = [&](int target) {
    plan.target_rate = target;
    plan.needs_resample =
        source_rate > 0 && target > 0 && target != source_rate;
    plan.cross_family_fallback =
        plan.family != RateFamily::kUnknown && target > 0 &&
        ClassifyRateFamily(target) != plan.family;
    plan.non_integer_ratio =
        plan.needs_resample && !IsIntegerRatio(source_rate, target);
    plan.device_limited =
        source_rate > 0 && target > 0 && target < source_rate;
  };

  if (mode == OutputMode::kDirect) {
    int target = source_rate;
    if (source_rate <= 0) {
      target = device.native_rate;
    } else if (!is_supported(source_rate)) {
      if (device.native_rate > 0 &&
          ClassifyRateFamily(device.native_rate) == plan.family) {
        target = device.native_rate;
      } else if (have_supported) {
        target = ClosestRate(supported, source_rate);
      } else if (device.native_rate > 0) {
        target = device.native_rate;
      }
    }
    finish(target);
    return plan;
  }

  if (plan.family == RateFamily::kUnknown) {
    int target = device.native_rate;
    if (target <= 0) {
      target = MaxRate(supported);
    }
    if (target <= 0) {
      target = source_rate;
    }
    finish(target);
    return plan;
  }

  // Walk the ladder from the highest rate down. A candidate is eligible only
  // when the device supports it and the ratio with the source is an exact
  // integer. This is what keeps 44.1k and 48k families separate.
  const std::span<const int> ladder = FamilyLadder(plan.family);
  int target = 0;
  for (std::size_t i = ladder.size(); i > 0; --i) {
    const int candidate = ladder[i - 1];
    if (!is_supported(candidate)) {
      continue;
    }
    if (IsIntegerRatio(source_rate, candidate)) {
      target = candidate;
      break;
    }
  }

  if (target == 0) {
    // No same-family target. Prefer the closest reported rate, which keeps a
    // 44.1-family source on 48k rather than jumping to 96k when both are
    // available. Only fall back to the native rate when the platform did not
    // enumerate any rates.
    if (have_supported) {
      target = ClosestRate(supported, source_rate);
    } else if (device.native_rate > 0) {
      target = device.native_rate;
    } else {
      target = source_rate;
    }
  }

  finish(target);
  return plan;
}

}  // namespace dsp
