cmake_minimum_required(VERSION 3.22)
project(hifiplayer LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Third-party and core-dsp are platform-independent and also built by the host
# test project.
add_subdirectory(
  ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party
  ${CMAKE_CURRENT_BINARY_DIR}/third_party
)

add_subdirectory(
  ${CMAKE_CURRENT_SOURCE_DIR}/../../../../core-dsp
  ${CMAKE_CURRENT_BINARY_DIR}/core-dsp
)

find_package(oboe REQUIRED CONFIG)

set(FFMPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ffmpeg)
set(FFMPEG_INCLUDE_DIR ${FFMPEG_ROOT}/include)
set(FFMPEG_LIB_DIR ${FFMPEG_ROOT}/${ANDROID_ABI}/lib)

foreach(ffmpeg_lib avformat avcodec swresample avutil)
  add_library(ffmpeg_${ffmpeg_lib} STATIC IMPORTED)
  set_target_properties(ffmpeg_${ffmpeg_lib} PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/lib${ffmpeg_lib}.a
    INTERFACE_INCLUDE_DIRECTORIES ${FFMPEG_INCLUDE_DIR}
  )
endforeach()

add_subdirectory(
  ${CMAKE_CURRENT_SOURCE_DIR}/../../../../platform-android
  ${CMAKE_CURRENT_BINARY_DIR}/platform-android
)

add_library(hifiplayer SHARED
  jni_bridge.cpp
)

target_link_libraries(hifiplayer PRIVATE
  hifi_platform_android
  hifi_dsp
  pa_ringbuffer
  oboe::oboe
  log
  android
  -Wl,--start-group
  ffmpeg_avformat
  ffmpeg_avcodec
  ffmpeg_swresample
  ffmpeg_avutil
  -Wl,--end-group
  m
  dl
)

target_compile_options(hifiplayer PRIVATE
  -Wall
  -Wextra
  -Wpedantic
  -Werror
)

# Android 15+ requires 16 KB page alignment for native libraries.
target_link_options(hifiplayer PRIVATE
  -Wl,-z,max-page-size=16384
)
