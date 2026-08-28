# Paramount+ live slate — manifest-continuity experiment (issue #91, post-v1.30.1)

Research prototype to try to remove the residual ~2.6s per-ad-pod decoder flush in the
shipped live-slate fix. **Not shipped. Shipped v1.30.1 (per-pod PTS align) is unaffected.**

## Hypothesis (from the 2026-08-28 "event has ended" banner capture)

The native "live event has ended" banner is glassy-smooth because it is delivered as ordinary
**content** on one continuous stream (`airspace-cdn/manifest_6_N.ts`, ~6s segs, monotonic PTS,
no ad-pod structure). Our commercial-break slate hitches because it fills the DAI **ad pods**,
whose variant `.m3u8` declares an `EXT-X-DISCONTINUITY` + PTS reset per pod → the player flushes
the decoder at each boundary.

So: make the break look like ONE continuous period.

## What this prototype does (two coupled changes, in `MorpheTsRewriter` + the interceptor)

1. **`stripManifest(byte[])`** — for variant `.m3u8` responses, remove every `#EXT-X-DISCONTINUITY`
   line so the player treats content+pods as one period. No-op when the manifest has none
   (i.e. outside breaks), returns original bytes on any error.
2. **Monotonic slate PTS** — `alignSlateToLive` now advances a single `OUT_PTS` by `SEG_TICKS`
   (~5.005s, real-time rate) every segment, ignoring the per-pod `realPts` resets; re-anchors to
   the real live edge only at break start (>20s wall gap). This matches the now-continuous manifest.

Interceptor (`AviaNetworkInterceptor.intercept`, injected smali) adds a branch: for `…/variant/….m3u8`
→ proceed original, `stripManifest(body)`, return 200 with the rewritten body. The `pods/v1` slate
branch is unchanged except for the monotonic engine.

## Status / risks

- Builds + boots clean on v16.17.0 (`out_disc.aligned.apk`, signed 86c779).
- ⚠️ UNVALIDATED at a live break. Known risks: (a) rewriting the manifest may break playback
  (`3002`, as an earlier AdGuard `$hls` removal test did); (b) the monotonic slate is ~6s content
  in ~5s slots → within-pod overlap jitter may return (add a per-segment trim if the flush is fixed
  but jitter appears); (c) transition-back drift if the break duration diverges from the lay-down.
- If it holds up: add slate-segment TRIM to `SEG_TICKS` to kill overlap, then productionize.
- If it breaks: the shipped per-pod-align approach is the ceiling; close this track.

Test loop: install `out_disc.aligned.apk` → live break → grep `MorpheLiveAd`/`STRIPMANIFEST` +
watch VDA INs (flushes gone?) + 6015/3002. Restore shipped build with the production-patched apk.
