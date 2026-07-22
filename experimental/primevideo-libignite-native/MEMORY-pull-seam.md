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
