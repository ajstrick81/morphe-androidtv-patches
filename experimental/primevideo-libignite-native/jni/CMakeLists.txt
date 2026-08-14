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

# TV-show ad suppression (verified on-device 2026-07-30, Onn 4K):
#   PV_EMPTY_REGOLITH — empty the regolith getVideoAds RESPONSE playlist (the SGAI
#                       mid-roll/preroll path). Uses the app's own clean empty-break
#                       path, so no ghost period on resume/scrub, and edits only the
#                       ad-decision response (never the gzip'd player bundle).
# The movie strip (strip_remote_items) remains unconditional. Diagnostics are OFF by
# construction — manifest_diag.cpp is not compiled into the shipping library. (A JS
# "count-gate" was prototyped and rejected — it corrupts the bundle download; see
# hooks.cpp.)
#
# Issue #14 restart fix (verified on-device 2026-08-14, Onn 4K, Reacher):
#   PV_SKIP_GVA_REMOTES — strip_remote_items LEAVES intraTitlePlaylist Remote ad
#                         items that resolve via /cdp/getVideoAds, so the app
#                         resolves them and PATH 2 (PV_EMPTY_REGOLITH) empties the
#                         resolved response via the app's clean empty-break path.
#                         Structurally blanking them (the old behavior) left the
#                         player's PeriodTailor a full-ad-length gap it filled with
#                         non-existent media -> content restarted to the beginning
#                         while the timeline kept running, subtitles dropped, and
#                         seeking across the gap buffer-locked ("something went
#                         wrong"). See remote_strip.cpp / hooks.cpp.
target_compile_definitions(pvhook PRIVATE PV_EMPTY_REGOLITH=1 PV_SKIP_GVA_REMOTES=1)

find_library(log-lib log)
target_link_libraries(pvhook PRIVATE ${log-lib})
