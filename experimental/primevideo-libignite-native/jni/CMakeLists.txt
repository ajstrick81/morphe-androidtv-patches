# CMakeLists.txt — builds libpvhook.so for the Prime Video in-process ad strip.
#
# Hooks libignite's PLT/GOT import slots for memcpy/memmove/__memcpy_chk/
# __memmove_chk (see got_hook.h) and runs pvfilter::strip_remote_items() on the
# source buffer of any copy carrying a COMPLETE intraTitlePlaylist array before
# the real copy proceeds.
#
# NO third-party hooking library: the GOT swap is self-contained (got_hook.cpp).
# ShadowHook/ByteHook were both ruled out on this Onn 4K ROM — ByteHook's
# mandatory dl-init monitor depends on ShadowHook's linker-constructor hook,
# which fails here (errno 12). See got_hook.h and shadowhook/VENDORED.md.
#
# Build (armeabi-v7a — this app's only shipping ABI):
#   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -B build-v7a
#   cmake --build build-v7a
#
# Output: build-v7a/libpvhook.so  → goes into the APK's lib/armeabi-v7a/.

cmake_minimum_required(VERSION 3.18)
project(pvhook LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "armeabi-v7a")
    message(FATAL_ERROR "This app ships armeabi-v7a only; got ANDROID_ABI=${ANDROID_ABI}")
endif()

add_compile_options(-Os -fvisibility=hidden -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL)

add_library(pvhook SHARED
    hooks.cpp
    got_hook.cpp
    remote_strip.cpp
)
target_include_directories(pvhook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(pvhook PRIVATE ${log-lib})
