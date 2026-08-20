# YouTube TV — capture runbook (local PC handoff)

Handoff for the **local Claude Code session on the Windows PC** that has `adb`
access to the Onn. The capture tooling was authored in a cloud session that can't
reach the device; it's all committed, so a local session pulls and runs directly.

Branch: `claude/scte35-youtube-tv-signaling-vyy0cy`
Device under test: **`<ONN_IP>:5555`** (prior Onn boxes: `192.168.12.209/.210/.211`)

## 1. Get the tools onto this PC
```bash
git fetch origin claude/scte35-youtube-tv-signaling-vyy0cy
git checkout claude/scte35-youtube-tv-signaling-vyy0cy
git pull
```
The assets land at:
- `experimental/youtubetv-scte35/network_security_config.xml`
- `experimental/youtubetv-scte35/frida/yttv_tap.js`
- `experimental/youtubetv-scte35/frida/libgadget.config.so`
- `experimental/youtubetv-scte35/README.md`  ← full apply/bundle/install steps
- `docs/YOUTUBE_TV_DASH_CAPTURE_PROCEDURE.md`  ← method selection + decision table

## 2. One-time PC prerequisites
```bash
pip install frida-tools mitmproxy threefive        # capture + SCTE-35 decode
# apktool, apksigner (build-tools), adb (platform-tools) on PATH
# frida-gadget for armeabi-v7a: download, rename to libgadget.so, place at
#   experimental/youtubetv-scte35/frida/libgadget.so   (NOT committed — supply locally)
```

## 3. Run
Follow `experimental/youtubetv-scte35/README.md`:
1. Confirm the package id + whether it's a system app
   (`adb shell pm list packages -s | findstr /I "youtube unplugged"`).
2. Bundle gadget + NSC into the decoded APK, re-sign all splits with one key.
3. `adb install-multiple`, launch, `adb logcat | findstr yttv-tap`, watch a live
   channel through an ad break.
4. `adb pull /sdcard/Android/data/<pkg>/files/yttv_tap ./yttv_tap`.

## 4. Report back
Commit the `yttv_tap/` dumps (or drop them into the chat). Next step is decoding
the SCTE-35 and filling the decision table to set the ad-reduction ceiling.

> If this PC's session hits the system-app or Play-Integrity walls, note which —
> those outcomes are findings, not dead ends (see the README's "Known Onn walls").
