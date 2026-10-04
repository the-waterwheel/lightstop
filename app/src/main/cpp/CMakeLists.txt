cmake_minimum_required(VERSION 3.22.1)
project(rawmeter)

add_library(rawmeter SHARED raw_meter.cpp)

# NDK r27 needs explicit flexible-page linker settings. These LOAD segments work on both
# legacy 4 KB devices and Android 15+ devices configured with 16 KB memory pages.
target_link_options(rawmeter PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(rawmeter ${log-lib})
