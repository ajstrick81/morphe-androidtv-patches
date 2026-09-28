# PV ATV in-process PRS ad-strip — Frida bench scripts (2026-07-21)

**Status: VERIFIED working on-device (Onn 4K Plus), NOT yet productized.** These scripts run the
strip from a PC over adb via the installed Frida-gadget build. They are the reference
implementation to port into a no-PC shippable form (inline-hook `.so` or gadget auto-load).

> ⚠️ This is a bench setup. The gadget only listens; the strip logic lives in `cmod-strip2.js`
> on the PC. Nothing is persistent — the app must be attached each session, and PV respawns its
> process often (detaches the script). A hands-off multi-day test needs the productized form.

## What it does
Removes Prime Video ad breaks in-process by blanking `{"type":"Remote",...}` items out of the
decrypted `GetVodPlaybackResources` (PRS) `intraTitlePlaylist` JSON **before the parser reads
it** — the in-process equivalent of the proven MITM strip, no PC-side proxy.

## How it works (the hard-won findings)
- The PRS response is decrypted **inside libignite** (static BoringSSL); it never appears at the
  system `libssl` or the socket `read`/`recv` boundary. `inflate@0xd32f7a` and the SSL_read
  wrapper `@0xc4fe3c` are the WRONG paths (don't carry PRS / never fire).
- The decrypted JSON **is memcpy/memmove'd** inside libignite. A **CModule** native filter finds
  it cheaply on the hot path (JS-level hooking of memcpy starves the thread).
- **Only blank a COMPLETE array.** libignite copies the playlist in **truncated** 4–16KB chunks
  (a `Main` element can be huge, e.g. `endMs:671921` with a giant URL); blanking a truncated
  chunk smashes partial `Main` content → **black screen**. The full array arrives in one large
  copy (~40–68KB, `arrayClosed`) at callers like `libc.so+0x3ea01` / `libignite+0xae44a5`.
  `cmod-strip2.js` parses the whole array to its closing `]`; if anything is truncated it SKIPS
  the buffer entirely. Blank is same-length (Remote object + one comma → spaces) so JSON stays
  valid and buffer length is unchanged.
- Breadth test 2026-07-21: 24 strips, 82 Remote items blanked, 494 truncated buffers safely
  skipped, 0 errors; no prerolls / no mid-roll markers / no black screens across many movies.
  **TV shows still need dialing in** (more/denser breaks).

## Files
- `cmod-strip2.js` — the WORKING strip (blank complete arrays only). `MODE='strip'|'detect'`.
- `cmod-diag.js` — detect-only; dumps each element's type/span and `arrayClosed` (safe, no writes).
- `cmod-memcpy.js` — the detector that first located the seam (marker → caller backtrace).
- `heap-scan-once.js` — detection oracle: proves ads are being served this session (frequency-cap
  guard) by finding the resident playlist in `rw-` memory.
- `ssl_driver2.py` — resilient driver: loads a script on a daemon thread and ignores the gadget's
  unreliable `load()` ACK TransportError (the script runs on-device regardless).

## Run (bench)
```
adb connect <device>:5555
adb forward tcp:27042 tcp:27042
adb shell am force-stop com.amazon.amazonvideo.livingroom
adb shell am start -n com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity
python -u ssl_driver2.py cmod-strip2.js      # watch [STRIP2]/[hb]; cold-start a title
```

## Gotchas
- Frida API: `Module.getExportByName` is DEAD in this frida — use
  `Process.getModuleByName('libc.so').getExportByName(name)` (method form). The dead call
  silently no-ops hooks and yields FALSE negatives; always confirm a hook fires before trusting 0.
- CModule C source lives in a JS template literal → **no backticks in the C** (even in comments).
- `kill -9` on the driver jams the gadget's single script slot → next `create_script` times out →
  force-stop + relaunch the app to clear it.
- Ad **frequency capping** kicks in after ~2h of repeated starts on one account (ads stop even
  with a passive script) — confirm ads are served (oracle / observe) before trusting "ads gone".
- Offsets are per-build; the marker/`arrayClosed` approach is version-robust, the specific caller
  offsets are not.
