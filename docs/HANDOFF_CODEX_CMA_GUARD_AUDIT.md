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

## Verification already done

- `./gradlew :patches:build -x test` — passes (Kotlin changes are comments only).
- `bash -n testing/scripts/devicehealth.sh` — clean.
- meminfo parser unit-tested against real format (above).
- Executable bit set (`100755`), matching the other scripts.
- **Not** run against the device: the Onn is offline and intentionally untouched.
