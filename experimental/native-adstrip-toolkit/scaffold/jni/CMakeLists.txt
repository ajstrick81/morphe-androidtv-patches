# CMakeLists.txt — builds lib<HOOK>.so, the in-process ad-strip library.
#
# Rename <HOOK> throughout (e.g. "pvhook"). The loaded name is lib<HOOK>.so and
# must match LIB in NativeHookLoader.java and the bundle path in the patch.
#
# Prereqs:
#   1. Android NDK (r25+ / NDK 28.x used for the reference build).
#   2. An inline-hook engine vendored under jni/hook/.
#      RECOMMENDED: ShadowHook (github.com/bytedance/android-inline-hook).
#      NOTE (hard-won, see docs/LESSONS.md): Dobby was a dead end on the
#      reference target — do not start there.
#
# Build (do armeabi-v7a first if that's the shipping ABI):
#   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -B build-v7a
#   cmake --build build-v7a
# Output: build-v7a/lib<HOOK>.so  → into the APK's lib/armeabi-v7a/.

cmake_minimum_required(VERSION 3.18)
project(<HOOK> LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keep the .so small and self-contained.
add_compile_options(-Os -fvisibility=hidden -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL)

add_library(<HOOK> SHARED
    hooks.cpp
    sigscan.cpp
    # ── your app-specific transform sources (copy the pattern from the
    #    primevideo example: a filter + optionally a reassembler) ──
    # <app>_filter.cpp
)

target_include_directories(<HOOK> PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# ── inline-hook engine (ShadowHook recommended) ──────────────────────────────
# Option A: source tree vendored under jni/hook/.
# add_subdirectory(hook)
# target_link_libraries(<HOOK> PRIVATE shadowhook)
#
# Option B: prebuilt per-ABI static lib.
add_library(shadowhook STATIC IMPORTED)
set_target_properties(shadowhook PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/hook/prebuilt/${ANDROID_ABI}/libshadowhook.a)
target_include_directories(<HOOK> PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/hook/include)
target_link_libraries(<HOOK> PRIVATE shadowhook)

# Android log.
find_library(log-lib log)
target_link_libraries(<HOOK> PRIVATE ${log-lib})
