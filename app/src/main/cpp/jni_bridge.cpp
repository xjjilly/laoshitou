// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include <jni.h>

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "audio/AudioEngine.h"
#include "audio/JavaMediaSource.h"
#include "audio/FfmpegInfo.h"
#include "dsp/AutoEqFitter.h"
#include "dsp/AutoEqProfile.h"
#include "dsp/SampleRatePolicy.h"

namespace {

std::mutex g_engine_mutex;
std::unique_ptr<audio::AudioEngine> g_engine;
std::string g_start_error;

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

namespace {

bool FillPlaybackOptions(JNIEnv* env, jboolean processing, jintArray supported_rates,
                         jint native_rate, jstring eq_profile_path, jboolean crossfeed,
                         jboolean eq_cascade, jboolean eq_slope_enabled, jdouble eq_slope,
                         jint forced_sample_rate, jint resampler_quality,
                         jdouble crossfeed_width, jdouble stereo_width,
                         jboolean loudness_enabled, jdouble loudness_volume,
                         jboolean limiter_enabled, jdouble limiter_threshold_db,
                         jboolean usb_dac_detected, jboolean auto_usb_192k, jstring brir_path,
                         jboolean brir_enabled, jdouble brir_mix, jboolean eq_linear_phase,
                         jboolean multiband_enabled, jdouble multiband_low_hz,
                         jdouble multiband_high_hz, jdouble multiband_threshold_db,
                         audio::PlaybackOptions* options) {
  if (options == nullptr) {
    return false;
  }
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

  audio::PlaybackOptions parsed;
  parsed.mode = processing != JNI_FALSE ? dsp::OutputMode::kProcessing : dsp::OutputMode::kDirect;
  parsed.device = std::move(device);
  parsed.crossfeed_enabled = crossfeed != JNI_FALSE;
  parsed.limiter_enabled = limiter_enabled != JNI_FALSE;
  parsed.eq_cascade = eq_cascade != JNI_FALSE;
  parsed.eq_use_slope = eq_slope_enabled != JNI_FALSE;
  parsed.eq_slope = static_cast<double>(eq_slope);
  parsed.forced_sample_rate = static_cast<int>(forced_sample_rate);
  parsed.resampler_quality = static_cast<int>(resampler_quality);
  parsed.crossfeed_width = static_cast<double>(crossfeed_width);
  parsed.stereo_width = static_cast<double>(stereo_width);
  parsed.loudness_enabled = loudness_enabled != JNI_FALSE;
  parsed.loudness_volume = static_cast<double>(loudness_volume);
  parsed.limiter_threshold_db = static_cast<double>(limiter_threshold_db);
  parsed.usb_dac_detected = usb_dac_detected != JNI_FALSE;
  parsed.auto_usb_192k = auto_usb_192k != JNI_FALSE;
  parsed.brir_enabled = brir_enabled != JNI_FALSE;
  parsed.brir_mix = static_cast<double>(brir_mix);
  parsed.eq_linear_phase = eq_linear_phase != JNI_FALSE;
  parsed.multiband.enabled = multiband_enabled != JNI_FALSE;
  parsed.multiband.crossover_low_hz = static_cast<double>(multiband_low_hz);
  parsed.multiband.crossover_high_hz = static_cast<double>(multiband_high_hz);
  parsed.multiband.low.threshold_db = static_cast<double>(multiband_threshold_db);
  if (brir_path != nullptr) {
    const char* brir_chars = env->GetStringUTFChars(brir_path, nullptr);
    if (brir_chars != nullptr) {
      parsed.brir_path = brir_chars;
      env->ReleaseStringUTFChars(brir_path, brir_chars);
    }
  }
  if (eq_profile_path != nullptr) {
    const char* eq_chars = env->GetStringUTFChars(eq_profile_path, nullptr);
    if (eq_chars != nullptr) {
      parsed.eq_profile_path = eq_chars;
      env->ReleaseStringUTFChars(eq_profile_path, eq_chars);
    }
  }
  *options = std::move(parsed);
  return true;
}

}  // namespace

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

  audio::PlaybackOptions options;
  if (!FillPlaybackOptions(env, processing, supported_rates, native_rate, eq_profile_path,
                           crossfeed, eq_cascade, eq_slope_enabled, eq_slope,
                           forced_sample_rate, resampler_quality, crossfeed_width, stereo_width,
                           loudness_enabled, loudness_volume, limiter_enabled,
                           limiter_threshold_db, usb_dac_detected, auto_usb_192k, brir_path,
                           brir_enabled, brir_mix, eq_linear_phase, multiband_enabled,
                           multiband_low_hz, multiband_high_hz, multiband_threshold_db,
                           &options)) {
    return JNI_FALSE;
  }

  std::lock_guard<std::mutex> lock(g_engine_mutex);
  g_start_error.clear();
  if (g_engine) {
    g_engine->stop();
    g_engine.reset();
  }
  auto engine = std::make_unique<audio::AudioEngine>();
  if (!engine->start_file(file_path, options)) {
    g_start_error = engine->last_error();
    if (g_start_error.empty()) {
      g_start_error = "failed to start playback";
    }
    return JNI_FALSE;
  }
  g_engine = std::move(engine);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_gu_laoshitou_NativeBridge_startSource(
    JNIEnv* env, jobject /*thiz*/, jobject data_source, jboolean processing,
    jintArray supported_rates, jint native_rate, jstring eq_profile_path, jboolean crossfeed,
    jboolean eq_cascade, jboolean eq_slope_enabled, jdouble eq_slope, jint forced_sample_rate,
    jint resampler_quality, jdouble crossfeed_width, jdouble stereo_width,
    jboolean loudness_enabled, jdouble loudness_volume, jboolean limiter_enabled,
    jdouble limiter_threshold_db, jboolean usb_dac_detected, jboolean auto_usb_192k,
    jstring brir_path, jboolean brir_enabled, jdouble brir_mix, jboolean eq_linear_phase,
    jboolean multiband_enabled, jdouble multiband_low_hz, jdouble multiband_high_hz,
    jdouble multiband_threshold_db) {
  if (data_source == nullptr) {
    return JNI_FALSE;
  }
  audio::PlaybackOptions options;
  if (!FillPlaybackOptions(env, processing, supported_rates, native_rate, eq_profile_path,
                           crossfeed, eq_cascade, eq_slope_enabled, eq_slope,
                           forced_sample_rate, resampler_quality, crossfeed_width, stereo_width,
                           loudness_enabled, loudness_volume, limiter_enabled,
                           limiter_threshold_db, usb_dac_detected, auto_usb_192k, brir_path,
                           brir_enabled, brir_mix, eq_linear_phase, multiband_enabled,
                           multiband_low_hz, multiband_high_hz, multiband_threshold_db,
                           &options)) {
    return JNI_FALSE;
  }

  auto source = std::make_unique<audio::JavaMediaSource>(env, data_source);
  if (!source->last_error().empty()) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_start_error = source->last_error();
    return JNI_FALSE;
  }

  std::lock_guard<std::mutex> lock(g_engine_mutex);
  g_start_error.clear();
  if (g_engine) {
    g_engine->stop();
    g_engine.reset();
  }
  auto engine = std::make_unique<audio::AudioEngine>();
  if (!engine->start_source(std::move(source), options)) {
    g_start_error = engine->last_error();
    if (g_start_error.empty()) {
      g_start_error = "failed to start stream playback";
    }
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gu_laoshitou_NativeBridge_seekPlayback(JNIEnv* /*env*/, jobject /*thiz*/,
                                                jlong position_ms) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return (g_engine && g_engine->seek_to_ms(static_cast<std::int64_t>(position_ms)))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackPositionMs(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jlong>(g_engine->position_ms()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackDurationMs(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jlong>(g_engine->duration_ms()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackXruns(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? static_cast<jlong>(g_engine->xruns()) : 0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackLatencyMs(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_engine_mutex);
  return g_engine ? g_engine->latency_ms() : 0.0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gu_laoshitou_NativeBridge_playbackLastError(JNIEnv* env, jobject /*thiz*/) {
  std::string error;
  {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (g_engine) {
      error = g_engine->last_error();
    } else {
      error = g_start_error;
    }
  }
  return env->NewStringUTF(error.c_str());
}
