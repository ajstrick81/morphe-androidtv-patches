# PV v16 libignite native ad-seam recon — session start (2026-09-16)

Device-verified on `.211` (patched 6.24.6 clone, mid-roll on "Neagley"): PV v16 ad pipeline is
compiled Rust→WASM + native C++ inside **libignite**. No Java / resident-JS / DNS-visible seam
(see memory `primevideo-v16-native-ad-move`). Target ABI = **armeabi-v7a** (device `ro.product.cpu.abi`
= armeabi-v7a; the app maps the v7a lib). This file records the native anchors found so the next
session can go straight to the Ghidra xref pass.

## Blobs
- `v7a/libignite.so` — 16,322,636 bytes — **the device target** (32-bit Thumb).
- `arm64/libignite.so` — 20,406,120 bytes (reference only; device is 32-bit).
- Extracted from `testing/apks/pv-v16-universal.apk` (merged v16 6.24.6+v16.0.0.279-allAbis).

## Exports (both ABIs)
Only 18 `Java_com_amazon_ignitionshared_*` JNI stubs (v15 had 11; v16 adds initializeCAres,
setFastInputPathEnabled, GMBMessageSender_sendGMBMessage, joystick/touch/trigger dispatch).
**No SSL/http/zlib/ad exports** — everything internal (as v15). Statically links OpenSSL 1.1.1,
nghttp2, c-ares, zlib (build path prefix `/codebuild/output/src147213523/src/src/`).

## v7a section layout (offset→vaddr)
- `.rodata` vaddr `0x000e72b0` == file offset `0x0e72b0`  → **rodata string file-offset == vaddr**.
- `.text`   vaddr `0x007b5e50` (Thumb; attach addrs need bit0=1).
- `.data.rel.ro` vaddr `0x00f5e490`, `.data` vaddr `0x00f8d8c0`.

## ⭐ Native ad-timeline anchors (v7a rodata vaddr == offset)
The mid-roll ad-break lives in a **native "Timeline" struct dump** with a `chapteringAdBreak` field
(NOT the SYE TileStitcher — that cluster, `ignite::sye::TileStitcherContext` / TILE_GEOMETRY_2PLUS2 /
1PLUS3, is multiview live-mosaic compositing, a red herring for VOD ads):

| vaddr | string | role |
|---|---|---|
| `0x14ce5e` | `, chapteringAdBreak=` | Timeline dump field — **primary xref target** |
| `0x14946c` | `    Timeline duration=` | Timeline dump header (same fn) |
| `0x13b9b4` | `chapteringAdBreak` | field name |
| `0x13868e` | `, updating stitcher geometry` | timeline/geometry update log |
| `0x125000` | `…/ignite-lib/src/megablast.cpp` | source unit |

WAMR host-fn family (the API the WASM calls into native — 792 `Native_*` total):
- `Native_SupportsTimelineActionId` (@0x102cb1), `Native_GetTimelineActionId` (@0x102cd1),
  `Native_StopTimelineAction` (@0x121e25), `Native_FrameMarker` (@0x14b2de) — **timeline-action /
  ad-break mechanism** (ad breaks likely modeled as "timeline actions").
- `Native_MPBInstance_*` (PlaybackInit/Play/Pause/Seek/Stop/Shutdown/GetPlaybackTime/
  SendAudioMetadata/SendVideoMetadata) — Media Pipeline Backend control surface.
- `Native_HTTP_*` / `Native_Downloader_*` (SetResolvedAddresses, StickyDNSOverrides, DoH) — the
  network host-fns; `HTTP_CreateHandler` present (v15 WAMR NativeSymbol table-scan FAILED — likely
  hashed dispatch; do NOT re-run that exact scan).
- PRS-arrival trigger anchors still present: `OpenSSL SSL_read: %s, errno %d` @0x107418,
  `SSL_write` @0xec86c (static BoringSSL — v15 PIC-xref blocker; use Ghidra, not byte-scan).

## Next session — the RE
1. Ghidra-load `v7a/libignite.so`; find code xrefs to `0x14ce5e` / `0x14946c` → the native
   **Timeline dump** fn → recover the Timeline struct and where `chapteringAdBreak` entries are added.
2. Cross-check the `Native_*TimelineAction*` family: is an ad break a "timeline action"? If so,
   neutering `Native_GetTimelineActionId` / the action-add path may drop the break natively.
3. Design a **ShadowHook `.so`** strip (reuse `experimental/primevideo-libignite-native/jni/`; NOT
   Frida — embedded-gadget script crashes at playback start per `primevideo-atv-track-a-gadget-crash`).
   Prefer **neutralize-in-place** (rename a key/type byte / zero the ad-break count) over resize
   (removing entries shrinks the struct/JSON — the v15 in-place length-change problem).
4. Verify on-device: patched v16 clone is installed on `.211`; play a Reacher-universe title
   ("Neagley") mid-roll. `Constants.kt` has a **LOCAL-ONLY** v16 AppTarget (`6.24.6+v16.0.0.279-allAbis`)
   — revert before any release.

Tools: NDK llvm at `C:\Users\Adam\AppData\Local\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin`.

## ⭐⭐⭐ Ghidra xref pass DONE — seam RESOLVED (2026-09-16)
The v15 "PIC-xref / hashed-dispatch" blocker is BEATEN. Reused the prior WAMR resolver method
(`ghidra_work/FindHttpImpl.java` → adapted to `ghidra_work/FindAdTimeline.java`): the NativeSymbol
reg-table references the name string, impl fn-ptr loads within ~14 instrs → Ghidra resolves the
Thumb-2 PC-relative refs. Ran headless against the ALREADY-ANALYZED 6.24.5 project (`proj_6245c/ign6245`).

**6.24.5 (libignite_v7_6245.so, IMAGE_BASE 0x10000) resolved addresses — structural map (device is 6.24.6, re-point offsets from proj_6246):**
Host-fn IMPLs:
- Native_GetTimelineActionId       IMPL=0x82e468
- Native_SupportsTimelineActionId  IMPL=0x839a34
- Native_StopTimelineAction        IMPL=0x839bc4  (Native_FrameMarker also resolved here — verify, likely shared helper mis-grab)
- Native_MPBInstance_PlaybackInit  IMPL=0x868fc4
Native Timeline function cluster (0xd4xxxx–0xd7xxxx = one code unit):
- "chapteringAdBreak" (bare field)  -> FUN_00d56c90, FUN_00d69bf0   ← PARSER/BUILDER = neutralize target
- ", chapteringAdBreak=" (dump)      -> FUN_00d708b8, FUN_00d716c4
- "    Timeline duration=" (dump)    -> FUN_00d5f1b0
- ", updating stitcher geometry"     -> FUN_00e31e4c

**NEXT:** (a) 6.24.6 exact addrs from `proj_6246/ign6246` (background import running → headless_6246.log).
(b) In Ghidra, decompile FUN_00d56c90 (the chapteringAdBreak parser) — find where the ad-break entry is
added to the Timeline vector; design neutralize-in-place (zero the break, or skip the add) in a ShadowHook
`.so`. (c) Confirm which of Native_GetTimelineActionId vs the Timeline builder is the cleaner cut.
Script: `ghidra_work/FindAdTimeline.java` (rerun on any new version).

## ⭐ 2026-09-21 — 6.24.7 / engine v16.0.0.332 (NEW build, released today) — seam INTACT, re-pointed
APK: `com.amazon.amazonvideo.livingroom_6.24.7_v16.0.0.332-allAbis-606024070` (apkmirror .apkm).
v7a `libignite.so` = 16,351,644 bytes (6.24.6 was 16,322,636 — only +29KB, minor bump).
Java ad surface STILL 0 hits (setAdPlaybackStates/ServerSideAdInsertion/MetricsTransporter/
getVideoAds/PeriodTailor/adsparx/regolith all 0 in base.apk dex) — fully native, unchanged.
`PrimeVideoApplication` loader anchor still present in classes.dex.

Section layout (v7a, rodata offset==vaddr STILL holds):
- .rodata vaddr/off 0x0e7ae0 (6.24.6 was 0x0e72b0); .text vaddr 0x7b7be0 (Thumb; 6.24.6 0x7b5e50);
  .data.rel.ro 0xf655e0; .data 0xf94a10.

Ad-timeline string offsets (== vaddr; drifted slightly from 6.24.6):
| string | 6.24.6 | 6.24.7 |
|---|---|---|
| `, chapteringAdBreak=` | 0x14ce5e | 0x14dce0 |
| `    Timeline duration=` | 0x14946c | 0x14a145 |
| `chapteringAdBreak` (bare) | 0x13b9b4 | 0x13c0e4 |
| `, updating stitcher geometry` | 0x13868e | 0x138d2f |

Ghidra: `ghidra_work/libignite_v7_6247.so` imported → `proj_6247/ign6247`,
headless import+analyze+FindAdTimeline.java running (log `headless_6247.log`). Awaiting
exact 6.24.7 FUN addrs for the chapteringAdBreak parser/builder (6.24.5 was FUN_00d56c90/FUN_00d69bf0).
NEXT once resolved: decompile the parser, design ShadowHook neutralize-in-place; current jni/hooks.cpp
is still the OLD v15 memcpy-GOT strip, NOT a v16 seam hook — v16 native strip is net-new.

## ⭐⭐⭐ 2026-09-21 — 6.24.7 Ghidra seam RESOLVED (proj_6247/ign6247, IMAGE_BASE 0x10000)
FindAdTimeline.java ran clean on the 6.24.7 v7a blob. Neutralize targets (ad-break parser/builder):
- `chapteringAdBreak` (bare) -> FUN_00dd00c0 (prologue f0 b5 03 af 4d f8 04 bd 88 b0 04 46)
                             -> FUN_00dbd160 (prologue f0 b5 03 af 2d e9 00 0f ad f2 dc 5d)
- `, chapteringAdBreak=` (dump) -> FUN_00dd7b94, FUN_00dd6d88
- `    Timeline duration=`       -> FUN_00dc5680
- `, updating stitcher geometry` -> FUN_00e9a56c
Host-fn IMPLs (6.24.7): Native_GetTimelineActionId=0x8509a8 (2nd 0x8ae498),
  Native_SupportsTimelineActionId=0x84faa0, Native_StopTimelineAction=0x85d2d0,
  Native_FrameMarker=0x85d2d0 (SHARED thunk w/ StopTimelineAction), Native_MPBInstance_PlaybackInit=0x88e2f4.
NEXT: decompile FUN_00dd00c0 / FUN_00dbd160 (the two chapteringAdBreak refs) -> find where the ad-break
entry is pushed to the Timeline vector -> ShadowHook neutralize-in-place. Maps 1:1 to 6.24.5 FUN_00d56c90/d69bf0.

## ⭐⭐ 2026-09-21 — DEVICE-VERIFIED on .210 (onn 4K Plus, armv7a): 6.24.7 patched NON-clone boots clean
Built `pv6247-patched.apk` (non-clone, all 6 default patches Applied, Morphe cert 49:C0:4F:A9,
libpvhook.so bundled v7a). Installed `-r` on .210, launched LEANBACK: RUNNING, no VerifyError/
UnsatisfiedLink. Same Megablast/WASM/SCRIPT Rust engine as 6.24.6.
⭐ NEW seam intel from boot log:
`[Bootloader]: Loaded native binary resource 'libamzn_av_living_room_rust_client-armv7thumb-ignite-6.0.0.so.br' into VM.`
→ The Megablast **Rust ad-client is a Brotli-compressed AoT ARM blob (`.so.br`) DOWNLOADED at runtime**
(NOT in the APK — grep of universal.apk = 0 hits) and cached in the app's private data dir. Implications:
(1) Amazon can push new ad logic server-side w/o an app update — versionName engine tag (v16.0.0.332) !=
the runtime rust_client version (ignite-6.0.0). (2) The `chapteringAdBreak`/megablast.cpp strings in
libignite.so are the NATIVE HOST side (WAMR + Native_* host-fns); the ad DECISION Rust runs in the
downloaded `.so.br` client inside the VM. Our libignite.so hook targets (FUN_00dd00c0/FUN_00dbd160 +
Native_*TimelineAction*) are still valid IF the timeline data crosses the native host boundary (host-fns
prove it does). (3) To RE the rust_client itself we need root or a debuggable rebuild — app flags=0x0
(not debuggable), run-as denied, blob not in /sdcard. TODO: debuggable-flag build OR root .210 to pull
`libamzn_av_living_room_rust_client-*.so.br`, brotli -d, disasm the AoT ARM.

## ⭐⭐⭐ 2026-09-21 — LIVE 6.24.7 mid-roll anatomy CAPTURED on .210 (Neagley, patched NON-clone, US region us-east-1)
Natural roll-in (seek-onto-marker BYPASSES the break — must play through). Complete break, both edges:
- Ad START 19:02:26.8: `I/C2VdecComponent "VideoResolutionChange"` + `c2.amlogic.hevc.decoder.secure#333
  "Discard frames from previous generation"` + `raw.crop.height=1080` / `Rect crop(0,0,1919,1079)`.
- Impression burst 19:02:28.7–30.9: **27x** `I/f: Successfully uploaded metrics; code:200 ... region: us-east-1`
  in 2.2s then silent → ad-START beacon storm (obfuscated class `f`). Loud, content-agnostic ad-START oracle.
- Ad END 19:04:03.9: same VideoResolutionChange + secure-HEVC flush + `raw.crop.height=960` / `crop(0,0,1919,959)`.
- Duration ~97s. **Ad RENDERED FULLY (video+audio, volume 1.0, NO black, NO freeze).** Single shared secure
  HEVC decoder + single Widevine session across content+ad (stitched SSAI). App logs ~nothing else during ad.
- ⇒ **6.24.6 black+countdown mid-roll was a CLONE-DRM surface artifact, NOT suppression.** Non-clone = clean render.
- Patch did NOT suppress (expected: native loader skipped on v16; libpvhook not active). Ad DOES serve in US here.

**Slate-MASK actuator design (road 3, only pure-client option):**
- Trigger ON  = crop.height content→1080 (VideoResolutionChange+flush) OR the `I/f` beacon burst (earlier).
- Trigger OFF = crop.height 1080→960.
- ⚠️ crop-delta is TITLE-SPECIFIC (this title letterboxes content 960 vs ad 1080; a 1080-native title shows no delta).
  Beacon burst is more content-agnostic for ad-START but does NOT mark ad-END. Best = combine both.
Window saved: `midroll-6247-boundary.log`.

## 2026-09-21 — BREAK #2 confirms stability (same session, .210)
- START 19:06:08.99 crop→1080 (VideoResolutionChange+flush); END 19:07:46.06 crop→960. Duration ~97s (IDENTICAL to #1).
- Full render (video+audio, vol 1.0). **Beacon burst = 0 this break (vs 27 on #1)** → `I/f` metric storm is UNRELIABLE
  per-break (queued/catch-up, not impression-locked). **Codec/crop boundary is THE stable oracle.** Both breaks ~97s.
- Actuator: trigger ON = raw.crop.height content→1080; OFF = →960. Drop the beacon-burst idea. Window: midroll-6247-break2.log.

## ⭐⭐⭐⭐ 2026-09-21 — ROAD 1 SEAM FULLY DECOMPILED (6.24.7, proj_6247). chapteringAdBreak = mid-roll ON/OFF flag.
Full call chain (parser → mutex → logger/handler → actuator):
```
FUN_00841374  (timeline update; ONLY caller of the parser)
  r0 = FUN_00dd00c0(&flagbyte, opts)   // PARSER: *out=0 default; if Options has "chapteringAdBreak", parse into *out
  FUN_00dcefd6(..., &flagbyte)          // recursive_mutex guard
    FUN_00dd6d88(..., flag)             // "PlayFromUtcTimeWithOptions" handler (logs ", chapteringAdBreak=<bool>")
      FUN_00ded1c8(state+0x48, utc_lo, utc_hi, flag)   // ACTUATOR
```
ACTUATOR FUN_00ded1c8 (52 bytes) — DEFINITIVE:
```c
bVar1 = *flag;                 // chapteringAdBreak
*(state+0x3c)=bVar1; *(state+0x30)=utc_lo; *(state+0x34)=utc_hi;
if((bVar1 & 1)==0){ *(state+0x40)=0; *(state+0x44)=0; }   // FALSE => zero ad-break payload (+0x40/+0x44)
```
⇒ flag TRUE keeps the mid-roll break params (+0x40/+0x44) populated; FALSE zeroes them = NO break.
Normal (non-ad) play-from-utc already runs FALSE ⇒ forcing FALSE universally = no side effect on seeking.

### ShadowHook design (neutralize-in-place, function-replace — NO resize)
PRIMARY target = the parser **FUN_00dd00c0** @ vaddr 0x00dd00c0 (Thumb: attach addr | 1). Exactly ONE caller
(FUN_00841374). ABI: r0=out ptr(&flagbyte), r1=opts; returns r0 (pointer preserved). The local parse object
(FUN_00e05a90/FUN_00db5170 on local_30) is stack-internal → safe to skip. Replacement stub:
```c
void* repl_dd00c0(uint8_t* out, void* opts){ *out = 0; return out; }  // chapteringAdBreak := false, ABI-preserving
```
ALT target (belt) = actuator **FUN_00ded1c8** @0x00ded1c8 — force the false branch (needs internal FUN_00dece34
addr, messier). Prefer the parser hook. Reuse `experimental/primevideo-libignite-native/jni/` ShadowHook scaffold
(current hooks.cpp = OLD v15 memcpy-GOT strip — REPLACE with a ShadowHook on libignite `chapteringAdBreak` parser).
Attach: ShadowHook on libignite.so base + 0xdd00c0 (|1 Thumb). Verify: play Neagley mid-roll on .210 → expect NO
crop→1080 boundary + content continuous (dur no longer +97s). Decompile logs: ghidra_work/{decomp,callers,dd6d88,ded1c8}_6247.log.
