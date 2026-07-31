cmake_minimum_required(VERSION 3.22.1)
project(rawmeter)

add_library(rawmeter SHARED raw_meter.cpp)

find_library(log-lib log)
target_link_libraries(rawmeter ${log-lib})
