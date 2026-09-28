// test_harness.h — the tiny host-test harness used across this toolkit.
//
// Deliberately dependency-free (just <cstdio>): every ad-strip transform is
// pure C/C++ and host-testable, so you compile the transform + its test with a
// plain g++ and run it on your PC — no NDK, no device, no gtest.
//
// Two conventions, both used in the Prime Video example:
//   * CHECK(cond, "msg")  — prints `ok: msg` on pass, `FAIL: msg (file:line)`
//     on fail, and increments a global counter. Printing one line PER PASS is
//     intentional: a green run is then self-evidently non-vacuous — you can SEE
//     each assertion execute. (The bench-day lesson: never trust a silent
//     "0 failures" you can't watch run.)
//   * TEST_MAIN_END() — prints the summary and returns non-zero if anything
//     failed, so the binary's exit code drives CI / a shell &&-chain.
//
// ALWAYS pair a new suite with a mutation check: deliberately break the code
// under test and confirm the suite goes RED. A test that passes no matter what
// the code does is the same false-confidence trap as a hook that never fires.
#pragma once

#include <cstdio>

static int g_ok = 0;
static int g_fail = 0;

#define CHECK(cond, what) do { \
    if (!(cond)) { std::printf("FAIL: %s  (%s:%d)\n", (what), __FILE__, __LINE__); ++g_fail; } \
    else         { std::printf("ok:   %s\n", (what)); ++g_ok; } \
} while (0)

#define TEST_MAIN_END() do { \
    std::printf("\n%s (%d check(s), %d failure(s))\n", \
                g_fail ? "TESTS FAILED" : "ALL TESTS PASSED", g_ok + g_fail, g_fail); \
    return g_fail ? 1 : 0; \
} while (0)
