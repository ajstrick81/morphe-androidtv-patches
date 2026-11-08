# Prime Video v16 native recon

Reverse-engineering notes and prototypes for the v16 (`6.24.x`, engine `v16.0.x`)
native ad path. `RECON_NOTES.md` has the decompiled chain.

## Provenance — read this first

All of this was rescued on 2026-11-07 from `stash@{0}`'s untracked-files commit
(`5d21afd`, *"On feat/espn-scoreboard-card: WIP espn-scoreboard + tubi skipads
refactor…"*). It had never been committed to any branch. The binary built from it
was running on the Onn 4K test unit and suppressing v16 mid-rolls, so the only
copy of working, field-proven code lived in a stash entry — one `git stash drop`
from gone.

Two process points worth keeping:

- **A stash is not storage.** The stash stack is shared across worktrees here, so
  another session can pop or drop it. Work that runs on a device belongs on a
  branch.
- **Unrecorded work gets rediscovered the hard way.** While this sat in the
  stash, the committed docs said v16 ad suppression was out of reach, and a later
  session (this one) re-derived the loader-fingerprint problem from scratch
  before the device log revealed the inline patch already existed.

## What is compiled

| File | Status |
|---|---|
| `jni/ignite_inline_patch.{cpp,h}` | **Shipping.** Wired into `CMakeLists.txt`. The v16 mid-roll kill. |

It inline-patches the `chapteringAdBreak` parser (`FUN_00dd00c0`, ELF vaddr
`0xDC00C0` on 6.24.7 armeabi-v7a) with a 6-byte Thumb stub forcing the parsed
flag false, so the engine never schedules a mid-roll break.

Consequences to keep in mind:

- **This is why a working v16 session logs no `PVKILL` or `rego_*` activity.** No
  break is requested, so no ad response is ever copied for the GOT strip to empty.
  Silence from the strip on v16 is success, not failure — the line to look for is
  `inline: chapteringAdBreak parser neutralized`.
- **armeabi-v7a only.** `apply_adbreak_patch()` refuses on other ABIs before
  reading anything; the offset is meaningless on arm64 and the read itself could
  fault. A 64-bit v16 install therefore keeps its mid-rolls until the arm64
  parser offset is found.
- **Offset is version-pinned.** A prologue guard (`push {r4-r7,lr}; add r7,sp,#12`)
  is checked before the write, so version drift logs a mismatch and skips rather
  than corrupting code. Verified on 6.24.7; `6.24.13`'s `libignite.so` is 27 KB
  larger, so expect the offset to have moved and the patch to skip until re-found.

## What is preserved but NOT compiled

| File | Why |
|---|---|
| `jni/timeline_action_patch.{cpp,h}` | Device result: `0/3 StartTimelineAction variants neutralized`. The `Native_StartTimelineAction*` strings are present in rodata but nothing in the data section points at them, so they aren't reachable through the NativeSymbol table. Kept for the diagnostics and the search strategy; it only produced log noise. |
| `jni/xray_{dump,strip}.h` | X-Ray / LiveExplore shop-and-betting overlay work (`liveExploreUpdatesV3`). Never wired up. |

Re-wiring either means adding it back to `CMakeLists.txt` and calling it from
`hooks.cpp`'s worker loop, the way `ignite_inline_patch` is called.

The two `libignite.so` copies that were in the same stash entry are deliberately
**not** committed — they are Amazon's binaries, and `.gitignore` policy here
keeps app binaries out of the repo. Pull them from the APK when needed:

```bash
unzip -j <bundle>.apkm split_config.armeabi_v7a.apk -d /tmp/pv
unzip -j /tmp/pv/split_config.armeabi_v7a.apk 'lib/armeabi-v7a/libignite.so' -d .
```
