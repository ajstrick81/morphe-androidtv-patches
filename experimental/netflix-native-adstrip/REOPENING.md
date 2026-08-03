# Netflix ad-strip — REOPENED (walls-are-doors methodology)

**Supersedes the "research-parked / closed" verdict in `PORTABILITY-ASSESSMENT.md` §8.**
That verdict was correct about the *network* (pure same-host SSAI, nothing to block) and about
the *at-rest* layers (TLS, MSL, appboot signature). It was wrong about the conclusion, because it
stopped at "appboot is signed" and read a **load-time door** as a **runtime wall**.

This document reopens Netflix on the same principle that cracked Prime Video:

> Ads are changeable. Netflix cannot play the same commercials to everyone all day, so the ad
> schedule must be **asked for and assembled at runtime**, in a form **the client's own code can
> read** — and the client necessarily carries a **"no ads" path**, because it runs it constantly.

---

## 1. The observation the "closed" verdict walked past

The prior assessment's own data contains the proof that the wall is a door:

- **§3e, measured:** *"~7 movies played with no pre-roll, then one did."* The **same signed
  appboot** ran its **own graceful no-ad path six times out of seven.** The client-side empty-break
  branch is not hypothetical — it executes several times an hour, on your device, with the retail
  signature fully intact.
- **§3b/§3d, measured:** the ad-break logic is **plaintext JavaScript** running in an embedded
  **Hermes** engine inside `libnetflix.so`. The manifest is decrypted (past MSL) into a **JS
  string/object in the Hermes heap**, then parsed by JS.

Put those together and the shape is *identical* to Prime Video: a JS player, ad logic in plaintext
at runtime, with a built-in no-ad path. The only difference is the engine name (Hermes vs QuickJS)
and the at-rest protection (signature vs hash-pin). Neither at-rest protection survives execution —
**that is the whole methodology.**

## 2. Why "signed" ≠ "sealed"

The appboot RSA/ECDSA signature (pubkey baked into `libnetflix.so`) is a **verify-on-load** gate.
Its job is to reject a *modified bundle at rest*. It has nothing to say about the bundle **after**
it has been verified, decompressed, and handed to Hermes as executing source/bytecode:

- Signature check happens **once**, before execution.
- After it passes, the player JS — including the ad-break resolver **and its own empty-break
  branch** — lives as plaintext in the **Hermes heap**.
- We never modify the signed bytes at rest. We operate on the **runtime heap**, downstream of the
  check, exactly as the Prime Video strip patched `ignitionx` live in QuickJS RAM (that bundle was
  *hash-pinned* — same lesson: at-rest integrity is irrelevant once the plaintext is executing).

The signature is a door the app must walk through on every launch. We let it walk through, then
operate on the far side.

## 3. Three seams, mapped from the Prime Video win

Netflix's post-MSL Hermes runtime exposes the same three surgical seams, in the same order of
precision. All three live **after** MSL-decrypt and **after** the appboot signature check.

| # | Prime Video (proven) | Netflix analogue | Signature touched? |
|---|---|---|---|
| **A** | Strip `type:"Remote"` items from PRS payload | **Scrub ad-break markers from the decrypted manifest object** before appboot's ad resolver reads it | **No** — it's *data* in the heap, never the signed code |
| **B** | Path-1 count-gate `0===t.length` → always-empty | **Live-edit appboot's ad-break resolver in the Hermes heap** to force its own empty-break return | No — edits heap plaintext *after* load-time verify |
| **C** | Path-2 regolith response-blanking → designed empty path | **Feed appboot's ad resolver an empty break list** so it runs the no-ad branch it already runs 6/7 movies | No — drives the app's own designed path |

**Seam A is the cleanest and the recommended de-risk lead**: because it only rewrites the *manifest
data* the player consumes — not the player — it is completely orthogonal to the signature, to milo's
hash, and to MSL (MSL already decrypted it for us; we read it in the clear in the heap). It is the
Netflix expression of "the app reads the ad schedule before it plays it, in a form its own code can
read."

## 4. Where the seam physically is (recon targets, bench-executable)

The plaintext manifest transits: `native OpenSSL SSL_read (MSL ciphertext) → MSL decrypt in Hermes
JS → manifest is a JS string/object in the Hermes heap → appboot ad-break resolver`. Seam A/B/C all
sit in the last two arrows. Recon to locate them, all doable on the Onn with Frida (the binary/device
are on the user's bench; the cloud session cannot reach them):

1. **Confirm the no-ad branch exists in appboot** (schema-level, no device): obtain the **appboot UI
   bundle** the same direct-download way milo was obtained (Gibbon loads it from
   `appboot.netflix.com`; it is also disk-cached on device). Static-grep it for the ad-break
   vocabulary the Prime Video bundle used its no-ad path on — `adBreak`, `interstitial`, `cuePoint`,
   `breakStart`/`breakDuration`, quartile/beacon fields — and for the **empty-guard** pattern
   (`length === 0`, `return []`, an `if (!breaks.length)` early-out). Finding that guard *names the
   seam-B edit* the same way `0===t.length` did for Prime Video.
2. **Fingerprint the manifest string in the heap** (device, Frida): Hermes exposes string/GC
   primitives. Hook the JSON parse / manifest-ingest path and dump the object that carries the
   ad-break array during a **known pre-roll session** (the §3e frequency cap tells you how to force
   one: burn ~7 titles, then capture). This is the Netflix analogue of the Prime Video `memcpy`
   import hook that surfaced the PRS payload.
3. **A/B the heap object** ad vs ad-free: capture the same code path on an ad-free title (6/7 of them)
   and on the pre-roll title. The diff **is** the ad-break schema — the field(s) seam A must scrub
   and the branch seam B/C must force.

`testing/scripts/analyze_pcap.py --vs` already does A/B at the *network* layer; step 3 is the same
idea moved to the *heap* layer, which is where Netflix's difference actually lives (network is
same-host SSAI — identical bytes — so the difference is only visible in-process, confirming this is
the right altitude).

## 5. What is genuinely different from Prime Video (honest deltas)

Not pretending these are free:

- **Hermes vs QuickJS.** QuickJS parses plaintext *source*; Hermes usually executes *bytecode* (HBC).
  If appboot ships as HBC, seam **B** (edit the JS source in the heap) needs a Hermes-bytecode-aware
  edit, not a UTF-8 string patch. **This is exactly why seam A leads** — the *manifest data* is a
  normal JS string/object regardless of whether the *code* is HBC, so A sidesteps the HBC question
  entirely. Determine appboot's on-heap form during recon step 1/2.
- **Reaching the Hermes heap from a native hook.** The toolkit's inline `memcpy`/`SSL_read` seam
  gave Prime Video plaintext directly; here the native hook has to land on the **Hermes ingest of the
  manifest** (the boundary where the decrypted MSL payload becomes a JS value). That boundary is a
  native call — locatable with the Frida bench, then Ghidra — it is one seam deeper than Prime Video,
  not a different kind of problem.
- **No power-of-two/zlib-window hazard**, but **do** preserve Hermes string invariants (length,
  encoding, GC) on any in-place edit — the analogue of Prime Video's same-length-rewrite rule.

None of these is the signature. The signature is settled: it's a load-time door and we operate past it.

## 6. De-risk order (METHODOLOGY §1 — stop if stripping breaks playback)

1. **Get the appboot bundle, static-analyze for the ad-break schema + its empty-guard.** (No device.
   Highest information per unit effort. Confirms seam B/C exist as code.)
2. **Frida-dump the decrypted manifest object from the Hermes heap during a forced pre-roll.**
   Confirms the *data* seam A operates on, and gives the field to scrub.
3. **A/B ad vs ad-free heap object** → lock the exact scrub.
4. **Prove seam A on device**: scrub the ad-break field in the heap, observe whether the break is
   **skipped/blanked** (win) or the **UI desyncs while the ad still plays** (SSAI stitched the video
   irreversibly — the one outcome that would re-close it). This is the single load-bearing empirical
   question; everything before it is cheap.
5. Only if A desyncs, fall back to B/C (force the player's no-ad control path so it never *requests*
   the stitched ad segments).

## 7. What this session can do without the bench

The binary and device are on the user's Windows/Onn side. From the cloud I can, on request:

- **Static-analyze the appboot bundle** for the ad-break schema and empty-guard (step 1) — the exact
  artifact that unblocked Prime Video's understanding. *Upload `appboot`/the UI JS as milo was
  uploaded.*
- Write the **Frida recon scripts** (Hermes manifest-ingest hook, heap-object dumper, ad/ad-free A/B
  harness) for steps 2–3, mirroring `scaffold/tools/frida/find-copy-seam.js`.
- Draft the **seam-A manifest-scrub transform** against the schema once step 1/2 hands it over.

## 8. Verdict (reopened)

- ❌ **"Not strippable" is withdrawn.** It generalized a *load-time* signature and a *network* SSAI
  result into a *runtime* impossibility. Both at-rest layers are irrelevant to a heap-level strip.
- ✅ **Netflix fits the methodology exactly:** ads vary per-viewer → assembled at runtime → the
  decrypted manifest is a plaintext JS object the player's own code reads → the player carries a
  **no-ad path it demonstrably runs 6 of 7 titles.**
- ✅ **Lead seam = A (manifest-scrub in the Hermes heap):** orthogonal to the signature, to milo's
  hash, and to MSL. The one real risk is SSAI irreversibility (step 4), which is empirical and cheap
  to settle — not the architectural wall the prior verdict assumed.
- 🔑 **The lock was only ever on the outside.** Netflix runs the player, decrypts the manifest, and
  executes its no-ad path for us on every launch. It is carrying the key every time it plays.
