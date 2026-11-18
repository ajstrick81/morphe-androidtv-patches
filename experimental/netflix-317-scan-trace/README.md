# #317 scan-trace diagnostic (Netflix, Fire TV Stick 3rd Gen)

Diagnostic build for [#317](https://github.com/ajstrick81/morphe-androidtv-patches/issues/317). Not shipped by any patch.

## What we know

- On AFTSSS (Fire OS 7 / Android 9 / ARM32) the clone dies with `SIGSEGV / SEGV_ACCERR` inside Frida's
  fault-recovery path. That happens on both gadget 17.x and 16.2.1, about 5 s after `KILL: killads ready`.
- Swapping `libgadget.script.so` for a one-line script removes the crash (sqzr2k, 2026-11-16).
  So the cause is something `killads.js` does, not the gadget alone.
- With the household features off (the default), every scan in `killads.js` is a synchronous
  `Memory.scanSync`. Three stages start in the first 5 s:
  - `fastMASTER`: starts at +0.2 s and repeats every 120 ms for about 60 s. It covers `jsRanges()`
    (anonymous memory only) and `rwRanges()` (which **includes private file-backed mappings**).
  - `apply`: starts at **+5 s**, which matches the crash time. It covers `rwRanges()` (including
    file-backed mappings) and scans `nrdp.gibbon` across everything.
  - `dumpMASTER`: does its first real scan at +34 s, so it isn't the cause.
- V3's M1 guard search scans a fixed 2 KB window *before* a hit (`cand.sub(2048)`). That window can
  start before the hit's mapping. A read across a mapping boundary is a plausible fault source.

## Files

| File | |
| --- | --- |
| `prelude.js` | Diagnostic wrappers (scan trace, anomaly flags, file-backed skip, stage disable). |
| `build.sh` | `prelude.js` + the current shipped `killads.js` → `killads-317-trace.js`. |
| `killads-317-trace.js` | Generated drop-in replacement for `lib/armeabi-v7a/libgadget.script.so`. |

The ad-kill logic is byte-identical to the shipped script. The prelude only observes, except
when you turn on the switches below.

## Switches (top of the file)

| Variable | Default | Use |
| --- | --- | --- |
| `DIAG_TRACE_MS` | `30000` | Logs every scan for this long after load. Anomalies are always logged. |
| `DIAG_ANON_ONLY` | `false` | **Test 2.** Never scans file-backed mappings. The JS source lives in anonymous memory, so the ad-kill should still land. |
| `DIAG_DISABLE` | `[]` | **Test 3.** Stage names to never run: `'apply'`, `'fastMASTER'`, `'dumpMASTER'`, `'anchorMap'`. |
| `DIAG_EXC_LOG` | `false` | Optional. Logs the native fault Frida sees. It may itself change behaviour, so use it only as a last step. |

## Test order

```
adb logcat -G 16M
adb logcat -c
adb logcat -s KILL > k317.log
```

1. **Trace (no switches).** Reproduce the crash and post `k317.log`. The last `DIAG S>` line before the
   log stops names the range, its protection and its backing file. Any `[UNMAPPED]`, `[NOREAD]` or
   `[SPAN]` flag is the prime suspect. `DIAG alive` heartbeats once a second fix the crash time.
2. **`DIAG_ANON_ONLY = true`.** Does the app stay up? Do `PATCH V3-ADV` / `PATCH V3-MASTER` still
   log `verify=OK`, and are ads gone? If both answers are yes, this is the fix.
3. Only if 2 still crashes: try `DIAG_DISABLE = ['apply']`, then `['fastMASTER']`.

Note: `Process.findRangeByAddress` runs before every scan, which slows each pass. If the crash is
timing-sensitive, a trace run may survive. That result is informative too, so report it.
