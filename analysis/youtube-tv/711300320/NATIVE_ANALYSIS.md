# YouTube for Android TV 7.11.300 — Native Engine Analysis (`libchrobalt.so`)

Analysis of `split_config.armeabi_v7a.apk` → `lib/armeabi-v7a/libchrobalt.so`
(68 MB). "Chrobalt" = the current **Chromium-based Cobalt** engine. Findings
here answer the open question from `PATCH_STRATEGY.md`: **can we do a purely
on-device solution, or is an external network layer required?**

Libraries in the split:
- `libchrobalt.so` (68 MB) — the Chromium/Cobalt engine (UI + net + media)
- `libchrome_crashpad_handler.so`, `libnative_crash_handler_jni.so`,
  `libnativecrashdetectorutil.so` — crash reporting
- `libframesequence.so` (animated images), `libaffinityconfigurator.so`,
  `libmemorymappedbufferjni.so`

---

## 1. Traffic separation — the good news

Ad/video/DRM traffic splits cleanly by host, which is what makes filtering
practical:

- **Video (must NEVER block):** `*.googlevideo.com` — path `/videoplayback`,
  `/videochunk`, `/initplayback`, etc. (confirmed from the engine's
  domain-reliability config). Also `redirector.googlevideo.com`.
- **DRM / licensing (must NEVER block):** Widevine via
  `dev.cobalt.media.MediaDrmBridge` — L1/L3, provisioning + license requests.
  Filtering must pass these through untouched or playback dies.
- **Ads / tracking (candidates to block):** the engine embeds Chromium's
  ad/tracker host awareness — `doubleclick.net`, `g.doubleclick.net`,
  `ad.doubleclick.net`, `googlesyndication.com`, `googletagmanager`,
  `pubads`, `pagead`. Leanback ad breaks are also negotiated in the InnerTube
  **player-response** payload (served from `www.youtube.com` / `youtubei`),
  so a complete solution needs **both** host blocking *and* response filtering
  of the player response.

The upshot: ads and video are on different hosts, so a filter can be surgical.

## 2. Proxy / interception capability — the decisive check

| Switch (string present in engine) | Implication |
|---|---|
| `--proxy-server` | **ABSENT** — the standard Chromium proxy switch appears compiled out. You cannot simply point Cobalt at a proxy via `--proxy-server`. |
| `host-rules`, `host-resolver-rules` | **Present** — Chromium's hostname-remap switches. These are the viable redirect mechanism (`MAP www.youtube.com <proxy-ip>`) *if honored in this build*. |
| `ignore-certificate-errors`, `ignore-certificate-errors-spki-list` | **Present** — needed to make the engine trust a MITM CA. |
| `unsafely-treat-insecure-origin-as-secure`, `allow-insecure-localhost`, `user-data-dir`, `remote-debugging-port` | Present as strings. |

**Important caveat:** a switch's *string* being in the binary does **not** prove
it is *honored* at runtime. Chromium/Cobalt release builds commonly gate these
net/debug switches behind build flags or a `--test-type` guard, and Cobalt strips
much of Chromium's surface. The only switch we've **confirmed honored** is
`--url=` (the app uses it live). Whether `host-rules` + `ignore-certificate-errors`
actually take effect here can only be settled by an **on-device test**, not
static strings.

Command line is ingested from the Java side (`mJavaSwitches` /
`CobaltActivity.getJavaSwitches()`), so *if* these switches are honored, they can
be injected without recompiling the engine — via a small smali edit to seed the
switch map, the same path that already carries `--url`.

---

## Verdict

- **Robust, known-good path: external network filtering layer.** DNS sinkhole for
  the ad hosts + a LAN MITM proxy that filters the InnerTube player-response,
  leaving `googlevideo.com` and Widevine licensing untouched. This depends on
  **no** engine switch and **survives app updates** with no re-patching. This is
  the recommendation.
- **Possible on-device path (needs validation): `host-rules` + custom CA.** If the
  engine honors `host-rules` and `ignore-certificate-errors-spki-list` in this
  release build, we could redirect `www.youtube.com` to an on-device filtering
  service and trust its CA — a self-contained mod, no external box. **Unproven
  until tested on a device.**
- **`--proxy-server` route: ruled out** (switch not present).

### On-device validation test (settles the open question)

On a rooted / ADB-accessible ATV device:

1. Stand up a local filtering proxy with its own CA.
2. Launch with switches injected (adb, or the seeded `mJavaSwitches`):
   ```
   --host-rules="MAP www.youtube.com 127.0.0.1:8888"
   --ignore-certificate-errors-spki-list=<your-CA-SPKI>
   ```
3. Watch whether the app (a) reaches the proxy and (b) still plays video.
   - Reaches proxy + plays → **on-device solution is viable**; build the filter.
   - Ignores switches / TLS fails → fall back to the **external network layer**.

Because we verified (`ANALYSIS.md`/`PATCH_STRATEGY.md`) that a **non-YouTube
startup URL is permitted** (guard only logs + disarms the watchdog), the
redirect target is free to be our own filtering front-end without the app
self-killing.

---

## What this means for "making YouTube tolerable"

Both viable paths keep the **real leanback app** (so DRM/playback keep working)
and strip ads in transit — they differ only in *where the filter runs*
(external box vs. on-device). Neither requires — nor benefits from — swapping in a
third-party frontend, which Cobalt's restricted engine wouldn't render anyway.
