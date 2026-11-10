# Codex audit request — CMA starvation guard (`c4ed051`)

**Repo:** `ajstrick81/morphe-androidtv-patches`
**Branch:** [`feat/paramount-17.3-ima-gate`](https://github.com/ajstrick81/morphe-androidtv-patches/tree/feat/paramount-17.3-ima-gate) (pushed 2026-11-09)
**Commit under review:** `c4ed051` — "docs(testing): catch Onn CMA starvation before blaming a patch"
**Scope:** 6 files, +363 / −39. No behavioural change to any patch.

Collaborative review, not sign-off — please disagree where the *reasoning* is
wrong, not only where the code is
([[feedback-collaborative-not-adjudicative]] posture).

## What this commit is

A diagnostic script plus documentation for a device failure mode, and
corrections to notes written before that mode was ruled out.

On the test device (onn 4K Plus "coffey", Amlogic S905X5M), exhausting
contiguous graphics memory (CMA) presents **identically to a broken patch**:
infinite spinner, no picture, decoder churning, audio advancing, and no FATAL /
AndroidRuntime / VerifyError / DRM error. The standard crash scan reports a
healthy app while playback is dead. The fix is a reboot, not a rebuild.

Misdiagnosed 2026-10-31, and again 2026-11-08 — the second time costing four
reinstalls spent bisecting a patch that static analysis had already shown to be
byte-correct and seam-identical to the working 16.21.0 build. The knowledge
existed only in session memory, nowhere in the repo. Hence this commit.

## Files

| File | What it is |
|---|---|
| `testing/scripts/devicehealth.sh` | **new** — the only executable code here. Reads `CmaFree`/`MemAvailable`, scans the log buffer for the starvation signature, exits non-zero when the device is too starved for a playback test to mean anything. |
| `docs/ONN_PLAYBACK_HEALTH_CMA.md` | **new** — tracked cross-app reference: signature, healthy contrast, thresholds, reboot fix, method lessons, incident table. |
| `testing/README.md` | script table + tree entry. |
| `patches/.../paramount/ParamountPatch.kt` | comments only. 17.3.0 downgraded from "NOT SUPPORTED" to "NOT YET SUPPORTED, pending a clean re-test". |
| `patches/.../paramount/Fingerprints.kt` | comments only. |
| `analysis/paramount/notes/recon-17.3.0.md` | marks all four 2026-11-08 device results confounded; withdraws an overreaching claim. |

## What I'd most like challenged

### 1. The script — the actual code

- **Thresholds.** `CMA_FREE_MIN_MB=60`, `MEM_AVAIL_MIN_MB=500`, derived from
  exactly two observations on one device: starved at `CmaFree` 35MB of 536MB
  with `MemAvailable` 425MB; healthy after reboot at 121MB and 746MB. I took
  midpoints. Sample size of two, and almost certainly wrong in general. Is a
  fixed MB floor even the right shape, versus a fraction of `CmaTotal`?
- **Regex breadth.** `STARVE_PATTERNS` is deliberately anchored because an
  unanchored `IMA` matches "primary" in `audio_hw_hal_primary` lines — that
  happened and buried the real hits. But I may have over-narrowed. Are
  `aml_cma_alloc` / `libdmabufheap allocation failed` / `api_disconnect failed`
  / `ion_alloc.*fail` the right set, and will they survive a kernel or vendor
  bump? `mali_gralloc.*(allocation failed|falling back to system heap)` is the
  loosest member.
- **`HEALTHY_PATTERNS`** (`ServiceDeviceTask.*INs=`, `kAtClient`, `res chg`) is
  advisory and never affects exit status. Useful, or noise that invites
  misreading?
- **meminfo parsing.** `awk -v k="^Field:" '$0 ~ k {print $2; exit}'`, kB→MB by
  integer division. Unit-tested against real `/proc/meminfo` format: reproduces
  35 / 536 / 425 exactly, and `^MemAvailable:` does not collide with `MemFree`.
  `CmaTotal`/`CmaFree` are absent on kernels that don't expose CMA, handled by
  reporting "not reported" rather than failing. Anything else wrong?
- **Deliberate deviation from the harness.** `lib.sh`'s `load_device_config`
  **dies** without `testing/config/device.env`, and the real patch loop runs
  `adb` by absolute path from `E:\` rather than through these scripts. So this
  script sources `lib.sh` for logging only and resolves the target itself:
  `ADB` env var or PATH, then `MORPHE_ADB_TARGET`, then the hardcoded coffey
  address `192.168.12.210:5555`. Reasonable pragmatism, or a footgun that will
  silently talk to the wrong device?
- **`set -euo pipefail`** arrives via `lib.sh`. I believe every `grep` that can
  legitimately not match is guarded with `|| true` — please verify, since a
  missed one makes the script exit silently mid-scan.
- Runs under Git Bash on Windows as well as Linux. `bash -n` passes.
  `shellcheck` is **not** installed here, so it has not been linted.

### 2. The reasoning, which matters more than the script

- **Is "all four device results are void" right, or too strong?** For: the tests
  ran across one session of repeated installs with no reboot, CMA was never
  checked, and this device is known to fail exactly this way. Against: live TV
  played throughout, which implies *some* graphics capacity was available. I
  judged that it doesn't rescue the results, because CMA pressure is graded and
  VOD plausibly needs larger contiguous allocations than live — but I am not
  confident, and this is the claim I'd most like attacked.
- **What stands on static evidence regardless:** the three patch seams are
  byte-identical between 16.21.0 and 17.3.0, and the patched dex is verified
  well-formed at all three injection sites — including that nothing was silently
  dropped, which is the failure mode Pluto's v17 Hook 3 documented (non-range
  invoke on a parameter above v15 → orphan `move-result` → VerifyError → player
  never starts).
- **Withdrawn claim.** I had written that 17.x VOD ad suppression "is a port of
  Pluto Hooks 3/4/8". Downgraded to a hypothesis: Pluto needs the DASH manifest
  strip because *its* VOD mid-rolls are FreeWheel-stitched, whereas Paramount+
  16.x VOD ads were fully handled by the `shouldPlayAd` gate alone. Different
  delivery, so the hooks don't automatically apply. Is that distinction right?
- **Doc overreach.** Does `docs/ONN_PLAYBACK_HEALTH_CMA.md` claim more than the
  evidence supports? Its method lessons ("prefer one resolve-only no-op build
  over bisecting hooks one at a time", "check the evidence you want can exist
  before spending installs collecting it") are generalisations from a single bad
  session and should be challenged if they don't hold.

## Open items, not in scope

- 17.3.0 VOD is untested from a clean (rebooted) device. That test has not run,
  so the headline question — is 17.3.0 actually broken? — is still open.
- The device currently holds a 17.3.0 all-`nop` build. Downgrading to 16.21.0 is
  blocked by `INSTALL_FAILED_VERSION_DOWNGRADE` (`-d` only permits downgrades
  for debuggable apps), so it needs `pm uninstall -k` or a full uninstall.
- `PARAMOUNT_TV.targets` is untouched; 17.3.0 is not offered to users.
- `docs/AGENT_PATCH_WORKFLOW.md` received the same guidance but is local-only via
  `.git/info/exclude`, so it is **not** in this commit. Tracked files were
  repointed to `docs/ONN_PLAYBACK_HEALTH_CMA.md` so a fresh clone doesn't chase
  a missing reference.
- **Release-type caveat for whoever merges:** the branch opens with
  `db79539 feat(paramount): …`, so merging as-is cuts a minor release for a
  branch whose net effect is a script plus docs (the gate it added was reverted
  in `a8b8db7`). Options considered: squash under a `docs:` subject, merge and
  accept a no-op bump, or cherry-pick `c4ed051` onto a `docs/` branch and leave
  the 17.3.0 investigation parked.

## Codex review outcome (2026-11-09) — 4 findings, all accepted

Reviewed against `c4ed051`. All four findings were confirmed by reading the code
and are fixed or addressed; three were genuine defects, one was overstated
prose. Fixes landed in the commit that follows this section's addition.

| # | Finding | Status |
|---|---|---|
| 1 | **Unknown reported as healthy.** Missing `CmaFree`/`CmaTotal` or `MemAvailable` left `STARVED=0`, so the script printed "Device health looks adequate" while unable to assess the main risk. For a preflight gate, the worst possible failure. | **Fixed.** Added an `UNKNOWN` state and a third exit code: `0` pass, `1` starved, `2` unknown. Missing fields and an empty log buffer both set it. The success line no longer claims health, and documents that a tripwire passing is not proof an allocation will succeed. |
| 2 | **Patterns described as "anchored" are unanchored substrings.** The comment contradicted the code. | **Fixed.** Comment now says specific, case-sensitive, *not* regex-anchored, and states that specificity — not an anchor — is what prevents incidental matches. Tightened `mali_gralloc` to require the dmabufheap / system-heap phrasing, and `error pushing blank frames` to require the `SurfaceUtils` tag. Recorded as NOT YET validated against a device-log corpus. |
| 3 | **`MORPHE_ADB_TARGET` silently ignored when `device.env` exists.** Could point the check at a different device than intended. | **Fixed.** Override now applied after `load_device_config`, with explicit precedence (env > config > default) and a warning when it overrides. The resolved target is printed on every run. |
| 4 | **"All four results are void" too categorical.** Evidence supports confounded-with-unknown-device-state; live playback shows some graphics capacity remained. | **Accepted.** Softened in `docs/ONN_PLAYBACK_HEALTH_CMA.md`, `analysis/paramount/notes/recon-17.3.0.md` and the patch comments: confounded, weak evidence, device state unknown, cannot carry a verdict alone — explicitly *not* worthless. The live-TV observation is now recorded as arguing against assuming total starvation. |

Also adopted from the review's reasoning section:

- Thresholds are now labelled **provisional, device-specific tripwires**, not a
  definition of starvation, and raw values are always printed so the numbers are
  what you reason from.
- Added a **fraction-of-`CmaTotal`** tripwire (`CMA_FREE_MIN_PCT`, default 15%)
  alongside the absolute floor, since a fixed MB floor doesn't survive a change
  in `CmaTotal`. Validated against both measured states: 35/536 = 6% trips,
  121/536 = 22% passes, and an in-between 80MB/536MB = 14% now trips where the
  absolute floor alone would have passed it.
- `HEALTHY_PATTERNS` kept explicitly advisory, with a note that the markers may
  predate the test in question and so are context rather than proof.
- The "every result after that point is worthless" method lesson was reworded to
  "confounded; don't use alone for a verdict".

Not adopted, with reasons: nothing. The one judgement call left open is whether
`UNKNOWN` should exit non-zero at all — it makes the script unusable as a gate
on any kernel that never reports CMA. Chosen behaviour is to exit `2` and say so
explicitly, so a caller can decide, rather than silently passing.

## Round two — review request (2026-11-09)

**Commits to review:** `0c0dce2` (the four fixes) and the follow-up commit
containing this section. Same branch.

Round one was useful precisely because it attacked the reasoning and not only
the code, so please do that again. Three specific things, in priority order.

### 1. Is `UNKNOWN` → exit 2 the right call? (the one open judgement)

Round one correctly said an unassessable result must not read as adequate. I
implemented that as a third exit code, `2`. The cost: on any kernel that never
reports `CmaFree`/`CmaTotal`, the script can never return `0`, so as a literal
gate it becomes permanently red and will be ignored or bypassed — the classic
fate of a check that cries wolf.

Alternatives I considered and rejected, and would like second-guessed:

- exit `0` with a loud warning → back to the false all-clear round one caught
- a `--strict` flag, default lenient → the dangerous behaviour becomes the
  default, which seems backwards for a safety check
- probe once and cache "this kernel has no CMA" → silently converts a real
  regression (CMA reporting disappearing after a kernel bump) into a permanent
  pass

Chosen behaviour is loud and explicit rather than convenient. Is there a better
shape — e.g. distinguishing "field absent on a kernel that never had it" from
"field absent where it previously existed" by recording a known-good baseline?

### 2. Sensitivity vs specificity in `STARVE_PATTERNS`

I made the tradeoff explicit in the code rather than tightening quietly. The
argument: a false positive costs an unnecessary reboot (~2 minutes), a false
negative costs a misdiagnosed session (which is why this script exists), so the
patterns deliberately err toward catching it. Concretely, `api_disconnect
failed` is kept **tag-free** even though the only observed form is on a
`SurfaceUtils` line, so a starvation logging it under another tag can't slip
through.

Is that asymmetry argument sound, or am I rationalising a pattern set I can't
validate? The device is offline, so none of this has been checked against a real
log corpus — if you think the unvalidated-but-broad position is worse than
unvalidated-but-narrow, say so.

Self-found since round one, already fixed: the `mali_gralloc` branch was
redundant. Its first alternative repeated the bare `libdmabufheap allocation
failed` pattern, so the observed line matched twice and the branch added nothing
but `falling back to system heap`. Simplified, and regression-checked that all
three observed starvation lines still match while the `audio_hw_hal_primary`
decoy (the original false-positive source) does not.

### 3. Did the fixes actually do what they claim?

Worth verifying independently rather than taking the commit message's word:

- every path that sets `UNKNOWN` (absent `CmaFree`/`CmaTotal`, absent
  `MemAvailable`, empty log buffer) really reaches `exit 2`, and `STARVED` still
  wins over `UNKNOWN` when both are set
- the `MORPHE_ADB_TARGET` override applies in **both** branches now, and the
  printed target always matches the device actually queried
- `set -euo pipefail` is still satisfied — the new `local pct=$(( ))` arithmetic
  and the added early `return` in `scan()` are the new risk surface
- the percentage tripwire: 35/536 = 6% trips, 121/536 = 22% passes,
  80/536 = 14% trips. Is 15% defensible as a midpoint, or is two data points
  simply too few to express as a percentage at all?

### Still out of scope

17.3.0 VOD remains untested from a clean device, so the headline question is
unchanged. No patch behaviour has been altered by any of this.

## Round-two outcome (2026-11-09)

Codex confirmed the four round-one fixes independently (unknown and starved
handled separately, `STARVED` winning when both set, override applied after
config load, success message no longer claiming health, `bash -n` clean) and
raised one new edge case plus two framing points.

### New finding, accepted and fixed: empty log buffer outranked good telemetry

An empty log buffer set the same `UNKNOWN` flag as unreadable memory values, so
`check` returned "unassessable" even when `CmaFree`/`MemAvailable` were read
fine and cleared the tripwires. That is a real defect, and worse than it looks:
`logs.sh` runs `adb logcat -c`, and so did every one of the 2026-11-08 test
runs, so **the natural "run it just before the test" position would have
returned unknown on a perfectly healthy device** — exactly the cry-wolf outcome
that gets a gate bypassed.

Fixed by splitting the two kinds of unknown, as suggested:

| Code | Meaning |
|---|---|
| `0` | pass |
| `1` | starved (values below tripwires, **or** signature present — historical evidence, so the recent test is suspect even if memory has since recovered) |
| `2` | telemetry unknown — memory values unreadable, main risk unassessable |
| `3` | scan inconclusive — memory fine and passing, log buffer empty so the signature couldn't be looked for; the expected result right after `logcat -c` |

Precedence verified across all six meaningful flag combinations: starved
outranks both unknowns, and telemetry unknown outranks an inconclusive scan,
since the former means the risk is unmeasurable while the latter only means the
corroborating history is absent.

A baseline for detecting CMA reporting disappearing after an update was
discussed and **not** implemented, per the review's own caveat: it must not turn
a currently-missing field into a pass without an explicit policy, and no such
policy exists yet.

### Framing points, accepted without code change

- **Pattern sensitivity.** The asymmetric-cost argument holds for a diagnostic
  that reports evidence and prompts review, and is weaker where a single broad
  hit auto-exits `1` — benign surface teardown could cost an unnecessary reboot.
  Kept as-is because an unnecessary reboot is cheap against a misdiagnosed
  session, but the set is explicitly provisional and the corpus-validation gap
  is now stated both in the script and here. Revisit with real logs, not
  reasoning.
- **Threshold.** 15% stands as a provisional midpoint on two observations. Worth
  recording plainly: the percentage and the absolute floor are derived from the
  *same* two measurements, so they are one measurement expressed two ways, not
  two independent checks. The raw values and the device-specific caveat are what
  make it honest.

### Limitation that remains, and cannot be reviewed away

The device is offline, so nothing here has been exercised end-to-end against a
real device or a real log corpus. Code review has taken this as far as it goes;
the outstanding checks need device data.

## Round-three outcome (2026-11-09)

Codex confirmed the exit-code split matches the documented precedence, and found
one remaining mismatch: an unreadable `/proc/meminfo` went through `die()`,
i.e. **exit 1**, while the documented contract says unreadable telemetry is 2.
An adb hiccup would therefore have read as a starvation verdict.

Accepted — and on inspection the problem was larger than the one line. `die()`
comes from `lib.sh` and always exits 1, so **every** `die` path in the script
collided with the starvation code:

| Path | Was | Now |
|---|---|---|
| `adb` not found | 1 (= starved) | 4 |
| device unreachable | 1 (= starved) | 4 |
| `/proc/meminfo` unreadable | 1 (= starved) | 2, and no longer fatal |
| bad usage / unknown mode | 1 (= starved) | 4 |
| genuine starvation verdict | 1 | 1 (the only remaining `die`) |

The usage case is the sharpest illustration: an automated gate keyed on exit 1
would have rebooted the TV because of a typo in the mode argument.

Fixes:

- Added `fail()` (exit 4) for "could not run the check at all", and repointed
  the three setup/connectivity/usage paths to it. `die()` is now used for
  exactly one thing: the starvation verdict.
- The unreadable-`/proc/meminfo` path now sets `UNKNOWN_TELEMETRY` and
  **returns instead of exiting**, so `check` still runs the log scan — the
  signature can be found even when meminfo could not be read, which the old
  hard exit prevented. Its message distinguishes an adb/read failure from a
  kernel that simply omits the CMA fields, since both land on exit 2.

Verified end-to-end (no device needed for these):

- `ADB=/definitely/not/adb … check` → exit **4**, not 1.
- `MORPHE_ADB_TARGET=192.0.2.1:5555 … check` → exit **4**, and it printed
  `MORPHE_ADB_TARGET=192.0.2.1:5555 overrides device.env (192.168.12.210:5555)`.
  That incidentally proves round one's finding 3 was a **live** bug rather than
  a theoretical one: `testing/config/device.env` exists on this machine, so the
  override was taking the ignored branch every time.
- `bash -n` clean; `die` now appears exactly once.

Unchanged and still outstanding: pattern accuracy and end-to-end device
behaviour cannot be validated without device logs.

## Verification already done

- `./gradlew :patches:build -x test` — passes (Kotlin changes are comments only).
- `bash -n testing/scripts/devicehealth.sh` — clean.
- meminfo parser unit-tested against real format (above).
- Executable bit set (`100755`), matching the other scripts.
- **Not** run against the device: the Onn is offline and intentionally untouched.
