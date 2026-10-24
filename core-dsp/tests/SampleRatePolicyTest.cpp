// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "dsp/SampleRatePolicy.h"

#include <cstddef>
#include <initializer_list>
#include <iostream>
#include <vector>

namespace {

int g_failures = 0;

#define CHECK(condition)                                                     \
  do {                                                                       \
    if (!(condition)) {                                                      \
      std::cerr << __FILE__ << ":" << __LINE__                               \
                << " CHECK failed: " #condition << "\n";                     \
      ++g_failures;                                                          \
    }                                                                        \
  } while (false)

dsp::DeviceRates Device(std::initializer_list<int> rates, int native_rate) {
  dsp::DeviceRates device;
  device.supported_rates.assign(rates.begin(), rates.end());
  device.native_rate = native_rate;
  return device;
}

void TestClassifyRateFamily() {
  using dsp::RateFamily;
  CHECK(dsp::ClassifyRateFamily(44100) == RateFamily::k44k);
  CHECK(dsp::ClassifyRateFamily(88200) == RateFamily::k44k);
  CHECK(dsp::ClassifyRateFamily(176400) == RateFamily::k44k);
  CHECK(dsp::ClassifyRateFamily(22050) == RateFamily::k44k);
  CHECK(dsp::ClassifyRateFamily(11025) == RateFamily::k44k);

  CHECK(dsp::ClassifyRateFamily(48000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(96000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(192000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(24000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(32000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(16000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(8000) == RateFamily::k48k);
  CHECK(dsp::ClassifyRateFamily(12000) == RateFamily::k48k);

  CHECK(dsp::ClassifyRateFamily(44101) == RateFamily::kUnknown);
  CHECK(dsp::ClassifyRateFamily(0) == RateFamily::kUnknown);
  CHECK(dsp::ClassifyRateFamily(-44100) == RateFamily::kUnknown);
}

void TestLadders() {
  const auto ladder_44 = dsp::FamilyLadder(dsp::RateFamily::k44k);
  CHECK(ladder_44.size() == 3U);
  CHECK(ladder_44[0] == 44100);
  CHECK(ladder_44[1] == 88200);
  CHECK(ladder_44[2] == 176400);

  const auto ladder_48 = dsp::FamilyLadder(dsp::RateFamily::k48k);
  CHECK(ladder_48.size() == 3U);
  CHECK(ladder_48[0] == 48000);
  CHECK(ladder_48[1] == 96000);
  CHECK(ladder_48[2] == 192000);

  CHECK(dsp::FamilyLadder(dsp::RateFamily::kUnknown).empty());
}

void TestIntegerRatio() {
  CHECK(dsp::IsIntegerRatio(44100, 176400));
  CHECK(dsp::IsIntegerRatio(176400, 44100));
  CHECK(dsp::IsIntegerRatio(88200, 176400));
  CHECK(dsp::IsIntegerRatio(32000, 96000));
  CHECK(!dsp::IsIntegerRatio(44100, 96000));
  CHECK(!dsp::IsIntegerRatio(32000, 48000));
  CHECK(!dsp::IsIntegerRatio(0, 48000));
  CHECK(!dsp::IsIntegerRatio(44100, -1));
}

void TestProcessing44Family() {
  const auto device =
      Device({44100, 48000, 88200, 96000, 176400, 192000}, 48000);

  const auto at_44100 =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kProcessing, device);
  CHECK(at_44100.target_rate == 176400);
  CHECK(at_44100.family == dsp::RateFamily::k44k);
  CHECK(at_44100.needs_resample);
  CHECK(!at_44100.cross_family_fallback);
  CHECK(!at_44100.non_integer_ratio);
  CHECK(!at_44100.device_limited);

  const auto at_88200 =
      dsp::MakeRatePlan(88200, dsp::OutputMode::kProcessing, device);
  CHECK(at_88200.target_rate == 176400);
  CHECK(at_88200.needs_resample);
  CHECK(!at_88200.cross_family_fallback);
  CHECK(!at_88200.non_integer_ratio);

  const auto at_176400 =
      dsp::MakeRatePlan(176400, dsp::OutputMode::kProcessing, device);
  CHECK(at_176400.target_rate == 176400);
  CHECK(!at_176400.needs_resample);
  CHECK(!at_176400.cross_family_fallback);
  CHECK(!at_176400.non_integer_ratio);

  const auto at_22050 =
      dsp::MakeRatePlan(22050, dsp::OutputMode::kProcessing, device);
  CHECK(at_22050.target_rate == 176400);
  CHECK(at_22050.needs_resample);
  CHECK(dsp::IsIntegerRatio(22050, at_22050.target_rate));
}

void TestProcessing48Family() {
  const auto device =
      Device({44100, 48000, 88200, 96000, 176400, 192000}, 48000);

  const auto at_48000 =
      dsp::MakeRatePlan(48000, dsp::OutputMode::kProcessing, device);
  CHECK(at_48000.target_rate == 192000);
  CHECK(at_48000.family == dsp::RateFamily::k48k);
  CHECK(at_48000.needs_resample);
  CHECK(!at_48000.cross_family_fallback);
  CHECK(!at_48000.non_integer_ratio);

  const auto at_96000 =
      dsp::MakeRatePlan(96000, dsp::OutputMode::kProcessing, device);
  CHECK(at_96000.target_rate == 192000);
  CHECK(at_96000.needs_resample);
  CHECK(!at_96000.non_integer_ratio);

  const auto at_192000 =
      dsp::MakeRatePlan(192000, dsp::OutputMode::kProcessing, device);
  CHECK(at_192000.target_rate == 192000);
  CHECK(!at_192000.needs_resample);

  const auto at_32000 =
      dsp::MakeRatePlan(32000, dsp::OutputMode::kProcessing, device);
  CHECK(at_32000.target_rate == 192000);
  CHECK(dsp::IsIntegerRatio(32000, at_32000.target_rate));

  const auto at_24000 =
      dsp::MakeRatePlan(24000, dsp::OutputMode::kProcessing, device);
  CHECK(at_24000.target_rate == 192000);
  CHECK(dsp::IsIntegerRatio(24000, at_24000.target_rate));
}

void TestNeverUpsample44To96ByDefault() {
  // The device has no 44.1-family rate at all. The policy must fall back to
  // the native 48k, never to 96k, and must flag the cross-family conversion.
  const auto device = Device({48000, 96000, 192000}, 48000);
  const auto plan =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kProcessing, device);
  CHECK(plan.target_rate == 48000);
  CHECK(plan.target_rate != 96000);
  CHECK(plan.cross_family_fallback);
  CHECK(plan.non_integer_ratio);
  CHECK(!plan.device_limited);
}

void TestCrossFamilyPrefersClosestOverNative96() {
  // Native rate is 96k, but the device also reports 48k. A 44.1-family
  // source must stay on 48k, not jump to the native 96k.
  const auto device = Device({48000, 96000, 192000}, 96000);
  const auto plan =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kProcessing, device);
  CHECK(plan.target_rate == 48000);
  CHECK(plan.target_rate != 96000);
  CHECK(plan.cross_family_fallback);
  CHECK(plan.non_integer_ratio);
}

void TestDeviceLimitedSameFamily() {
  // Device tops out at 88.2k. A 176.4k source is downsampled within the same
  // family (integer ratio 1/2), not converted to the 48k family.
  const auto device = Device({44100, 88200}, 48000);
  const auto plan =
      dsp::MakeRatePlan(176400, dsp::OutputMode::kProcessing, device);
  CHECK(plan.target_rate == 88200);
  CHECK(plan.device_limited);
  CHECK(plan.needs_resample);
  CHECK(!plan.cross_family_fallback);
  CHECK(!plan.non_integer_ratio);
}

void TestSmallerSameFamilyLadder() {
  const auto device_44 = Device({44100, 88200}, 48000);
  const auto at_44100 =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kProcessing, device_44);
  CHECK(at_44100.target_rate == 88200);
  CHECK(dsp::IsIntegerRatio(44100, at_44100.target_rate));

  const auto device_48 = Device({48000, 96000}, 48000);
  const auto at_48000 =
      dsp::MakeRatePlan(48000, dsp::OutputMode::kProcessing, device_48);
  CHECK(at_48000.target_rate == 96000);
  CHECK(dsp::IsIntegerRatio(48000, at_48000.target_rate));
}

void TestDirectMode() {
  const auto device = Device({44100, 48000, 88200, 96000}, 48000);

  const auto direct_44100 =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kDirect, device);
  CHECK(direct_44100.target_rate == 44100);
  CHECK(!direct_44100.needs_resample);
  CHECK(!direct_44100.cross_family_fallback);

  const auto direct_96000 =
      dsp::MakeRatePlan(96000, dsp::OutputMode::kDirect, device);
  CHECK(direct_96000.target_rate == 96000);
  CHECK(!direct_96000.needs_resample);

  const auto direct_fallback =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kDirect,
                        Device({48000, 96000}, 48000));
  CHECK(direct_fallback.target_rate == 48000);
  CHECK(direct_fallback.needs_resample);
  CHECK(direct_fallback.cross_family_fallback);
}

void TestEmptySupportedRates() {
  const auto device = Device({}, 48000);

  const auto at_44100 =
      dsp::MakeRatePlan(44100, dsp::OutputMode::kProcessing, device);
  CHECK(at_44100.target_rate == 48000);
  CHECK(at_44100.cross_family_fallback);
  CHECK(at_44100.non_integer_ratio);

  const auto at_48000 =
      dsp::MakeRatePlan(48000, dsp::OutputMode::kProcessing, device);
  CHECK(at_48000.target_rate == 48000);
  CHECK(!at_48000.needs_resample);
}

void TestUnknownSourceRate() {
  const auto device = Device({48000, 96000, 192000}, 48000);
  const auto plan =
      dsp::MakeRatePlan(44101, dsp::OutputMode::kProcessing, device);
  CHECK(plan.family == dsp::RateFamily::kUnknown);
  CHECK(plan.target_rate == 48000);
}

void TestNonIntegerFallbackFlag() {
  const auto at_32000 =
      dsp::MakeRatePlan(32000, dsp::OutputMode::kProcessing,
                        Device({48000}, 48000));
  CHECK(at_32000.target_rate == 48000);
  CHECK(at_32000.non_integer_ratio);

  const auto at_32000_ok =
      dsp::MakeRatePlan(32000, dsp::OutputMode::kProcessing,
                        Device({96000}, 96000));
  CHECK(at_32000_ok.target_rate == 96000);
  CHECK(!at_32000_ok.non_integer_ratio);
}

}  // namespace

int main() {
  TestClassifyRateFamily();
  TestLadders();
  TestIntegerRatio();
  TestProcessing44Family();
  TestProcessing48Family();
  TestNeverUpsample44To96ByDefault();
  TestCrossFamilyPrefersClosestOverNative96();
  TestDeviceLimitedSameFamily();
  TestSmallerSameFamilyLadder();
  TestDirectMode();
  TestEmptySupportedRates();
  TestUnknownSourceRate();
  TestNonIntegerFallbackFlag();

  if (g_failures == 0) {
    std::cout << "ALL TESTS PASSED\n";
    return 0;
  }
  std::cerr << g_failures << " CHECK(s) failed\n";
  return 1;
}
