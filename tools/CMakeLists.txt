add_executable(hifi_offline hifi_offline.cpp)
target_link_libraries(hifi_offline PRIVATE hifi_dsp)
target_compile_features(hifi_offline PRIVATE cxx_std_20)

if(MSVC)
  target_compile_options(hifi_offline PRIVATE /W4 /WX /permissive-)
else()
  target_compile_options(hifi_offline PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
  )
endif()
