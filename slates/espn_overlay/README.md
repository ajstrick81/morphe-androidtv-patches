# ESPN "Be Right Back" animated overlay slate

Source for the `overlay` slate mode of the ESPN Android TV patch — an animated
1920×1080 broadcast graphic (WebGL background + live countdown + "UP NEXT" strip)
shown during commercial breaks.

## Files
- `index.html` — the overlay page (markup + inline styles + vanilla countdown +
  scale-to-fit). Config via query params (all optional):
  - `secs` — countdown length in seconds (default 150; loops at 0)
  - `brand` — badge wordmark (default `ESPN`)
  - `tag` — tagline (default `We'll resume shortly`)
  - `up` — "UP NEXT" copy (default `Live coverage continues`)
  - `speed` — 3D motion speed, `0` freezes (default 1)
  - `scanlines` — `0` to disable the scanline overlay
- `bg-three.js` — the three.js scene as a self-registering `<bg-three>` custom
  element (global `THREE`, no ES-module import — loads cleanly from `file://`).
- `three.min.js` — vendored three.js r160 (UMD build).

Adapted from a Claude Design handoff prototype. The badge is a plain text
wordmark, not a licensed logo — nothing copyrighted is bundled.

## Install to a device
The patch loads these from the app's external files dir at runtime, so updating
the design is a push — no APK rebuild:

```
D=/sdcard/Android/data/com.espn.score_center/files/espn_overlay
adb shell mkdir -p $D
adb push index.html   $D/index.html
adb push bg-three.js  $D/bg-three.js
adb push three.min.js $D/three.min.js
# REQUIRED (Android 11+): a shell-created subdir is 0770 (drwxrws---), so the
# app (a different uid) can't traverse it and the overlay silently falls back
# to the card. Make it world-traversable so the app can read the assets:
adb shell chmod 0775 $D
```

Then select **Be Right Back** in the on-screen slate picker, or:
```
printf 'overlay' > slate_mode && adb push slate_mode /sdcard/Android/data/com.espn.score_center/files/slate_mode
```
Optional countdown length: push an `overlay_secs` marker (integer seconds).

If the assets are absent, `overlay` mode falls back to the built-in card.
