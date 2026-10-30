cmake_minimum_required(VERSION 3.24)
project(sensor_server LANGUAGES CXX)

# The JNI library of the Device Server app: one openDAQ instance with the
# phone's sensors, served over openDAQ's native protocol. Built against the
# openDAQ SDK cross-compiled by tool/build_native.sh.
find_package(openDAQ REQUIRED)

add_library(sensor_server SHARED src/server.cpp)
target_compile_features(sensor_server PRIVATE cxx_std_17)
target_link_libraries(sensor_server PRIVATE daq::opendaq dl log)
set_target_properties(sensor_server PROPERTIES CXX_VISIBILITY_PRESET hidden)
