# Onn TV playback health: CMA / graphics-memory starvation

**Applies to every app in this repo, not one of them.** This is a property of the
test device (onn 4K Plus "coffey", Amlogic S905X5M), so it can corrupt a patch
verdict for Paramount+, Pluto, ESPN, Prime Video or anything else.

## The problem

Playback fails with an **infinite spinner and no picture**, while the app itself
looks perfectly healthy. The decoder is running the whole time — frame buffers
churn, audio advances — but decoded frames can never get a display surface,
because the device is out of **contiguous graphics memory (CMA)**.

It is the single most convincing false positive for "the patch broke playback"
that this repo has encountered, because a patched build is usually the thing
that changed most recently.

## Why a normal crash scan misses it completely

The usual scan is:

```bash
adb logcat -d | grep -iE "FATAL|AndroidRuntime|VerifyError|NoClassDefFound|UnsatisfiedLink"
```

Starvation produces **none** of those. No fatal, no Java exception, no
VerifyError, no DRM error, nothing in the app process at all. The scan comes
back clean and the natural conclusion — "the app is fine, so the hook must be
wrong" — is exactly backwards.

## Signature

```
aml_cma_alloc heap-gfx: alloc failed ret:-12          # ENOMEM
mali_gralloc libdmabufheap allocation failed, falling back to system heap
SurfaceUtils: error pushing blank frames: api_disconnect failed
```

plus a low `CmaFree` in `/proc/meminfo`. Also seen: `ion_alloc` failures.

Measured on coffey:

| State | CmaFree | MemAvailable | Playback |
|---|---|---|---|
| Starved | 35MB of 536MB | 425MB | spinner, no picture |
| After reboot | 121MB | 746MB | plays fine |

## What healthy playback looks like instead

- `VDA ServiceDeviceTask INs=` and `OUTs=` both climbing **in lockstep** to
  `kAtClient` — frames are reaching the client.
- A clean mid-stream `res chg` at a resolution switch.

If the decoder is clearly working but those markers never appear, suspect the
display surface, not the ad patch.

## The fix is a reboot, not a rebuild

```bash
adb -s <device> reboot
```

CMA fragments with uptime and is consumed by other foreground media apps
(YouTube TV, cast `mediashell`, a long recent-tasks list). Close those and clear
recent tasks before a test that matters.

## Check it, don't assume it

```bash
testing/scripts/devicehealth.sh check          # snapshot + log scan; exit 1 if starved
testing/scripts/devicehealth.sh snapshot       # CmaFree / MemAvailable / uptime only

# From the manual loop, where adb is not on PATH and config/device.env may not exist:
ADB=/e/Android/Sdk/platform-tools/adb.exe MORPHE_ADB_TARGET=192.168.12.210:5555 \
    testing/scripts/devicehealth.sh check
```

Thresholds are `CMA_FREE_MIN_MB` (default 60), `MEM_AVAIL_MIN_MB` (default 500)
and `CMA_FREE_MIN_PCT` (default 15), all overridable by environment variable.
They are **provisional device-specific tripwires** derived from the two measured
states above, not a definition of starvation — clearing them does not prove an
allocation of a given size will succeed. Read the raw values the script prints,
not just the verdict.

Exit codes:

| Code | Meaning |
|---|---|
| `0` | pass — no signature, and every value read clears the tripwires |
| `10` | **starved** — values below the tripwires, or the signature is in the log. The signature is *historical*: if present, the recent test is suspect even if memory has since recovered, because killing the app frees CMA |
| `2` | **telemetry unknown** — the memory values themselves were unreadable, so the main risk is unassessable. Not an all-clear |
| `3` | **scan inconclusive** — memory read fine and passed, but the log buffer was empty so the signature couldn't be looked for. This is the *expected* result right after `adb logcat -c`, which `logs.sh` and our own test procedure both do, so don't confuse it with `2` |
| `4` | **could not run** — any error: `adb` missing, device unreachable, unreadable `/proc/meminfo`, malformed `device.env`, bad usage |
| anything else | **unexpected failure** — treat as an error, never as a verdict |

The verdict is `10`, not `1`, on purpose. `1` is what the shell itself produces
in all sorts of circumstances — `set -e` on a failing assignment, a failure
inside `lib.sh` before the script can override `die()`, paths nobody has
enumerated. Trying to prove "no error can exit 1" failed three separate review
rounds; giving the verdict a status nothing else emits is airtight where
enumeration is not. **Callers must treat anything outside `{0, 2, 3, 4, 10}` as
an error.**

Target precedence is `MORPHE_ADB_TARGET` > `config/device.env` > the known
coffey address, and the resolved target is printed on every run.

The exit contract is regression-tested without a device by
`testing/scripts/devicehealth-selftest.sh`, which drives the script against a
fake `adb` returning canned `/proc/meminfo` and logcat output and asserts the
code for each case (healthy, starved, signature-only, failing read, kernel
without CMA fields, empty buffer, missing adb, bad mode, malformed
`device.env`). It cannot validate the log *patterns* — that still needs real
device logs.

**Run it before and after any playback test.** A test that begins on a starved
device produces no usable evidence in either direction.

## Why this matters for how you debug, not just what you grep

Install → force-stop → relaunch churn is itself a way to exhaust CMA. So a long
session of reinstall-and-retry can *manufacture* this failure and then read it as
evidence about whichever build happened to be installed at the time.

The honest framing is **confounded, not worthless**. Without a health check at
the time, you cannot tell whether a given result reflects the build or the
device — so the result is weak evidence with an unknown device state behind it,
and must not carry a verdict on its own. It is not nothing; it is just not
decisive. (An earlier version of this document said every result after the
device goes starved is "worthless". That overstated what one incident
establishes — thanks to Codex for the correction.)

Practical consequences:

- Reboot **before** a verdict-grade test, and re-check health after it.
- If a result followed several installs with no reboot, treat it as confounded:
  keep the observation, record that the device state was unknown, and don't
  settle anything with it alone.
- Prefer a **resolve-only no-op build** (every injection reduced to `nop`, so
  fingerprints still resolve but nothing changes) over bisecting hooks one at a
  time. If the symptom persists on that build, the patch is a bystander and
  hook-by-hook bisecting cannot find anything.
- Check that the evidence you plan to collect can exist at all. AVIA
  (Paramount+, Pluto v17) logs nothing about ad decisions at default verbosity,
  so install-and-compare cannot distinguish two builds no matter how many times
  it is repeated.

## Incidents

| Date | Misread as | Actual |
|---|---|---|
| 2026-10-31 | stale 16.19 client bug | stale client **and** CMA starvation; fixed by a fresh patched 16.21 install plus a reboot |
| 2026-11-08 | Paramount+ 17.3.0 patch bug | never determined — four device tests ran across repeated installs with no reboot and no CMA check, so the device state behind each is unknown and none can carry a verdict. Note live TV played throughout, so the device was not starved to the point of no graphics capacity at all. See `analysis/paramount/notes/recon-17.3.0.md` |

The 2026-11-08 session spent four reinstalls bisecting a patch that static
analysis had already shown to be byte-correct and seam-identical to the working
16.21.0 build. This document exists so that is not repeated.
