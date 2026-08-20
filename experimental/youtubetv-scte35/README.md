# YouTube TV (Unplugged) — capture scaffolding

Rootless capture assets for the DASH/ad-signal measurement described in
[`docs/YOUTUBE_TV_DASH_CAPTURE_PROCEDURE.md`](../../docs/YOUTUBE_TV_DASH_CAPTURE_PROCEDURE.md).
This is **RE/capture tooling, not a shipping patch** — it exists to answer the one
open question: *how much YouTube TV ad fill is client-fetched (removable) vs.
server-welded SSAI (the floor)?*

Files:
| File | What it is |
|---|---|
| `network_security_config.xml` | User-CA `overridePins` trust override — opens the **Java/platform** TLS stack to a proxy. |
| `frida/libgadget.config.so` | frida-gadget config (script mode by default; listen mode documented inline). |
| `frida/yttv_tap.js` | The gadget script: **Cronet/BoringSSL unpin** + Java unpin + an **obfuscation-proof** in-process payload sniffer (dumps `.mpd` + `adSlotRenderer`/`cuepoint`/`InstreamAdBreak`). |

> ⚠️ You supply the `libgadget.so` binary yourself (armeabi-v7a frida-gadget,
> renamed) — same as the Netflix flow; it is **not** checked in (large, ABI-specific,
> not ours to redistribute).

---

## Why both an NSC change *and* a native unpin

The [reanalysis](../../docs/YOUTUBE_TV_SCTE35_REANALYSIS.md) found two TLS paths:

- **Platform/Java TLS** (some InnerTube calls) → opened by
  `network_security_config.xml` (`overridePins`) + a user-store CA. This is the
  same lever proven in [`testing/pluto-runbook.md`](../../testing/pluto-runbook.md).
- **Native Cronet** (`libcronet…so`, carries the media + player-response fetches)
  → does **not** read the NSC. Handled by the **BoringSSL unpin** in
  `yttv_tap.js` (`SSL_[CTX_]set_custom_verify` → `ssl_verify_ok`).

Ship both, or you'll capture only half the traffic.

**Or skip the proxy entirely (Method 0):** `yttv_tap.js`'s sniffer dumps the same
payloads from *inside* the decrypted pipeline — no proxy, no CA. The unpin then
just keeps the app from noticing anything is off.

---

## Applying the NSC override (two routes)

**Route A — quick, manual (apktool):**
```bash
apktool d base.apk -o yttv_dec
# If the app already ships res/xml/network_security_config.xml, MERGE our
# base-config + domain-config into it (don't blindly overwrite — keep its
# existing entries). Otherwise copy ours in and reference it in the manifest:
#   <application ... android:networkSecurityConfig="@xml/network_security_config">
cp network_security_config.xml yttv_dec/res/xml/
apktool b yttv_dec -o yttv.patched.apk
```

**Route B — as a resourcePatch (repo-consistent):** drafted at
[`patches/.../youtubetv/misc/security/CertificatePinningPatch.kt`](../../patches/src/main/kotlin/ajstrick81/morphe/patches/youtubetv/misc/security/CertificatePinningPatch.kt)
(+ [`shared/Constants.kt`](../../patches/src/main/kotlin/ajstrick81/morphe/patches/youtubetv/shared/Constants.kt)).
It writes the authoritative NSC above and repoints the manifest
`android:networkSecurityConfig`, modeled on the vix patch. **Before building,
confirm the two placeholders in `Constants.kt`** (package id + a real
`AppTarget` version) on the Onn — they're marked UNCONFIRMED. Not yet compiled in
this environment (gradle distribution unreachable behind the proxy); the local
PC build is its first real compile.

---

## Bundling the gadget (rootless, ADB sideload)

Mirror the Netflix flow
([`experimental/netflix-native-adstrip/frida/README.md`](../netflix-native-adstrip/frida/README.md)):

```bash
ABI=armeabi-v7a
# into the decoded APK:
cp <your-frida-gadget>              yttv_dec/lib/$ABI/libgadget.so
cp frida/yttv_tap.js               yttv_dec/lib/$ABI/libgadget.script.so   # script, named .so
cp frida/libgadget.config.so       yttv_dec/lib/$ABI/libgadget.config.so
# inject the loader at Application.onCreate (smali):  const-string "gadget"
#   invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
# manifest: extractNativeLibs="true" (so the .so's land in a readable dir),
#           android:debuggable="true" (optional; eases run-as pulls)
apktool b yttv_dec -o yttv.patched.apk
apksigner sign --ks debug.ks yttv.patched.apk        # re-sign ALL splits with ONE key
```

Install the whole split set atomically and launch:
```bash
adb connect <ONN_IP>:5555            # accept "Allow debugging?" on the Onn
adb install-multiple yttv.patched.apk split_config.*.apk
adb shell monkey -p <pkg> 1
adb logcat | grep yttv-tap           # HITs + dump paths
adb pull /sdcard/Android/data/<pkg>/files/yttv_tap ./yttv_tap
```
Confirm `<pkg>` on-device: `adb shell pm list packages | grep -iE "youtube|unplugged"`.

---

## Known Onn walls (expect these — see the Netflix HANDOFF)

1. **Preinstalled system app** → in-place re-signed install fails
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Fix: **package-rename clone** installed
   alongside stock. Check: `adb shell pm list packages -s | grep -iE "youtube|unplugged"`.
2. **Play Integrity + Widevine** after re-sign → may block login or drop to
   L3/SD. Use a throwaway account; verify playback before trusting the capture; if
   it hard-blocks, that's itself a finding → fall back to host/volume-only capture
   + on-screen ground truth.

---

## Feed the dumps back

Drop the `yttv_tap/` dumps (`*_MPD_*`, `*_adSlotRenderer_*`, `*_cuepoint_*`,
`*_InstreamAdBreak_*`) into the chat. We decode the SCTE-35 with `threefive`, map
`segmentation_type_id`s against the
[reference table](../../docs/SCTE35_AD_SIGNALING_REFERENCE.md), and fill the
[decision table](../../docs/YOUTUBE_TV_DASH_CAPTURE_PROCEDURE.md#decision-table-what-the-capture-proves)
— which sets the realistic ad-reduction ceiling and picks the suppression lever.

---

## Credits

SCTE-35 signaling groundwork follows *"The Essential Guide to SCTE-35"* by
**Andy Francis** and **Alex Zambelli** (Bitmovin / VidTech, 2026) — full credit to
their work, which shaped what these dumps look for and how we decode them.
