add_library(hifi_dsp STATIC
  src/AudioRingBuffer.cpp
  src/AutoEqFitter.cpp
  src/AutoEqProfile.cpp
  src/Biquad.cpp
  src/BrirProcessor.cpp
  src/Crossfeed.cpp
  src/DffReader.cpp
  src/DriftCompensator.cpp
  src/DspChain.cpp
  src/Fft.cpp
  src/FirEqProcessor.cpp
  src/IrLoader.cpp
  src/Limiter.cpp
  src/Loudness.cpp
  src/MultibandCompressor.cpp
  src/PartitionedConvolver.cpp
  src/Peq.cpp
  src/Resampler.cpp
  src/SampleRatePolicy.cpp
)

target_include_directories(hifi_dsp PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(hifi_dsp PUBLIC cxx_std_20)

target_link_libraries(hifi_dsp PRIVATE pa_ringbuffer samplerate bs2b)

if(MSVC)
  # /utf-8: the sources are UTF-8 (Chinese comments); the default system
  # codepage would misparse them.
  target_compile_options(hifi_dsp PRIVATE /W4 /WX /permissive- /utf-8)
else()
  target_compile_options(hifi_dsp PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
  )
endif()
