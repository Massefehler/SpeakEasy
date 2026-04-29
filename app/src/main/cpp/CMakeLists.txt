cmake_minimum_required(VERSION 3.22.1)

project(speakeasy_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# libopus 1.5.x als Subprojekt – wir bauen statisch und linken in unsere .so,
# damit nur eine native Library im APK landet.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

# Auf 32-Bit-ARM ohne harten FPU-Pfad: Fixed-Point, und Intrinsics aus –
# libopus 1.5.x bringt NEON-ASM für armv7 mit, die der NDK-Build aber nicht
# automatisch über gas-preprocessor.pl assembliert. Performance-Hit auf
# armv7 ist verschmerzbar, arm64-v8a bleibt voll-NEON-optimiert.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)
    set(OPUS_DISABLE_INTRINSICS ON CACHE BOOL "" FORCE)
else()
    set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
    set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(opus)

add_library(speakeasy_opus SHARED opus_jni.c)

target_include_directories(speakeasy_opus PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
)

target_link_libraries(speakeasy_opus PRIVATE
    opus
    log
)

# Größenoptimierung: kein RTTI nötig (reines C), keine ungenutzten Symbole.
target_compile_options(speakeasy_opus PRIVATE
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)
target_link_options(speakeasy_opus PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
