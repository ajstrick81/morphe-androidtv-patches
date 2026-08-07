# Netflix ad-strip investigation — session handoff

**For:** a fresh session picking this up. **Status:** ⚠️ **REOPENED** — the earlier
"not strippable / research-parked / closed" verdict has been **withdrawn**. See
**`REOPENING.md`** (read it first): the appboot signature was misread as a runtime wall
when it is a **load-time door**, and the app's own **no-ad path runs 6 of 7 titles**
(§3e). The live lead is **seam A — scrub ad-break markers from the decrypted manifest
object in the Hermes heap**, downstream of both MSL and the signature. This doc + the
detail below (`PORTABILITY-ASSESSMENT.md`) remain the full architecture context; only the
*conclusion* changed.

---

## 0. Session re-verify (2026-08-06, on-device 13.0.1-25028)

Ran REOPENING.md **step 1** against the *current* device APK (Netflix updated 25009 → **25028**;
pulled `split_config.armeabi_v7a.apk` off the Onn at `.211`, extracted `libnetflix.so`, 88.5 MB).
**All seam markers survive** — architecture is unchanged by the bump:

| marker | count | meaning |
|---|---|---|
| `HERMESATOM` | 1 | Hermes engine present |
| `appboot_fail_nas_verify` / `appboot_key` | 2 / 5 | appboot signature door present |
| `RSASSA` / `SPKI` | 15 / 31 | appboot pubkey verify present |
| `getMslEncoderFactory` / `MslEncoderFormat` | 53 / 18 | MSL layer present |
| `milo.prod.js` / `milo_update_url` / `milo_ignore_hash_errors` | 6 / 1 / 3 | milo present |
| `libandroid_netflix` / `OpenSSL 3.2.1` | 1 / 43 | soname + crypto unchanged |

**⭐ BONUS FINDING (upgrades the seam strategy):** the appboot / nrdp / milo layer is embedded as
**plain minified JavaScript source**, NOT Hermes bytecode (HBC). Proof: the `appboot_fail_nas_verify`
and `milo_ignore_hash_errors` strings appear *inside readable JS* — e.g. `l.declare({appboot_key:…,
appboot_fail_nas_verify:["appboot_fail_nas_verify",!1],…})` and
`if(fe.milo_ignore_hash_errors){…warn("Allowing insecure response…")}`. HERMESATOM=1 / "Hermes "=0
means Hermes is the *runtime* but boot scripts are loaded as JS via `nrdp.gibbon.loadScript`.
Consequence: **seams B and C (live-edit the appboot ad-break resolver to force its own empty return)
are viable**, not just seam A (data scrub) — the resolver will be readable JS in the heap. This was
the main open risk from the assessment ("if dumps are HBC, lead with seam A"); it is retired.

Also surfaced: `appboot_test_response`, `appboot_drop_mt`, `appboot_ignore_retrycontrol` config
knobs worth probing during dump analysis. **Next = REOPENING.md step 2 (build gadget APK).** Blocker:
no armeabi-v7a frida-gadget `.so` present locally yet (only an unrelated arm64 frida-*server* on D:).

---

## 1. What this was

A portability experiment: *does the "native in-process ad-strip toolkit" (built from the Prime
Video work) port to other APKs?* Test target: **Netflix Android TV** (`com.netflix.ninja`,
`13.0.0-25009`, armeabi-v7a). Work committed on branch **`claude/toolkit-cross-apk-compat-ku719r`**
under `experimental/netflix-native-adstrip/`.

## 2. The answer (definitive)

**Netflix's ads cannot be stripped with the toolkit's approach (or DNS, bytecode, or network
MITM).** They are protected by three independent layers, confirmed both statically and empirically:

```
TLS (Cronet/static OpenSSL) → MSL (Message Security Layer, in JS) → appboot RSA/ECDSA signature
```
and delivered as **pure same-host SSAI** (ad served from the same `oca.nflxvideo.net` Open Connect
hosts as content). No separate ad host, no third-party beacon, no blockable plane.

The toolkit "ports in spirit but not in mechanism": its premise is hooking a native plaintext seam,
but Netflix decrypts MSL inside an embedded **Hermes JS engine**, so the plaintext manifest is a
JS-heap object, not a native buffer — and the ad-bearing player JS is signature-locked.

## 3. Architecture map (measured, not guessed)

- **`libnetflix.so`** (84 MB, soname `libandroid_netflix.so`) = the nrdp monolith: **Hermes** JS
  engine + **Gibbon** renderer + static **OpenSSL 3.2.1** + **MSL** (implemented in JS). Application
  subclass `Lcom/netflix/ninja/NetflixService…` → actually `Lcom/netflix/ninja/NetflixApplication;`
  (`extractNativeLibs=false`). No `lib/` in base.apk; native libs are in the `config.armeabi_v7a`
  split.
- **milo** = downloadable JS **networking** layer (HTTP/WS/diskCache/MSL transport). Hash-checked
  (`milo_ignore_hash_errors` bypass exists). Fetched from
  `occ.a.nflxso.net/genc/nrdp/milo/<ver>/milo.prod.js`. **Contains NO ad logic** (it's plumbing).
- **appboot UI app** = the player/UI JS (Gibbon-loaded from `appboot.netflix.com`). **This holds the
  ad-break logic**, and is **RSA/ECDSA signature-verified** against a pubkey baked into
  `libnetflix.so` (`appboot_key`/SPKI/RSASSA). This is the wall.
- **dex** = thin Java shell; no milo loader / JS bridge / integrity class. Every "advert*" string in
  the app is **Bluetooth LE / MDX casting** or Google Ad-ID, NOT video ads (proven by decompiling
  `Lo/getArguments;` = the BLE advertiser agent — see `decompiled/BleAdvertiseAgent.deobfuscated.java`).
- **Empirical capture:** a real 15s pre-roll session hit 18 unique hosts; the ONLY non-Netflix one is
  `sessions.bugsnag.com` (crash telemetry). Ad came from the same OCA hosts as content = SSAI.
  Frequency-capped (~7 movies no ad, then one) → ad decision is server-side/in-manifest.

## 4. Committed artifacts (on the branch)

- `experimental/netflix-native-adstrip/PORTABILITY-ASSESSMENT.md` — the full running assessment
  (§0 toolkit self-check → §3e empirical capture → §8 verdict; also a prior-art survey table).
- `experimental/netflix-native-adstrip/decompiled/BleAdvertiseAgent.deobfuscated.java` — deobfuscated
  BLE agent (proof "advert"==Bluetooth, not video ads).
- **Reusable tooling** (built this session, generic — works for any app in the harness):
  - `testing/scripts/capture.sh` — drive PCAPdroid on the Onn over adb (start/stop/pull/run, `--decrypt`).
  - `testing/scripts/analyze_pcap.py` — DNS+SNI host-footprint report from a pcap; `--ad MM:SS-MM:SS`
    flags AD-ONLY hosts; `--vs ADFREE.pcap` A/B-diffs an ad title against an ad-free one. (needs `scapy`.)
  - `testing/capture-runbook.md` — the timestamped capture + A/B protocol.
  - `netflix` added to `testing/config/apps.conf`.

## 5. Local environment / workflow (user's Windows PC + Onn TV)

- **Onn Android TV** at `adb connect 192.168.12.210:5555`. Developer options + Network debugging on.
- **PCAPdroid** installed on the Onn (`com.emanuelef.remote_capture`); needs its **Control permission**
  granted for headless adb control. PCAP saves to `/sdcard/Download/PCAPdroid/`.
- User has **Wireshark** (used tshark/GUI filters), **AdGuard Premium** (PC), and can rig a MITM.
- **Division of labor:** the cloud session can't reach the LAN/Onn — the user runs captures on their
  PC and uploads pcaps; the session analyzes them. (adb/PCAPdroid = user side; analyze_pcap = here.)
- **Files the user has locally (NOT in repo, big/copyright):** the Netflix `.apkm` and its splits,
  the reassembled `libnetflix.so` (sha256 `b3873f00…`), `milo.debug.js` (5.7 MB), the capture pcaps.
  A fresh cloud session won't have these — re-request from the user if needed.

## 6. If resuming

- Nothing is pending on Netflix — it's closed. Reopen only if Netflix moves ads to a separable plane
  (re-run the §3e capture to check for a new non-Netflix ad host).
- The **capture→analyze pipeline is the real reusable win** — point it at a *softer* target next
  (an app with clean bytecode ad hooks like the repo's working ones). `analyze_pcap.py --vs` is ideal
  for any "which hosts are ad-specific" question.
- Useful Wireshark display filter that nailed it: non-Netflix SNI →
  `tls.handshake.extensions_server_name and not (…contains "nflx" or …contains "netflix")`.
