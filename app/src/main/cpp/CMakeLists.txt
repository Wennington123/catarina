cmake_minimum_required(VERSION 3.22.1)

project(catarina_jni CXX)

# Engine nativo do Needle/Whistle (baixado por scripts/fetch-engine.sh, não versionado).
set(ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/android-arm64")
set(ENGINE_LIB "${ENGINE_DIR}/libneedle.a")
set(ENGINE_HDR "${ENGINE_DIR}/needle.h")

if(NOT EXISTS "${ENGINE_LIB}" OR NOT EXISTS "${ENGINE_HDR}")
    message(FATAL_ERROR
        "Engine nativo ausente em ${ENGINE_DIR}.\n"
        "Rode ./scripts/fetch-engine.sh para baixar android-arm64 "
        "(needle, libneedle.a, needle.h).")
endif()

add_library(catarina_jni SHARED needle_jni.cpp)

target_include_directories(catarina_jni PRIVATE "${ENGINE_DIR}")

target_link_libraries(catarina_jni
    "${ENGINE_LIB}"
    android
    log)

target_compile_options(catarina_jni PRIVATE -Wall -Wextra -std=c++17)

# Requisito de página de 16 KB (Android 15+).
target_link_options(catarina_jni PRIVATE "-Wl,-z,max-page-size=16384")
