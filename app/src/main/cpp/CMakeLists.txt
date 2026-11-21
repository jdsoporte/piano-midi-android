cmake_minimum_required(VERSION 3.22)
project(pianoengine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(pianoengine SHARED
    engine.cpp
    master.cpp
    sf_engine.cpp
    jni_bridge.cpp)

target_compile_options(pianoengine PRIVATE -O3 -Wall)
target_link_libraries(pianoengine oboe::oboe log android)
