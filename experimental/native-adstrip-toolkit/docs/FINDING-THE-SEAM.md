# Finding the in-process seam (Frida bench)

Goal: locate the exact function inside the app where the plaintext ad payload
appears, so the `.so` knows what to hook. This is a **PC-attached Frida bench**
— throwaway, exploratory. The scripts referenced here are in
[`../scaffold/tools/frida/`](../scaffold/tools/frida/).

## Prereqs

- A Frida server or a **Frida-gadget** build of the target app on the device
  (the gadget listens; your PC drives it over adb).
- `adb connect <device>` and `adb forward tcp:27042 tcp:27042`.
- Knowledge of a *marker* that identifies the payload (a JSON key like
  `intraTitlePlaylist`, a URL token like `/iad_`, a manifest tag).

## Step 1 — is the payload even at the boundary you expect?

Hook the obvious candidates and log whether the marker ever passes through:
`SSL_read`, `recv`/`read`, `inflate`. On many apps the crypto is **statically
linked and stripped** inside the app's own `.so`, so the marker **never** shows
at system `libssl`/socket level. If the candidates come up empty but the app is
clearly playing ads, the payload is being handled internally — move to step 2.

> ⚠️ Confirm your hook actually fires. A dead hooking API (or a wrong module
> name) returns a silent zero that looks identical to "payload not here." Log
> unconditionally on entry first, *then* add the marker check.

## Step 2 — find the internal copy seam

The decrypted buffer is almost always `memcpy`/`memmove`'d internally. Use
[`find-copy-seam.js`](../scaffold/tools/frida/find-copy-seam.js): a **CModule**
(native-speed) hook on `memcpy`/`memmove` that scans each copied buffer for your
marker and, on a hit, logs the **return address** (→ which function/offset in
which module is doing the copy). Hooking `memcpy` from JS is too slow and
starves the thread — the CModule is why this is tractable.

You'll get a caller like `libfoo.so+0x3ea01` — that (and the module) is your
seam.

## Step 3 — characterise the payload's delivery

Before you design the transform, learn how the payload arrives:
- Does the **whole** unit arrive in one copy, or in **truncated chunks**? (Log
  each hit's size and whether the closing bracket is present.) This decides
  same-length-blank-one-copy vs reassemble-whole-body.
- Is there a size ceiling you'll want on the hot path (to skip huge video
  copies) — and could a large/dense payload exceed it?
- Use [`heap-oracle.js`](../scaffold/tools/frida/heap-oracle.js) to confirm ads
  are actually being served this session (find the resident payload in `rw-`
  memory) before trusting any "0 ads" — guards against frequency-capping.

## Step 4 — prove the transform in-process (still on the bench)

Port your filter into the CModule (or JS) and run it live: confirm ads gone,
playback healthy, **0 errors across many titles, movies AND TV**. This bench
result is the green light to productize. A permanent gadget-auto-load build can
even run a multi-day cold-start soak with no PC — a cheap stepping stone before
the `.so`.

## Driver

[`driver.py`](../scaffold/tools/frida/driver.py) is a resilient loader: it runs
a script on a daemon thread and ignores the gadget's flaky `load()` ACK (the
script runs on-device regardless). Note: `kill -9` on the driver jams the
gadget's single script slot — force-stop + relaunch the app to clear it.

## When you're done

You have: the module name, the seam function's runtime address on this build,
the payload's delivery shape (whole vs chunked), and a proven transform. Take
the address to [GHIDRA-OFFSETS.md](GHIDRA-OFFSETS.md) to turn it into a
version-robust signature for the shipping `.so`.
