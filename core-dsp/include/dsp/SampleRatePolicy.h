// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace dsp {

enum class RateFamily : std::uint8_t {
  kUnknown = 0,
  k44k,
  k48k,
};

enum class OutputMode : std::uint8_t {
  // Bit-perfect: the caller bypasses the DSP chain.
  kDirect = 0,
  // DSP active: integer-multiple upsample within the source rate family.
  kProcessing,
};

struct DeviceRates {
  // Rates reported by AudioDeviceInfo.getSampleRates() for the active output
  // route. May be empty when the platform cannot enumerate them.
  std::vector<int> supported_rates;
  // AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE. Zero when unknown.
  int native_rate = 0;
};

struct RatePlan {
  int source_rate = 0;
  int target_rate = 0;
  RateFamily family = RateFamily::kUnknown;
  bool needs_resample = false;
  // True only when the device cannot provide any rate in the source family.
  // The caller must surface this to the user. It must never be the default
  // path for 44.1-family content.
  bool cross_family_fallback = false;
  // True when source -> target is not an integer ratio. The 44.1k -> 96k
  // conversion is exactly what the policy avoids; this flag exists so the UI
  // can warn if the hardware forces such a conversion.
  bool non_integer_ratio = false;
  // True when the device forced a lower target than the source rate.
  bool device_limited = false;
};

// Classifies a sample rate into the 44.1 kHz or 48 kHz family using integer
// divisibility rather than a closed enumeration. Returns kUnknown for rates
// that belong to neither family (for example 44101).
[[nodiscard]] RateFamily ClassifyRateFamily(int rate) noexcept;

// Returns the hard-coded family ladder:
//   k44k -> {44100, 88200, 176400}
//   k48k -> {48000, 96000, 192000}
// Returns an empty span for kUnknown.
[[nodiscard]] std::span<const int> FamilyLadder(RateFamily family) noexcept;

// True when a/b or b/a is an exact integer. Used to reject 44.1k -> 96k.
[[nodiscard]] bool IsIntegerRatio(int a, int b) noexcept;

// Chooses the output target rate.
//
// kDirect: target = source rate when the device supports it. If not, prefer
// the device native rate in the same family, otherwise the closest reported
// rate. The caller bypasses the DSP chain in this mode.
//
// kProcessing: target = the highest supported rate in the source family's
// ladder that has an integer ratio with the source. Both 44.1k and 88.2k go
// to 176.4k when available; both 48k and 96k go to 192k. If no same-family
// target exists, fall back to the device native rate and set
// cross_family_fallback; the caller must warn. The function never selects the
// opposite family's highest ladder rate merely because it is supported
// (44.1k is never turned into 96k by default).
[[nodiscard]] RatePlan MakeRatePlan(int source_rate, OutputMode mode,
                                    const DeviceRates& device) noexcept;

}  // namespace dsp
