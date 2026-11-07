# Disney+ Android TV — how LIVE ads are delivered, and whether bytecode can reach them

**App** `com.disney.disneyplus` · **Version analysed** `26.20.0+rc3-2026.11.02` (versionCode 1793662190)
**Decompile** `E:\Morphe\decompiled\Disney+\2620-patched` (36,007 smali files, 7 dex)
**Device** onn 4K Plus "coffey", `192.168.12.210:5555`
**Captures** live `/e/Morphe/logs/Disney+/2620-LIVE-gameday.log` (ESPN "College Gameday" inside Disney+, 92k lines, from 08:38:52 on 2026-11-07) · VOD baseline `/e/Morphe/logs/Disney+/2620-session.log` (14 MB, several titles)

> **Short answer.** Live ad windows are **not** native. They arrive as HLS
> `#EXT-X-DATERANGE` interstitial tags in the live playlist and are parsed
> **entirely in app Java** — a ~5,900-line smali method using `org.json`,
> joda-time and `LinkedHashMap`. Bytecode reaches that timeline easily, so
> **DNS is not the ceiling for the live break schedule**.
>
> But reaching the timeline is not the same as removing the ad. The parsed
> break can take one of two branches, chosen by two booleans. On the stream we
> actually measured (ESPN within Disney+) the client takes the **passthrough**
> branch: no client-side interstitials are scheduled at all, because the
> commercials are already inside the delivered segments. There is nothing in
> the timeline to empty there, and masking (slate + mute, as
> `EspnLiveSlatePatch` does) is the realistic route. The same `playlistRetrieved`
> data is the right seam to **read** for a mask, which is why pinning it was
> worth doing.
>
> **No patch is shipped from this investigation.** The VOD ad patch is untouched.

---

## 1. The exact mechanism

### 1.1 Live ad windows ride in the HLS playlist, not in `Insertion`

A playlist-line predicate decides what counts as an interstitial. It is an R8-merged
synthetic `Function1<String, Boolean>`:

`smali_classes4/x73.smali`, `invoke()` branch `:pswitch_3` (packed-switch base `0x0`,
29 entries, so discriminator `0x19`):

```smali
const-string p0, "#EXT-X-DATERANGE:"
invoke-static {p1, p0, v0}, Lscm;->n0(...)Z          # startsWith
const-string p0, "com.apple.hls.interstitial"
invoke-static {p1, p0, v0}, Llcm;->q0(...)Z          # contains
const-string p0, "com.disney.media.break.v1"
invoke-static {p1, p0, v0}, Llcm;->q0(...)Z          # contains
```

So the two interstitial `CLASS` values the app recognises are the standard Apple
`com.apple.hls.interstitial` and Disney's own `com.disney.media.break.v1`. Those
are the only two occurrences of `com.disney.media.*` in the whole dex
(`x73.smali:176`, `ssc.smali:10437`).

### 1.2 The delivery callback: `playlistRetrieved(DateTime, List<DateRange>)`

`smali_classes6/nmh.smali` is the listener interface — the minified equivalent of
ESPN's clear-named `SgaiPlaybackSession` surface:

```smali
.class public interface abstract Lnmh;
.method public abstract c(Lpph;)V
.method public abstract d(J)V
.method public abstract e(Lnd4;Leej;Lod4;)V                              # recipe ready
.method public abstract g(Lorg/joda/time/DateTime;Ljava/util/List;)V     # playlistRetrieved
.method public abstract h(Ln5k;)V
.method public abstract k(J)V
.method public abstract l(JJLntk;)Lhmk;
.method public abstract o(JJLntk;)Lbuk;
```

`g(...)` is `playlistRetrieved(programDateTime, dateRanges)`. **Proof by log-lambda
discriminator**, not by inference:

| Site | Lambda call | Switch index | Log string |
|---|---|---|---|
| `jcl.g` (`smali_classes6/jcl.smali:396`) | `Lf7l;-><init>(Object,Object,0xa)` | base `0x0`, 29 entries → `pswitch_12` | `"PlaybackSession playlistRetrieved() programDateTime:"` + `" - dateRanges:"` |
| `ssc.g` (`smali_classes6/ssc.smali:10202`) | `Lmd5;-><init>(List,0xb)` | base `0x0`, 22 entries → `pswitch_a` | `"playlistRetrieved, size = "` |
| inside `ssc.g` (`ssc.smali:10350`) | `Lmd5;-><init>(List,0x8)` | → `pswitch_d` | `"scheduleLiveInterstitials size = "` |

The label mapping is mechanical: a `.packed-switch 0x0` table lists labels in
descending order, so discriminator *i* selects label `pswitch_(max − i)`. That is
what pins these otherwise-anonymous merged lambdas to specific log sites.

There are two implementors of `Lnmh;`:

* **`Ljcl;`** (`smali_classes6/jcl.smali`, 681 lines) — a **logging decorator**. It
  holds `a:Landroidx/media3/common/Player;` and `b:Lcom/disney/dmp/internal/pbo/StreamingType;`,
  logs each call via `Lp97;->w(Integer, Function0, int)`, then forwards to
  `f:Lnmh;` *if non-null*.
* **`Lssc;`** (`smali_classes6/ssc.smali`, 25,671 lines — an R8 mega-merge) — the
  **real implementation**. It is installed as the delegate by
  `smali_classes4/bck.smali:165` (`iput-object v0, v1, Ljcl;->f:Lnmh;`, where
  `v0 = nck.n:Lssc;`) and cleared by `nck.l()` (`smali_classes6/nck.smali:6442`,
  which writes null into `Ljcl;->f`).

### 1.3 What `ssc.g` does — all of it in Java

`Lssc;->g(Lorg/joda/time/DateTime;Ljava/util/List;)V` spans
**`ssc.smali:10202`–`16143`, ~5,941 lines of smali, `.locals 59`**. Per `DateRange`
(`Lf05;`, with `a:String` id and `b:LinkedHashMap` attributes) it reads:

| Attribute | Use |
|---|---|
| `CLASS` | discriminator; `"com.disney.media.break.v1"` → ad break |
| `START-DATE` | parsed against `programDateTime` by `Luq1;->L(String, DateTime)Ljava/lang/Long;` |
| `END-DATE` | window end |
| `PLANNED-DURATION` | `Lrcm;->b0(String)Double` → `Lufc;->l0(Double)Long` |
| `X-ASSET-LIST` | the SGAI asset-list URI (what the client would fetch and play) |
| `X-COM-DISNEY-PAYLOAD` | `android.util.Base64.decode` → `new String(bytes, charset)` → `org.json.JSONObject` → `getString("content")` → `Lwq;->a(String)Lgp3;` |
| `X-RESUME-OFFSET` | `ssc.smali:12965` |

It then builds a break record `Lau1;-><init>(Ljava/lang/String;JLjava/lang/Long;)V`
(id, start, duration) and stores it in the scheduler's maps.

**There is no JNI, no native call and no sealed pipeline anywhere in this path.**
`org.json`, `joda-time`, `java.util.LinkedHashMap`, `android.util.Base64` — it is
ordinary app bytecode.

### 1.4 The two branches, and the booleans that choose between them

Near the top of `ssc.g` the scheduler state is pulled out of `Lcub;` (reached as
`ssc.b` → `Lnck;` → `nck.r:Lcub;`), then two gates are tested:

```smali
iget-object v10, v2, Lcub;->p:Li08;          # Function0<Boolean> — feature flag
invoke-virtual {v10}, Li08;->invoke()Ljava/lang/Object;
...
if-eqz v10, :cond_41                         # flag false  -> PASSTHROUGH
iget-boolean v10, v2, Lcub;->j:Z
if-eqz v10, :cond_40                         # second gate -> PASSTHROUGH
...                                          # both true   -> CLIENT-SIDE
invoke-direct {v3, v0, v5}, Lmd5;-><init>(Ljava/util/List;B)V   # 0x8 =
                                             # "scheduleLiveInterstitials size = "
```

* **Client-side branch (both gates true).** Logs `scheduleLiveInterstitials size = `,
  parses every `DateRange` as above, populates `cub.q` / `cub.r` / `cub.t` / `cub.u` /
  `cub.v` `LinkedHashMap`s, and the controller then schedules each break — log string
  `"SgaiInterstitialController scheduleBreak() "` (`f7l` discriminator `0x14` →
  `pswitch_14`, called from `smali_classes5/fam.smali:2509`) — building synthetic
  sources whose ids are prefixed `"SynthAsset:"` (`smali_classes5/cub.smali:339`).
  In this branch the ads are **app-inserted**: suppressing the schedule genuinely
  removes them.
* **Passthrough branch (`:cond_41` / `:cond_40`).** Clears `cub.q` and the asset map
  and routes through `cub.a:Lwri;` with `cub.f:Z` and `cub.c:Leej;` (the Recipe).
  **No interstitials are scheduled.** The main manifest plays as delivered, so the
  commercials are inside the segments.

### 1.5 Disney does not use media3's interstitial machinery

`HlsInterstitialsAdsLoader` is **absent** from the dex. `AdsMediaSource` and
`AdPlaybackState` appear only inside media3's own merged classes
(`dcn`, `cz7`, `q08`, `np`, `lz7`, `jz7`, `ds`), with **zero** app-side
`Landroidx/media3/common/AdPlaybackState;` references. Disney runs its own
scheduler (`cub` + `fam` + `kcl`/`lcl`) on top of plain
`androidx.media3.common.Player`.

---

## 2. Where the shipping VOD patch does and does not reach

### 2.1 `Insertion` is a different channel from `playlistRetrieved`

`Lmcl;` (`smali_classes6/mcl.smali`) is the DMP playback-orchestration builder. Its
constructor takes `androidx.media3.exoplayer.ExoPlayer`,
`com.disney.dmp.internal.pbo.MediaPayload` and
`com.disney.dmp.internal.pbo.InsertionUrlInfo`, and at `mcl.smali:1453` it builds the
**Recipe** and hands it to the listener:

```smali
new-instance v5, Leej;
invoke-direct {v5, v3, v7, v11, v4}, Leej;-><init>(
    Ljava/util/ArrayList;                                           # insertionPoints
    Lcom/disneystreaming/androidmediaplugin/playio/AdSession;        # adSession
    Ljava/util/ArrayList;                                           # allowedLiveInterstitials
    Lcom/disneystreaming/androidmediaplugin/playio/InsertionMode;)V  # mode
iget-object v0, v0, Lmcl;->k:Ljcl;
invoke-virtual {v0, v1, v5, v2}, Ljcl;->e(Lnd4;Leej;Lod4;)V
```

`Leej;` (`smali_classes5/eej.smali`, 365 lines) is the data class whose `toString()`
reads `Recipe(insertionPoints=…, adSession=…, allowedLiveInterstitials=…, mode=…)`.
That `toString()` label is where the name `allowedLiveInterstitials` in the existing
patch comment comes from.

### 2.2 How `allowedLiveInterstitials` is actually produced

Immediately before the Recipe is constructed (`mcl.smali` `:cond_1f`–`:goto_1d`):

```smali
invoke-virtual/range {p8 .. p8}, Lcom/disney/dmp/internal/pbo/MediaPayloadStream;->getInsertion()...
invoke-virtual {v5}, Lcom/disney/dmp/internal/pbo/Insertion;->getRanges()Ljava/util/List;
# map each element:
check-cast v8, Lcom/disney/dmp/internal/pbo/RangeReplacementOption;
invoke-virtual {v8}, Lcom/disney/dmp/internal/pbo/RangeReplacementOption;->getId()Logd;
# Logd { liveAvailBreak, liveAvailDistributor, liveAvailProvider }
#   -> Lngd { LiveAvailPublisher, LiveAvailDistributor, LiveAvailFull }
```

So `allowedLiveInterstitials` is `Insertion.getRanges()` mapped from the wire enum
`Logd` to the internal enum `Lngd` — a list of **which live avail classes this client
is permitted to replace**.

### 2.3 Correction: `allowedLiveInterstitials` is dead data on 26.20

The brief and the existing `DisneyPatch.kt` comment say emptying `getRanges()`
"prevents live interstitial gating from admitting any ad range". **That is not true on
this build.** Every read of the field in the whole dex:

```
eej.smali:39    iput-object p3, p0, Leej;->c:Ljava/util/List;   # constructor
eej.smali:128   iget-object v0, p0, Leej;->c  }
eej.smali:132   iget-object v1, p1, Leej;->c  }  equals()
eej.smali:218   iget-object v0, p0, Leej;->c      hashCode()
eej.smali:320   iget-object v1, p0, Leej;->c      toString()
```

Nothing functional consumes it. The only other Recipe reader is
`smali_classes5/gvg.smali:609`, which reads `eej.d` (mode), `eej.a` (insertionPoints)
and `eej.b` (adSession) — **never `eej.c`**.

Emptying `getRanges()` is still correct and should stay: it is the VOD range channel
and it feeds the Moshi `InsertionJsonAdapters`. But it buys **nothing** for live, and
the patch comment overstates its reach. That is the direct explanation for the
brief's observation that live ads appear "despite `getPoints()`/`getRanges()` already
returning empty": live breaks never travel through `Insertion` at all.

---

## 3. On-device evidence

### 3.1 What a live session looks like

Live capture, ESPN "College Gameday" inside Disney+, ~25 minutes of continuous
playback from 08:38:52:

| Oracle | Live (92,293 lines) | VOD baseline |
|---|---|---|
| `C2VdecComponent` / `CCodec` lines | **0** | 1,744 |
| decoder `onStopDone` | **0** | 12 |
| `WVCdm` operations | **1** | 421 |
| media-session states observed | `PLAYING(3)` only, 5,712 samples | mixed |
| position | strictly monotonic, 15,057,874 ms onward (~4h11m live-edge offset), no jumps or resets | per-title resets |
| `insertion` / `interstitial` / `adBreak` / `disneyadvertising` / `SGAI` / `SSAI` / `cuepoint` string hits | **0** | 0 |
| BAM-SDK dust `EdgeEvent` lines | 0 (buffer was cleared mid-playback, after session setup had already been logged) | 65 |

One continuous decoder session with no re-licensing, no period boundary and no
playback-state change is the signature of a **single stitched stream**. Client-side
ad periods would force decoder churn and new Widevine sessions, as the VOD baseline
shows they do. This is consistent with the passthrough branch of §1.4 and with
ESPN's own finding in this repo.

### 3.2 What this does *not* prove

Stated plainly, because it matters:

* **No break boundary was confirmed inside the capture window.** The user had not
  flagged a commercial break, so the measurement is *consistent with* passthrough
  SSAI, not proof of it. Getting a user-flagged break timestamp is the single
  highest-value next step.
* **logcat is a weak oracle for this app.** Disney's own logging goes through
  `Lp97;->w(Integer, Function0, int)`, and the release build emits **none** of it:
  the live capture contains zero lines matching any ad/insertion/interstitial
  vocabulary across 92k lines, and the only app-tagged lines are `cast_shell` and
  the platform's `BasicMediaStatusFactory`. Deciding which branch of §1.4 a given
  stream takes cannot be done from logcat on a release build — it needs a pcap (is
  an `X-ASSET-LIST` URI fetched during the break?) or an instrumented build.

---

## 4. Verdict on reachability, and the candidate seam

### 4.1 Reachable — yes, unambiguously

The live ad timeline is parsed in app Java/Kotlin bytecode: ~5,900 lines of smali,
`org.json`, joda-time, `LinkedHashMap`, `android.util.Base64`. No native boundary.
**Bytecode can reach it; DNS is not the ceiling for the schedule.**

### 4.2 The seam, if a stream takes the client-side branch

```
Lssc;->g(Lorg/joda/time/DateTime;Ljava/util/List;)V        # = playlistRetrieved
```

**What to do with it: filter `p2`, do not `return-void`.** Drop the `DateRange`
entries whose `CLASS` attribute (`Lf05;->b:LinkedHashMap`, key `"CLASS"`) equals
`"com.disney.media.break.v1"`, and pass the rest through. Emptying the ad *data*
while leaving the call itself intact is exactly the repo's hard-won rule.

Why not a blanket `return-void` at offset 0:

* It also discards `programDateTime` bookkeeping. `jcl.g` stores it
  (`iput-object p1, v0, Ld0i;->b:Lorg/joda/time/DateTime;`) and the static initial
  value is `{a = -1L, b = null}` — a sentinel the rest of the session reads.
* It discards **non-ad** `DateRange`s too (`com.apple.hls.interstitial` entries and
  anything else the stream carries).
* The passthrough branch `:cond_41` does real non-ad work — clearing `cub.q` and the
  asset map, and routing through `cub.a:Lwri;`. Skipping it leaves stale state.

**Hang risk, and why filtering the input is the safe shape.** The scheduler runs a
completion handshake: `"SgaiInterstitialController scheduleBreak() "` →
`"requestPod() ) assetList Size: "` → `"notifyAssetsReady() assets loaded, durationOfAssetsSkippedAtStart:"`
/ `"notifyAssetsError() areAssetsReady:"` → `"handlePrepareError() ["`. Removing a
break **before** it is scheduled is safe: the controller simply never learns about
it, and `jcl.g` already tolerates `f == null` (it logs and returns), a state the app
itself exercises before `bck` installs the delegate and after `nck.l()` clears it.
Cutting any of the *asset* callbacks instead would leave the controller waiting on
assets that never arrive — that is the hang. **Hook the input list; never the asset
callbacks.**

**Anchoring.** `Lssc;` and `g` are R8 names and will drift every version. Stable
anchors inside the method: the string literals `"com.disney.media.break.v1"`,
`"X-COM-DISNEY-PAYLOAD"`, `"X-ASSET-LIST"`, `"PLANNED-DURATION"`, plus the interface
shape `g(Lorg/joda/time/DateTime;Ljava/util/List;)V` on a class implementing the
8-method `Lnmh;`. The log-string lambdas are the cross-check:
`"playlistRetrieved, size = "` (`md5`) and
`"PlaybackSession playlistRetrieved() programDateTime:"` (`f7l`). Note `Lssc;` is a
25k-line R8 mega-merge, so matching by class identity alone is meaningless — match by
method shape plus the in-method strings.

### 4.3 The honest ceiling for the stream we measured

For ESPN live inside Disney+, the evidence points to the passthrough branch: the
commercials are in the delivered segments, nothing is scheduled client-side, and
there is **no ad data in the timeline to empty**. Bytecode removal has nothing to act
on there. The realistic route is the one `EspnLiveSlatePatch` already proved in this
repo — **detect the break window and mask it** (slate + mute) — driven off the
`playlistRetrieved` window data pinned in §1.2, which is precisely why that seam is
worth having mapped even though emptying it would be pointless.

Whether *other* Disney+ live streams (non-ESPN linear channels, live events) take the
client-side branch is **unknown and untested**. The gates are `cub.p:Li08` (a
`Function0<Boolean>` feature flag) and `cub.j:Z`; resolving what sets them is the
natural follow-up and would decide whether §4.2 is ever worth building.

---

## 5. What was tried, and the dead ends

| Thread | Outcome |
|---|---|
| `allowedLiveInterstitials()` as "live ad gating" | **Dead end, and the premise was wrong.** It is a Recipe *field*, not a method, read only by `equals`/`hashCode`/`toString`. See §2.3. The name came from a `toString()` literal. |
| `com.disney.dmp.internal.pbo.Insertion` `mode` (SGAI/SSAI) as the live/VOD discriminator | **Not the live path.** `Insertion` feeds the Recipe for VOD insertion points; live windows arrive out of band, via the HLS playlist. `mode` is read by `gvg` (`eej.d`) and does not carry live breaks. |
| Java vs native (the decisive question) | **Answered: Java.** §1.3. No JNI anywhere in the live break path. |
| ESPN's `com/disney/dmp/media3/ads/mel/SgaiPlaybackSession` fingerprint | **Does not port.** That clear class path is absent from Disney+ — Disney+ R8-minifies the DMP layer ESPN ships in the clear. The equivalent is `Lssc;->g` via `Lnmh;`, found here by log-lambda discriminator mapping. |
| `HlsSampleStreamWrapper` `"Ignoring EMSG. Expected it to contain wrapped application/id3 but actual wrapped format: null"` | **Not a live-ad trigger.** Fires on *both* live (65) and VOD (97) at segment cadence (~9–10 s). It is the general in-band event channel. Recorded so nobody chases it again. |
| media3 `HlsInterstitialsAdsLoader` / app-side `AdPlaybackState` | **Absent.** Disney does not use media3's interstitial machinery (§1.5), so no media3-level seam exists. |
| logcat as the branch oracle | **Insufficient.** Release build emits none of the app's own `p97.w` logging (§3.2). Needs pcap or a flagged break boundary. |
| `Lwri;` (the passthrough branch's target) | **Not pursued far.** Another R8 mega-merge, dominated by Conviva analytics key strings; the `cond_41` call reaches it through a generic accept/apply path. Low value. |
| `scripts/smali-search/esm.sh` indexing | **Unavailable on this machine** — `cindex`/`csearch`/`es.exe` are not in `E:\Tools`, so `esm index` exits 127 and `esm` falls back to `rg`. Targeted `rg -l` sweeps over `smali*` completed in seconds each; a tree-wide content grep was never needed. Worth installing the binaries per `scripts/smali-search/README.md`. |

---

## 6. Follow-ups, in priority order

1. **Get a user-flagged commercial-break timestamp on a live stream** and re-read the
   capture around it. This is what turns §3.1 from "consistent with passthrough" into
   proof, and it is cheap.
2. **pcap a live break.** If an `X-ASSET-LIST` URI is fetched mid-break, the stream is
   on the client-side branch and §4.2 becomes a real patch; if not, passthrough is
   confirmed and masking is the only route.
3. **Resolve the gates** `cub.p:Li08` and `cub.j:Z` — which feature flag drives them,
   and whether any Disney+ live surface turns them on.
4. **If masking is the route**, reuse `EspnAdBreakOverlayHelper` and read break
   windows from `Lssc;->g`'s `DateRange` list (`START-DATE`, `END-DATE`,
   `PLANNED-DURATION`), rather than inventing a second window source.

## 7. Files and line references

| What | Where |
|---|---|
| Interstitial playlist-line predicate | `smali_classes4/x73.smali:176` (`invoke`, `pswitch_3`) |
| `Lnmh;` listener interface (= `SgaiPlaybackSession` surface) | `smali_classes6/nmh.smali` |
| `playlistRetrieved` logging decorator | `smali_classes6/jcl.smali:396` |
| `playlistRetrieved` real implementation | `smali_classes6/ssc.smali:10202`–`16143` |
| `scheduleLiveInterstitials` log site | `smali_classes6/ssc.smali:10350` |
| `com.disney.media.break.v1` discriminator | `smali_classes6/ssc.smali:10437` |
| `X-RESUME-OFFSET` | `smali_classes6/ssc.smali:12965` |
| Live ad scheduler / `SynthAsset:` | `smali_classes5/cub.smali:339` |
| `SgaiInterstitialController scheduleBreak()` caller | `smali_classes5/fam.smali:2509` |
| Recipe data class (`allowedLiveInterstitials` label) | `smali_classes5/eej.smali` |
| Recipe construction from `Insertion.getRanges()` | `smali_classes6/mcl.smali:1453` |
| Only functional Recipe reader (reads `a`/`b`/`d`, not `c`) | `smali_classes5/gvg.smali:609` |
| Delegate install / clear | `smali_classes4/bck.smali:165` · `smali_classes6/nck.smali:6442` |
| Log-lambda tables | `smali_classes3/f7l.smali:2704` · `smali_classes6/md5.smali:569` |
