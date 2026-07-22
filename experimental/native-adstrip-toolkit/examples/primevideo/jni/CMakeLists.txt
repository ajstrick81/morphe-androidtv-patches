# CMakeLists.txt — builds libpvhook.so for the Prime Video in-process ad strip.
#
# Prereqs:
#   1. Android NDK (r25+).
#   2. Dobby vendored under jni/dobby/ (github.com/jmpews/Dobby) — either its
#      source tree (add_subdirectory) or a prebuilt libdobby.a per ABI.
#
# Build (armeabi-v7a first — that's the shipping target ABI):
#   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -B build-v7a
#   cmake --build build-v7a
#
# Output: build-v7a/libpvhook.so  → goes into the APK's lib/armeabi-v7a/.

cmake_minimum_required(VERSION 3.18)
project(pvhook LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keep the .so small and self-contained.
add_compile_options(-Os -fvisibility=hidden -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL)

add_library(pvhook SHARED
    hooks.cpp
    sigscan.cpp
    manifest_filter.cpp
    prs_filter.cpp
)

target_include_directories(pvhook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# ── Dobby ─────────────────────────────────────────────────────────────────────
# Option A: build from source (uncomment if you vendor the full tree).
# add_subdirectory(dobby)
# target_link_libraries(pvhook PRIVATE dobby)
#
# Option B: prebuilt static lib per ABI under jni/dobby/prebuilt/${ANDROID_ABI}/.
add_library(dobby STATIC IMPORTED)
set_target_properties(dobby PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/dobby/prebuilt/${ANDROID_ABI}/libdobby.a)
target_include_directories(pvhook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/dobby/include)
target_link_libraries(pvhook PRIVATE dobby)

# Android log.
find_library(log-lib log)
target_link_libraries(pvhook PRIVATE ${log-lib})
