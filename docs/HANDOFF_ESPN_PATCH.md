# HANDOFF — ESPN Android TV patch (Morphe)

_State as of 2026-09-07._

## STATUS: all shipped & documented
- **v1.33.0** — animated "Be Right Back (ESPN Ad-Break Overlay)" WebView slate (three.js background
  + live countdown), score-API 403 fix, on-screen slate picker, cleaned video clips, perf/polish
- **v1.33.1** — picker simplified to Video / Video+Score / Overlay / Blank
- **v1.34.0** — Clone ESPN + Disable auto-updates + live slate made an optional patch
- README ESPN section documents all patch options (docs-only)
- `main` is current; Onn 4K (`.211`) running latest, `slate_mode=overlay`

## ESPN patch = FOUR selectable patches (pkg `app.morphe.patches.espn`)
- **ESPN Android TV** (core, always on) — DMP SGAI `isAdDisabled` → true; kills VOD/scheduled ads.
  File: `EspnAdsPatch.kt`. Compat: `AppCompatibilities.ESPN_TV` (`com.espn.score_center` 6.11.1).
- **ESPN live commercial-break slate** (default on, deselectable) — `dependsOn` core; injects the
  5 overlay hooks; `extendWith` the extension. File: `EspnLiveSlatePatch.kt`.
- **Disable auto-updates** (default on) — `versionCode` bump. File: `DisableAutoUpdatesPatch.kt`.
- **Clone ESPN** (opt-in, off) — package rename + provider/permission uniqueness for Fire-TV
  system-app installs. File: `CloneAppPatch.kt`. ⚠️ NOT yet tested on a real system-app device.

## Extension helper (the on-device slate)
`extensions/.../espn/ads/EspnAdBreakOverlayHelper.kt` — window detection (playhead + manifest
DateRanges), slate render + mute, D-pad picker. Runtime markers in the app's external files dir:
- `slate_mode` = `video` | `video+score` | `overlay` | `blank` | `card` | `scoreboard` | `ads`
- `picker_alpha` = 0..100 (picker scrim %, default 35)
- `scoreboard_on` = comma `sport/league` (enables live-score strip)
- `slate_off` = existence disables the live slate
- `overlay_secs` = optional countdown length (else uses REAL ad-window remaining, else 150)
- `espn_slate_1.mp4` / `_2.mp4` (round-robin video slates); `espn_slate*.png` also works
- `espn_overlay/` = `index.html` + `bg-three.js` + `three.min.js` (the animated overlay; source
  vendored in repo `slates/espn_overlay/`, with README)

## Build / deploy recipe (Onn 4K test unit)
- `adb connect 192.168.12.211:5555`
- `export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2g"`
  `./gradlew :patches:build -x test --exclude-task :patches:stub:compileJava`
- CLI `/d/Tools/morphe-cli-1.9.1-all.jar` ; APKEditor `/d/Tools/APKEditor-1.4.9.jar`
- ⚠️ mpp filename tracks the version: use `patches/build/libs/patches-<CURRENT>.mpp`
  (semantic-release bumps it — do NOT hardcode an old version)
- `java -jar $CLI patch -f -e "ESPN Android TV" -p patches-<ver>.mpp -o out.apk espn_merged.apk`
  (default set = core + slate + disable-updates; clone is opt-in). Merged APK regen: APKEditor merge
  of the ESPN `.apkm` from Downloads.
- `adb install -r out.apk` ; then MANDATORY: force-stop `com.espn.score_center` + `monkey` LEANBACK
  relaunch
- Verify: `adb logcat -s MORPHE-ESPN-SLATE:V`

## GOTCHAS (cost real time)
- Pushing overlay assets to a subdir: `adb shell mkdir` makes it `0770` (`drwxrws---`), app (diff
  uid) can't traverse → overlay silently falls back to card. FIX: `adb shell chmod 0775 <dir>` after
  push.
- Overwriting an existing on-device file via `adb push` can fail "remote fchown failed" → stage via
  `/data/local/tmp` then `cp` into place + `chmod 0644`.
- ESPN `site.api` 403s `Mozilla/5.0` and default-Java UAs → use `okhttp/4.9.0`.
- WebGL perf on the Onn: pin `renderer.setPixelRatio(1)` (was 2 → rendering at 4K), antialias off,
  cap render to ~30fps (competes with ad-video decode). Look preserved.
- Morphe CLI silently ignores a wrong `-e` name; `-e` is additive to the default set, not exclusive.

## OPEN ITEMS
1. **Clone ESPN** — verify on a device with ESPN preinstalled as a system app (Fire TV).
2. **Scoreboard "match the game you're watching"** — API works now (okhttp), but session recon only
   exposed `getDebugUUID` (no clean eventId). Needs manifest-path parse or a deeper endpoint; blocked
   only by no live football during the session. Tennis needs a different endpoint.
3. **Slate startup** still has a slight cold-start beat (WebView boot); fade-in mitigates.
   Pre-warming the WebView on playback start would make it instant (not yet done).
