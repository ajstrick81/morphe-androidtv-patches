# Twitch Android TV — Ad-Suppression Blueprint

A starting map for anyone picking up the Twitch ad work (`tv.twitch.android.app`).
It records what ships today, **why** it works, the walls we've already hit (with
on-device evidence, so you don't re-run dead ends), and the concrete frontier
for getting closer to zero ads.

Written against **v30.2.2** (versionCode 3002026). Obfuscated class/method names
below are R8 pins for that build and **will drift** on version bumps — see
[Re-pinning on a version bump](#re-pinning-on-a-version-bump).

---

## TL;DR — current state (shipped v1.18.0)

The **Skip ads** patch reduces ads; it does **not** eliminate them.

- ✅ Long SSAI **mid-roll ads are gone**
- ✅ Join **pre-roll cut to ~15s** (from 45s+), lower frequency
- ⚠️ A **short residual pre-roll** at channel join remains — server-stitched in
  Twitch's **native IVS** playback path, which bytecode cannot reach

If you want to push further, the residual pre-roll is the whole game. Jump to
[The frontier](#the-frontier-getting-to-zero).

---

## Ad architecture (confirmed by dex disassembly + on-device logcat)

Twitch live playback on Android runs on **ExoPlayer2** — but fed by a **custom /
native Amazon IVS media source**, not ExoPlayer's stock HLS stack (this
distinction is the crux; see the dead ends). Ads arrive by two mechanisms:

1. **Stream access token (GraphQL `StreamAccessTokenQuery`).** Carries a
   `playerType` field baked into the *signed* token. `usher.ttvnw.net` uses it to
   decide server-side ad fill. Web "backup" player contexts
   (`embed` / `popout` / `autoplay`) are served an **ad-free** stream. This is
   the lever that kills mid-rolls.
2. **GrandDads GQL query** — a per-session ad-eligibility/decisioning call. A
   client-side eligibility gate.

The ad **video itself** is **server-stitched (SureStream SSAI)**: the ad `.ts`
segments are welded into the live HLS stream that the IVS pipeline plays. There
is **no client-side ad-cue timeline** to empty (the usual Pluto/Disney
"empty the accessor" technique has no target here — confirmed: the `surestream`
package collapses under R8 to a tracking API + audio-only pod model).

```
StreamAccessTokenQuery ──(playerType in signed token)──> usher ──> ad-free vs stitched stream
GrandDads query ─────────> client ad-eligibility gate (server can ignore it)
live HLS (IVS) ──> SSAI ad segments welded inline ──> ExoPlayer decodes (MediaCodec)
in-band ID3/EMSG metadata ──> ExoPlayer onMetadata ──> "twitch-stitched-ad" ad events/beacons
```

---

## What ships, and why it works

All three layers live in
[`patches/.../twitch/ads/SkipAdsPatch.kt`](../patches/src/main/kotlin/ajstrick81/morphe/patches/twitch/ads/SkipAdsPatch.kt),
anchored by [`Fingerprints.kt`](../patches/src/main/kotlin/ajstrick81/morphe/patches/twitch/ads/Fingerprints.kt).

| Layer | Where | What it does | Proven effect |
|-------|-------|--------------|---------------|
| **1 — playerType spoof** (the win) | `PlaybackAccessTokenParams.<init>` (`Ls1r;`), force the `playerType` string arg to `"embed"` | usher serves the ad-free "embed" web-player stream | **Long mid-rolls eliminated**; ad 45s→15s |
| **2 — GrandDads decline** | GrandDads response mapper (`Lwyh;->invoke`, tag-4 branch), force return of the `AdContextUnavailable` singleton (`Ln0i;->a`) | Client eligibility block (PurpleTV-style) | Crash-free; no *observed* solo effect (server ignores it for stitched pre-rolls) — kept as defense-in-depth |
| **3 — stitched-ad metadata false** | ExoPlayer player core `Ltv/twitch/android/shared/player/core/b;->G` (onMetadata), force the `"twitch-stitched-ad".equals(...)` boolean false | Player stops entering ad handling | Suppresses ad **tracking/beacons/UI** — but **not** the ad video (segments already decoded) |

**Key mechanism note on Layer 3:** forcing that boolean false only hides the app's
*awareness* of a stitched ad. The ad segments are already in the stream and still
decode, so the ad still *plays*. Layer 3 is a tracking/beacon win, not a removal.
The removal on mid-rolls comes entirely from **Layer 1** (never receiving the
stitched stream).

---

## The walls (don't re-run these)

Each of these was taken to on-device proof. Evidence in the
`twitch-ad-suppression` session memory.

1. **GrandDads is not consulted for the pre-roll.** Layer 2 forces the decline
   sentinel and runs crash-free, but the join pre-roll plays anyway. The
   server-stitched pre-roll doesn't gate on this client signal.
2. **Killing stitched-ad metadata ≠ removing the ad.** Layer 3 correctly forces
   the boolean false (byte-verified), yet a 45s ad still played and playback
   position climbed *continuously* through the ad window → the ad video is baked
   into the same HLS timeline (pure SSAI). Detection-suppression only.
3. **ExoPlayer's stock HLS parser is NOT on the live path.** ⭐ The big one. We
   hooked ExoPlayer's `HlsPlaylistParser` (R8-renamed `Lxsi;`) at its `parse()`
   entry to strip ad segments from the media-playlist text (the TwitchAdSolutions
   approach; modeled on the working MLB `MlbManifestRewriter`). On-device the hook
   fired **zero times** across a full cold-join **with an ad playing** (playback
   confirmed via 144 decoder lines; app alive; no VerifyError) — even with
   unconditional entry logging. **Conclusion:** Twitch live feeds ExoPlayer via a
   custom/native IVS media source that parses the live `.m3u8` itself; the stock
   parser is used only for VOD/clips. There is **no Java playlist-text seam** for
   the live stream. (This diagnostic scaffolding was built, proven, and reverted
   before shipping.)
4. **playerType alone can't finish the job.** `embed` already yields a clean
   *mid-roll* stream, but the head pre-roll persists through it, so swapping to
   `popout`/`autoplay` (same clean-stream family) is unlikely to help. Untested
   but low-EV.

---

## The frontier: getting to zero

The residual pre-roll's playlist is parsed **inside the native IVS pipeline**
(analogous to Prime Video's sealed native stack). Ordered by leverage:

1. **Find a Java DataSource seam below the parser.** Live playback *is* ExoPlayer
   (the `onMetadata` callback fires, MediaCodec/CCodec decodes). If the IVS media
   source still fetches playlist/segment bytes through a **Java `DataSource`**
   (even a custom one), those bytes are on-path regardless of which parser
   consumes them. **Next step:** read `core.b`'s media-source construction (how it
   builds the player + source for live) and look for a `DataSource`/`DataSpec`
   whose `.m3u8` response `InputStream` can be wrapped — the same seam MLB uses,
   one layer lower than the parser. If found, reuse the
   [`MlbManifestRewriter`](../extensions/extension/src/main/java/ajstrick81/morphe/extension/mlbtv/ads/MlbManifestRewriter.kt)
   pattern. **Caveat:** a *pre-roll* is prepended to content, so dropping its head
   segments is safe (no gap); a mid-roll would need a backup-stream splice.
2. **Native IVS `.so` hook.** If the m3u8 fetch/parse is fully native
   (`libamazon-ivs-player`-class), this becomes a native-hook problem like the
   Prime Video GOT/PLT work (see the `primevideo-atv-got-hook` memory + native
   strip patches for a working template on this repo's devices).
3. **Access-token variants.** Cheap to try, low expected value: `popout` /
   `autoplay` playerType; or a de-authed token request for the join manifest.
4. **Accept the ~15s pre-roll.** It's already a large improvement over stock and
   over the third-party TV apps (which show full ads).

---

## Build / test / ship

Follow [`docs/AGENT_PATCH_WORKFLOW.md`](AGENT_PATCH_WORKFLOW.md). Twitch specifics:

```
APP PACKAGE     : tv.twitch.android.app
APK BUNDLE      : Twitch Android TV 30.2.2 (.apkm) from APKMirror  (version-pinned)
LAUNCH ACTIVITY : tv.twitch.android.app/.core.LandingActivity   (no LEANBACK category)
DEEP-LINK JOIN  : adb shell am start -a android.intent.action.VIEW -d "https://www.twitch.tv/<channel>"
PATCH SOURCE    : patches/src/main/kotlin/ajstrick81/morphe/patches/twitch/
```

**Verifying an ad on-device** (release build logs almost nothing about HLS/ads):
- Playback boundaries show up as Amlogic decoder resets (`amlv4l ... reset mode`)
  and `VideoDecWraper`/`VDA got resolution change` events.
- `AudioMediaPlayerWrapper` (system media session) reports a `position` in ms —
  a pre-roll shows as position pinned near 0 during the ad, then content starting
  fresh; a baked mid-roll shows position climbing continuously *through* the ad.
- Don't trust `Applied: <name>` as success — **verify the effect**, not the apply.

---

## Re-pinning on a version bump

The obfuscated names (`Ls1r;`, `Lwyh;`, `Ln0i;`, `core/b;->G`, `Lxsi;`) are
v30.2.2-specific and change with R8. Re-anchor on the **stable** strings, not the
names:

- **Layer 1:** `"PlaybackAccessTokenParams(disableHTTPS="` (data-class toString) →
  class → its `<init>`; the `playerType` is the required (non-Optional) String
  field.
- **Layer 2:** `"query GrandDads"` (Apollo document) → the query class → its sole
  constructor's enclosing builder → the response mapper (the functor that returns
  the sealed response; force the `AdContextUnavailable` singleton branch). The
  fingerprint's `custom` predicate self-verifies by requiring the sentinel ref, so
  a drifted pin fails to match (patch skipped) rather than mis-applying.
- **Layer 3:** unobfuscated class `Ltv/twitch/android/shared/player/core/b;` +
  protocol strings `"#EXT-X-DATERANGE"` / `"twitch-stitched-ad"`; injection point
  located dynamically (equals→move-result), not by offset.

**Tooling gotchas:** `rg`/`grep -r` over the ~50k-file smali tree **times out at
120s** in the foreground — run searches as a backgrounded job. Prefer dexlib2
scanners or `strings classes*.dex | grep` for string-pool counts. `aapt dump`
fails on the APKEditor-repacked universal APK — run it against `base.apk` from the
`.apkm`.

---

## Attribution

Techniques independently re-derived against v30.2.2 dex; credit to
**TwitchAdSolutions/vaft** (pixeltris) for the ad-free playerType/clean-stream
approach, **Purple TV** for the GrandDads eligibility short-circuit, and **Xtra
for Twitch** as prior art. See the headers in `Fingerprints.kt`.
