// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <jni.h>

#include <array>
#include <cmath>
#include <cstddef>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "audio/AudioEngine.h"
#include "audio/FfmpegInfo.h"
#include "dsp/AutoEqFitter.h"
#include "dsp/AutoEqProfile.h"
#include "dsp/SampleRatePolicy.h"

namespace {

std::mutex g_engine_mutex;
std::unique_ptr<audio::AudioEngine> g_engine;

}  // namespace

extern "C" JNIEXPORT jint JNICALL Java_com_gu_laoshitou_NativeBridge_sampleRatePlan(
    JNIEnv* env, jobject /*thiz*/, jint source_rate, jboolean processing, jintArray supported_rates,
    jint native_rate) {
  dsp::DeviceRates device;
  device.native_rate = static_cast<int>(native_rate);

  if (supported_rates != nullptr) {
    const jsize length = env->GetArrayLength(supported_rates);
    if (length > 0) {
      std::vector<jint> values(static_cast<std::size_t>(length));
      env->GetIntArrayRegion(supported_rates, 0, length, values.data());
      device.supported_rates.assign(values.begin(), values.end());
    }
  }

  const dsp::OutputMode mode =
      processing != JNI_FALSE ? dsp::OutputMode::kProcessing : dsp::OutputMode::kDirect;
  const dsp::RatePlan plan = dsp::MakeRatePlan(static_cast<int>(source_rate), mode, device);
  return static_cast<jint>(plan.target_rate);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_gu_laoshitou_NativeBridge_startTestTone(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sample_rate) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->stop();
    g_engine.reset();
  }

  auto engine = std::make_unique<audio::AudioEngine>();
  if (!engine->start_test_tone(static_cast<int>(sample_rate))) {
    return JNI_FALSE;
  }
  g_engine = std::move(engine);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gu_laoshitou_NativeBridge_stopTestTone(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->stop();
    g_engine.reset();
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_isTestToneRunning(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->is_running()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gu_laoshitou_NativeBridge_testToneActualRate(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jint>(g_engine->actual_sample_rate()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_testToneExclusive(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->is_exclusive()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gu_laoshitou_NativeBridge_testToneUnderruns(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jlong>(g_engine->underruns()) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gu_laoshitou_NativeBridge_ffmpegVersion(JNIEnv* env, jobject /*thiz*/) {
  return env->NewStringUTF(audio::FfmpegVersion());
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_gu_laoshitou_NativeBridge_loadEqProfileForEditor(
    JNIEnv* env, jobject /*thiz*/, jstring path, jint order, jboolean use_slope, jdouble slope) {
  if (path == nullptr) {
    return env->NewFloatArray(0);
  }
  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr) {
    return env->NewFloatArray(0);
  }
  std::string profile_path(chars);
  env->ReleaseStringUTFChars(path, chars);

  dsp::AutoEqOptions options;
  options.order = static_cast<int>(order);
  options.use_slope = use_slope != JNI_FALSE;
  options.slope = static_cast<double>(slope);

  dsp::AutoEqProfile profile;
  std::string error;
  std::array<double, dsp::kEditorBandCount> gains{};
  double preamp = 0.0;
  if (!dsp::LoadAutoEqProfile(profile_path, &profile, &error, options) ||
      !dsp::FitProfileToEditorBands(profile, &gains, &preamp, &error)) {
    return env->NewFloatArray(0);
  }

  std::array<jfloat, dsp::kEditorBandCount + 1> values{};
  for (std::size_t i = 0; i < dsp::kEditorBandCount; ++i) {
    values[i] = static_cast<jfloat>(gains[i]);
  }
  values[dsp::kEditorBandCount] = static_cast<jfloat>(preamp);
  jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
  if (result != nullptr) {
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
  }
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_gu_laoshitou_NativeBridge_startFile(
    JNIEnv* env, jobject /*thiz*/, jstring path, jboolean processing, jintArray supported_rates,
    jint native_rate, jstring eq_profile_path, jboolean crossfeed, jboolean eq_cascade,
    jboolean eq_slope_enabled, jdouble eq_slope, jint forced_sample_rate, jint resampler_quality,
    jdouble crossfeed_width, jdouble stereo_width, jboolean loudness_enabled,
    jdouble loudness_volume, jboolean limiter_enabled, jdouble limiter_threshold_db,
    jboolean usb_dac_detected, jboolean auto_usb_192k, jstring brir_path, jboolean brir_enabled,
    jdouble brir_mix, jboolean eq_linear_phase, jboolean multiband_enabled,
    jdouble multiband_low_hz, jdouble multiband_high_hz, jdouble multiband_threshold_db) {
  if (path == nullptr) {
    return JNI_FALSE;
  }
  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr) {
    return JNI_FALSE;
  }
  std::string file_path(chars);
  env->ReleaseStringUTFChars(path, chars);

  dsp::DeviceRates device;
  device.native_rate = static_cast<int>(native_rate);
  if (supported_rates != nullptr) {
    const jsize length = env->GetArrayLength(supported_rates);
    if (length > 0) {
      std::vector<jint> values(static_cast<std::size_t>(length));
      env->GetIntArrayRegion(supported_rates, 0, length, values.data());
      device.supported_rates.assign(values.begin(), values.end());
    }
  }

  audio::PlaybackOptions options;
  options.mode = processing != JNI_FALSE ? dsp::OutputMode::kProcessing : dsp::OutputMode::kDirect;
  options.device = std::move(device);
  options.crossfeed_enabled = crossfeed != JNI_FALSE;
  options.limiter_enabled = limiter_enabled != JNI_FALSE;
  options.eq_cascade = eq_cascade != JNI_FALSE;
  options.eq_use_slope = eq_slope_enabled != JNI_FALSE;
  options.eq_slope = static_cast<double>(eq_slope);
  options.forced_sample_rate = static_cast<int>(forced_sample_rate);
  options.resampler_quality = static_cast<int>(resampler_quality);
  options.crossfeed_width = static_cast<double>(crossfeed_width);
  options.stereo_width = static_cast<double>(stereo_width);
  options.loudness_enabled = loudness_enabled != JNI_FALSE;
  options.loudness_volume = static_cast<double>(loudness_volume);
  options.limiter_threshold_db = static_cast<double>(limiter_threshold_db);
  options.usb_dac_detected = usb_dac_detected != JNI_FALSE;
  options.auto_usb_192k = auto_usb_192k != JNI_FALSE;
  options.brir_enabled = brir_enabled != JNI_FALSE;
  options.brir_mix = static_cast<double>(brir_mix);
  options.eq_linear_phase = eq_linear_phase != JNI_FALSE;
  options.multiband.enabled = multiband_enabled != JNI_FALSE;
  options.multiband.crossover_low_hz = static_cast<double>(multiband_low_hz);
  options.multiband.crossover_high_hz = static_cast<double>(multiband_high_hz);
  options.multiband.low.threshold_db = static_cast<double>(multiband_threshold_db);
  if (brir_path != nullptr) {
    const char* brir_chars = env->GetStringUTFChars(brir_path, nullptr);
    if (brir_chars != nullptr) {
      options.brir_path = brir_chars;
      env->ReleaseStringUTFChars(brir_path, brir_chars);
    }
  }

  if (eq_profile_path != nullptr) {
    const char* eq_chars = env->GetStringUTFChars(eq_profile_path, nullptr);
    if (eq_chars != nullptr) {
      options.eq_profile_path = eq_chars;
      env->ReleaseStringUTFChars(eq_profile_path, eq_chars);
    }
  }

  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->stop();
    g_engine.reset();
  }

  auto engine = std::make_unique<audio::AudioEngine>();
  if (!engine->start_file(file_path, options)) {
    return JNI_FALSE;
  }
  g_engine = std::move(engine);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gu_laoshitou_NativeBridge_stopPlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->stop();
    g_engine.reset();
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_isPlaying(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->is_running()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackActualRate(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jint>(g_engine->actual_sample_rate()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackSourceRate(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jint>(g_engine->source_sample_rate()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackExclusive(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->is_exclusive()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackUnderruns(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jlong>(g_engine->underruns()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackTargetRate(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jint>(g_engine->target_sample_rate()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackCrossFamily(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->cross_family_fallback()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackNonInteger(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->non_integer_ratio()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackDeviceLimited(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->device_limited()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackEnded(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->source_eof()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackNeedsRecovery(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->needs_output_recovery()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_recoverPlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->recover_output()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackProcessingMode(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->processing_mode()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackEqBandCount(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jint>(g_engine->eq_band_count()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackCrossfeedEnabled(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->crossfeed_enabled()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackBrirActive(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->brir_active()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackLimiterEnabled(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->limiter_enabled()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gu_laoshitou_NativeBridge_pausePlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->set_paused(true);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gu_laoshitou_NativeBridge_resumePlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->set_paused(false);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_isPlaybackPaused(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->is_paused()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gu_laoshitou_NativeBridge_setPlaybackGain(JNIEnv* /*env*/, jobject /*thiz*/, jfloat gain) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->set_gain(static_cast<float>(gain));
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_gu_laoshitou_NativeBridge_setLoudnessVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat volume) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (g_engine) {
    g_engine->set_loudness_volume(static_cast<double>(volume));
  }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_gu_laoshitou_NativeBridge_updateEq(
    JNIEnv* env, jobject /*thiz*/, jfloatArray gains, jfloatArray frequencies, jdouble preamp_db) {
  if (gains == nullptr || frequencies == nullptr) {
    return JNI_FALSE;
  }
  const jsize count = env->GetArrayLength(gains);
  if (count <= 0 || count > 64 || env->GetArrayLength(frequencies) < count) {
    return JNI_FALSE;
  }
  std::vector<jfloat> gain_values(static_cast<std::size_t>(count));
  std::vector<jfloat> frequency_values(static_cast<std::size_t>(count));
  env->GetFloatArrayRegion(gains, 0, count, gain_values.data());
  env->GetFloatArrayRegion(frequencies, 0, count, frequency_values.data());

  dsp::PeqConfig config;
  config.preamp_db = static_cast<double>(preamp_db);
  config.bands.reserve(static_cast<std::size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    dsp::PeqBandConfig band;
    band.type = dsp::PeqFilterType::kPeaking;
    band.frequency = static_cast<double>(frequency_values[static_cast<std::size_t>(i)]);
    band.gain_db = static_cast<double>(gain_values[static_cast<std::size_t>(i)]);
    // Matches EqEditorQ in ui/EqCurve.kt (the 16-band editor grid).
    band.q = 1.41;
    band.enabled = std::abs(band.gain_db) >= 0.05;
    config.bands.push_back(band);
  }

  std::lock_guard<std::mutex> lock(g_engine_mutex);
  if (!g_engine) {
    return JNI_FALSE;
  }
  return g_engine->update_eq_live(config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackLinearPhaseActive(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->eq_linear_phase_active()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackFirDegraded(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->fir_degraded()) ? JNI_TRUE : JNI_FALSE;
}
