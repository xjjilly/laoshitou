cmake_minimum_required(VERSION 3.22)
project(hifi_player LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_subdirectory(third_party)
add_subdirectory(core-dsp)
add_subdirectory(tools)

enable_testing()
add_subdirectory(core-dsp/tests)
