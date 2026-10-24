add_library(pa_ringbuffer STATIC
  portaudio/pa_ringbuffer.c
)

target_include_directories(pa_ringbuffer PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/portaudio
)

set_target_properties(pa_ringbuffer PROPERTIES
  C_STANDARD 11
  C_STANDARD_REQUIRED ON
)

# Third-party code is compiled without first-party warning flags.
if(MSVC)
  target_compile_options(pa_ringbuffer PRIVATE /w)
else()
  target_compile_options(pa_ringbuffer PRIVATE -w)
endif()

# libbs2b 3.1.0 (MIT), standalone library source. The DeaDBeeF plugin wrapper
# is not compiled.
add_library(bs2b STATIC
  bs2b/libbs2b-3.1.0/src/bs2b.c
)

target_include_directories(bs2b PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/bs2b/libbs2b-3.1.0/src
)

if(MSVC)
  target_compile_options(bs2b PRIVATE /w)
else()
  target_compile_options(bs2b PRIVATE -w)
  target_link_libraries(bs2b PRIVATE m)
endif()

# libsamplerate (BSD-2-Clause), built as a static library. Tests, examples and
# install rules are disabled; only the library is needed.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LIBSAMPLERATE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIBSAMPLERATE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(libsamplerate)
