# Project memory — `primevideo-tv-pull-seam-next`

**Saved 2026-07-22.** The single next step for the Prime Video native ad-strip,
for whoever (session or human) picks this up at the Onn device.

## The situation in one paragraph

The in-process `type:"Remote"` PRS strip gets **movies 100%** but leaves **TV
markers**. This is NOT a transform gap — the off-device MITM rig proved the same
strip cleans movies AND TV. It is an **interception-completeness** gap: the
movies path (`prs_blank`, same-length blank at the `memcpy` push seam) can only
act on a playlist that is COMPLETE inside one copy; the larger/denser TV
playlist arrives only as truncated chunks (and can exceed the 256KB size gate),
so every chunk is safely skipped and the ads survive. The fix is to strip the
**whole reassembled body**, which is exactly what the rig did off-device.

## ⚡ Test this cheaper alternative FIRST (could obviate the native strip)

**Lead from a web project — `WINOFFRG/ottpro` (browser Prime Video blocker), reviewed 2026-07-23.**
It kills ads on *web* Prime Video from the **request / control-plane** side, not by
stripping the response the way we do. Two request-side levers, both against
endpoints we already know:

1. **Hard-block the ad-schedule fetch** `cdp/getVideoAds` → return empty `{}`.
   (This is their *primary* lever.)
2. **Rewrite the PRS *request* body** on the same `GetVodPlaybackResources` we
   target — set `vodPlaylistedPlaybackUrlsRequest.ads.gdpr` consent fields
   (`consentMap`, `enabled`) — to nudge the server toward a no-ads playlist.

**Why this matters:** if a request-side flag (or nulling `getVideoAds`) makes the
server hand back an **ad-free playlist**, then the entire native RESPONSE strip —
`prs_blank`, `prs_reassembly`, the pull-seam Ghidra hunt below — is **unnecessary**.
A tiny request edit would replace all of it. This is a ~15-minute test on gear you
already have.

**The test (MITM rig, which already sits on the request side):**
- Capture the ATV `GetVodPlaybackResources` **request** body; look for an
  `ads` / `gdpr` / consent structure (the ATV schema may differ from web).
- Try (a) nulling `cdp/getVideoAds`, and (b) adding the consent flag; observe
  whether the response returns ad-free.

**Honest caveats:** web ≠ ATV — the request schema may differ or the server may
gate ads by plan/account and ignore consent; `consentMap` semantics are murky
(GDPR personalization vs ad presence); their `getVideoAds` block is likely doing
most of the work. And in-process this is our deferred **Phase B** (a *request*-side
hook, a different seam than the response `memcpy`) — but you don't need to build
that to *test* the hypothesis on the rig.

**Not wasted either way.** Even if the request-side route works, the native
response strip stays valuable: it's resistant to server-side changes (Amazon can
patch a consent loophole overnight; they can't easily stop us editing the decoded
body in-process), and the seam/APK mapping we've built is the reusable asset. Treat
this as "try the cheap lever first; keep the robust one in reserve."

## The next step (do these in order, on device)

1. **Validate the direction with ONE capture (cheap, do first).** Pull a real
   **TV-show** PRS body (`POST …/GetVodPlaybackResources`) and confirm the ads
   are `type:"Remote"` items in `intraTitlePlaylist`. If yes → the whole-body
   reassembler is the right fix. If the TV ads are instead `/iad_` SSAI manifest
   segments, pivot to the `manifest_filter` + `ssl_reassembly` path instead.
   **Do not start Ghidra until this capture confirms the mechanism.**

2. **Cheapest experiment before any Ghidra:** just raise `prs_blank`'s
   `kMaxScan` (currently 262144) in case the TV complete-copy merely exceeds
   256KB rather than being chunked. One-line change, already parameterized.

3. **Recover the whole-body PRS PULL seam (Ghidra).** The reassembler needs a
   seam where we control what the app reads back (SSL_read / inflate / a read
   wrapper) — NOT the one-shot `memcpy` push seam movies use. ⚠️ **The old
   guesses are DISPROVEN:** `inflate@0xd32f7a` and `SSL_read@0xc4fe3c` do NOT
   carry the PRS (it's decrypted inside libignite's static BoringSSL). Re-hunt
   the point where the whole decrypted PRS body exists before it's chunk-copied.

4. **Wire `PrsReassembler` to it** — the transform is done and tested; this is
   glue. Remember the **framing caveat**: `filter_prs` shrinks the body, so the
   hook MUST rewrite `Content-Length` (or use chunked transfer-encoding) or the
   app's HTTP layer waits for bytes that never come.

## Device-phase hook requirements (don't forget these when wiring)

- **Reentrancy / thread-safety.** The seam function (memcpy for the movies push
  path, and likely a hot read/copy for the pull path) is called from EVERY
  thread. The hook body must be reentrant and signal-safe: no non-reentrant libc
  in the hot path, no global mutable state without care, keep allocation off the
  hot path. Stress-test under concurrent playback before trusting it. (Raised by
  an external audit, 2026-07-22 — a valid point; not yet designed for.)
- **Per-response keying + body-complete detection** is the reassembler's missing
  framing driver (see `prs_reassembly.h`); it's the same device-side integration
  work as the Content-Length fixup above.

## What's already built & proven (no need to redo)

- `jni/prs_reassembly.h` (`PrsReassembler`) — accumulate → strip complete body
  via `filter_prs` → re-serve. Host-tested `jni/test_prs_reassembly.cpp` (28
  checks: chunk-boundary invariance across every split; a >300KB TV fixture that
  per-chunk blanking strips 0 of and the reassembler strips all of).
  Mutation-verified, ASan/UBSan-clean.
- `jni/prs_blank.{h,cpp}` — the movies push-seam blanker (75 checks); includes a
  fix for a trailing-comma bug latent in the original `cmod-strip2.js`.
- Reproduce all suites: see `HANDOFF.md` → "Re-verify".

## Related memories
- `primevideo-native-speed-deadend` (`experimental/primevideo-speed-control/`) —
  the native engine is sealed to bytecode; this native approach goes under it.
