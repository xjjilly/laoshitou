function(add_host_test name source)
  add_executable(${name} ${source})
  target_link_libraries(${name} PRIVATE hifi_dsp)
  target_compile_features(${name} PRIVATE cxx_std_20)

  if(MSVC)
    target_compile_options(${name} PRIVATE /W4 /WX /permissive-)
  else()
    target_compile_options(${name} PRIVATE
      -Wall
      -Wextra
      -Wpedantic
      -Werror
    )
  endif()

  add_test(NAME ${name} COMMAND ${name})
endfunction()

add_host_test(sample_rate_policy_test SampleRatePolicyTest.cpp)
add_host_test(audio_ring_buffer_test AudioRingBufferTest.cpp)
add_host_test(resampler_test ResamplerTest.cpp)
add_host_test(autoeq_profile_test AutoEqProfileTest.cpp)
add_host_test(autoeq_fit_test AutoEqFitTest.cpp)
add_host_test(peq_test PeqTest.cpp)
add_host_test(crossfeed_test CrossfeedTest.cpp)
add_host_test(limiter_test LimiterTest.cpp)
add_host_test(dsp_chain_test DspChainTest.cpp)
add_host_test(dff_reader_test DffReaderTest.cpp)
add_host_test(loudness_test LoudnessTest.cpp)
add_host_test(fft_test FftTest.cpp)
add_host_test(partitioned_convolver_test PartitionedConvolverTest.cpp)
add_host_test(ir_loader_test IrLoaderTest.cpp)
add_host_test(brir_processor_test BrirProcessorTest.cpp)
add_host_test(drift_compensator_test DriftCompensatorTest.cpp)
add_host_test(fir_eq_processor_test FirEqProcessorTest.cpp)
add_host_test(multiband_compressor_test MultibandCompressorTest.cpp)
