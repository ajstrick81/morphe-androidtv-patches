# Methodology — the end-to-end playbook

Ordered cheapest-risk-reduction first. Do NOT skip ahead to writing a `.so`;
most projects die on step 1 or 2, and those are free to get wrong on paper.

## 0. Recon — what kind of ad delivery is this?

Before any code, classify the ad mechanism. The strip strategy depends entirely
on it:

- **Control-plane / client-stitched.** The app fetches an ad schedule (a JSON
  playback-resources doc, an `AdPlaybackState`, VMAP/VAST) and *then* requests
  ad media. Killable from the schedule: delete the ad entries and the client
  requests no ad media. (Prime Video's `type:"Remote"` items are this.)
- **SSAI / server-stitched.** Ads are spliced into the *same* manifest/stream as
  content (`/iad_` segments, DASH ad `<Period>`s, HLS `#EXT-X-DISCONTINUITY`
  runs). Killable by rewriting the manifest to drop the ad entries.
- **Markerless inline SSAI.** Ads share the content stream with *no* manifest
  marker. Hardest; needs request-side URL logic. Usually the last resort.

Also check the cheap wins first: can **DNS** split the ad host from content?
Can a **bytecode** patch reach it (Volley/OkHttp chokepoint, ExoPlayer
`AdPlaybackState`)? Only go native for what those provably can't reach — the
media plane fetched by the app's native pipeline over a shared TLS session.

## 1. De-risk the assumption empirically (before any Ghidra)

The single biggest unknown is usually *"is this actually strippable from the
data we can reach?"* Answer it with a capture, not a hunch:

- Get plaintext (a PC MITM proxy with a CA on the device is the fastest bench)
  and capture a **real ad break** — for movies AND, separately, TV/series (they
  differ; see LESSONS).
- Confirm **both**: (a) the ad segments carry the marker your filter will key on,
  and (b) removing them yields **clean playback** (the player glides over the
  gap; SSAI stitch timing survives the strip).
- You can test (b) offline: run the captured body through your filter and diff.

**If stripping breaks playback, stop — rethink before writing any hook.**

## 2. Find the in-process seam ([FINDING-THE-SEAM.md](FINDING-THE-SEAM.md))

The plaintext exists somewhere inside the app between decrypt and parse. Find
*which function* carries it. Candidates, in rough order:
- `SSL_read` (decrypted HTTP body) — but often the crypto is **statically linked
  and stripped** inside the app's own `.so`, so system `libssl` never sees it.
- `inflate` (decompressed body) — if the body is gzip'd.
- `memcpy`/`memmove` — the app copying the decrypted buffer internally.

**Do not assume.** The Prime Video scaffold confidently targeted `inflate` /
`SSL_read`; the bench proved the payload was only at `memcpy`. Confirm the hook
**fires** on real data before trusting a "0 ads found" result.

## 3. Design the transform

Match the transform to the seam's *mechanics*:

| Seam kind | You get… | Transform |
|---|---|---|
| **Pull** (`SSL_read`/`inflate`/read wrapper) | control over what the app reads back | **reassemble → filter whole body → re-serve** (may shrink). Handles any size / chunking. |
| **Push** (`memcpy`) | one-shot copy; you can only edit `src` in place | **same-length blank**, and only on a COMPLETE unit in one buffer (never a truncated chunk). |

Safety rule for both: **never edit a partial/truncated payload.** Parse the
whole unit first; if it's incomplete, leave the bytes untouched. Editing a
truncated chunk corrupts a partial content element → black screen.

## 4. Host-test the transform to death ([TESTING.md](TESTING.md))

The transform is pure C/C++ — no Android needed. Write it, unit-test every edge
(complete/truncated/first/last/all-ad/whitespace/nested-brackets), then
**mutation-test**: break the code, confirm the suite goes red. Run under
`-fsanitize=address,undefined`. This is where you buy correctness cheaply.

## 5. Recover offsets & build runtime signatures ([GHIDRA-OFFSETS.md](GHIDRA-OFFSETS.md))

Pull the exact `.so` off the device, recover the seam function's entry, grab a
unique prologue signature + a fallback offset, and paste into `offsets.h`. The
runtime `sigscan` re-finds the function on each launch so a minor app update
doesn't hard-break you. Record the `.so` SHA-256 — offsets are per-build.

## 6. Build the `.so`

Vendor an inline-hook engine (**ShadowHook** recommended; Dobby was a dead end,
see LESSONS), fill `hooks.cpp` (`JNI_OnLoad` → resolve module → sigscan →
install hooks → run your transform), build per `CMakeLists.txt` for the shipping
ABI first (usually `armeabi-v7a`).

## 7. Bundle + load via a Morphe patch

Three files, templated in `scaffold/patch` + `scaffold/extension`:
- `BundleNativeHookPatch` — writes `lib<HOOK>.so` into `lib/<abi>/`, forces
  `extractNativeLibs="true"` (avoids the alignment `UnsatisfiedLinkError`).
- `LoadNativeHookPatch` — injects `NativeHookLoader.load()` at `onCreate` top.
- `NativeHookLoader` — the 5-line `System.loadLibrary` with fail-loud logging.

## 8. Verify the EFFECT on device — not "Applied"

Watch logcat for your tag: the hook resolved the seam, and it **stripped N
items on playback start**. Confirm no ad GETs follow. "Patch applied" means
nothing; the ad being *gone on screen* is the deliverable. Breadth-test movies
AND TV, and re-verify after an app update (offsets are per-build; the
content-keyed markers are version-robust, the addresses are not).
