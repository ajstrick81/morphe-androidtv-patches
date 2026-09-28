# Testing discipline

The transform is the part that must be *correct* — a wrong strip is a black
screen or a survived ad. It's also pure C/C++, so it's cheap to test to death on
your PC. This is where the project buys its confidence. The example's four
suites (103 + 34 + 75 + 28 assertions) were all built this way.

## 1. Everything pure is host-tested

Keep the transform Android-free (no JNI, no zlib-on-device dependency in the
logic itself) so it compiles with a plain `g++` and runs on the PC. Use
`scaffold/jni/test_harness.h`:

```
g++ -std=c++17 -D_GNU_SOURCE -Wall test_<x>.cpp <x>.cpp -o /tmp/t && /tmp/t
```

## 2. One visible `ok:` per assertion

The harness prints a line per passing check on purpose: a green run is then
**self-evidently non-vacuous** — you watch each assertion execute. A suite that
prints only "PASSED" can hide a block that never ran.

## 3. Cover the edges that actually bite

For an ad-strip transform, the load-bearing cases are:
- **complete vs truncated** payload (truncated MUST be left byte-identical —
  this is the black-screen guard);
- ad element **first / middle / last / all** (comma handling → the output must
  still be valid);
- **whitespace** variants around keys/colons;
- **brackets/quotes/escapes inside strings** (a URL containing `]` `}` `,`);
- **size gate** boundaries (below min, above max);
- **idempotence** (running twice changes nothing the second time).

Back "output is still valid" with a real structural validator, not substring
spot-checks. The example ships a small JSON validator in `test_prs_blank.cpp`
(and self-tests the validator).

## 4. Mutation-test — the non-negotiable step

A passing suite proves nothing until you've proven it can FAIL. Deliberately
break the code and confirm the suite goes red:

```
# e.g. neuter the strip, or make the "is it complete?" gate always return true,
# then rebuild the test and confirm non-zero exit + FAILs.
```

Do this for each suite, in a scratch copy so the real tree is untouched. If a
mutation *doesn't* turn the suite red, your test isn't exercising that path —
fix the test. (This is the direct analog of "confirm the hook fires": never
trust a green you haven't seen turn red.)

## 5. Sanitizers

Run every native suite under ASan + UBSan before trusting it:

```
g++ -std=c++17 -D_GNU_SOURCE -g -O1 -fsanitize=address,undefined \
    -fno-omit-frame-pointer test_<x>.cpp <x>.cpp -o /tmp/t
ASAN_OPTIONS=detect_leaks=1 /tmp/t
```

Catches the buffer overruns / UB that `-Wall` can't. The example is clean under
this plus `-Wextra -Wpedantic -Wshadow -Wconversion -Wsign-conversion`.

## 6. What you can't host-test

The seam wiring (hook install, HTTP framing, per-handle keying, on-device
offsets) is device-only. Keep it *thin* and push all real logic into the pure,
tested transform. Verify the wiring on-device by **effect** (logcat shows N
stripped, ad gone on screen), never by "patch applied."
