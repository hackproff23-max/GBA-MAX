cmake_minimum_required(VERSION 3.10)
project(gbamax)
add_library(gbamax SHARED emulator.cpp)
target_link_libraries(gbamax dl log jnigraphics)
