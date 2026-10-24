add_library(hifi_platform_android STATIC
  src/AudioEngine.cpp
  src/FfmpegDecoder.cpp
  src/FfmpegInfo.cpp
  src/OboeOutput.cpp
)

target_include_directories(hifi_platform_android PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_include_directories(hifi_platform_android PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/ffmpeg/include
)

target_compile_features(hifi_platform_android PUBLIC cxx_std_20)

# FFmpeg's C headers require these when included from C++ translation units.
target_compile_definitions(hifi_platform_android PRIVATE
  __STDC_CONSTANT_MACROS
  __STDC_FORMAT_MACROS
)

target_link_libraries(hifi_platform_android PUBLIC
  hifi_dsp
  pa_ringbuffer
  oboe::oboe
  log
  android
)

target_compile_options(hifi_platform_android PRIVATE
  -Wall
  -Wextra
  -Wpedantic
  -Werror
)
