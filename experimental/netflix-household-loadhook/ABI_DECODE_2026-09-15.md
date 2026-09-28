# LOAD-HOOK (#168) — the V8 arg-ABI blocker is SOLVED (offline)

Date: 2026-09-15. Binary: `com.netflix.ninja.clone` `base.apk` → `lib/armeabi-v7a/libnetflix.so`
(88 MB, ARM 32-bit, fully stripped), pulled from device `.211` this session.

This resolves the blocker that stalled sessions 2–3 (see memory
`netflix-loadhook-eval-entrypoint`): *"decode this V8 build's FunctionCallbackInfo ABI to
read the script buffer."* No device probe needed to get here — it was all offline static RE
in `r2`, which is the exact "deliberate OFFLINE pass" the memory said to do next.

## 1. Why the last 3 sessions stalled (two compounding errors)

1. **Wrong worker address.** Session 3 flat-disassembled *carved raw bytes* (section headers
   stripped) and computed the worker `bl` target as `~0x6e2dd8`. That address is in the
   **read-only** LOAD segment (vaddr 0..0xc341c0), i.e. not executable — it can't be a `bl`
   target. Flat-disasm without correct segment base = wrong relative targets. Loading the ELF
   by its program headers in `r2` and disassembling at the real vaddr gives the **correct
   worker = `0x1a59034`**.
2. **Checked the string at the wrong offset.** Session 3 read the candidate external/string
   pointer "at +0" and saw "not ASCII-JS." Offset +0 of any V8 String is the **map pointer**,
   never the chars. The chars are at **+12** (see §3).

## 2. Pinned facts (all offline-verified)

- **V8 version `13.2.152.45`** (string appears twice in `.rodata`; Chrome ~132). Maglev +
  Sparkplug present.
- **32-bit, NO pointer compression.** Proof strings: *"Signed bit of 31 bit smi register…"*
  (classic 31-bit tagged Smi) and the pointer-compression cage config is the *disabled* path
  on this arch (v7a can't do the 64-bit cage). ⇒ tagged pointers are full 32-bit, tag bit = low
  bit, `Smi = value << 1`.
- **Segment map** (`readelf -l`): exec segment `vaddr = fileoff + 0x1000`
  (`0xc351c0 = 0xc341c0 + 0x1000`). So `fileoff = vaddr − 0x1000` for any code vaddr. Data
  segs are `+0x2000` / `+0x3000`.

## 3. The eval seam, disassembled correctly

`nrdp.gibbon.eval` (Gibbon2Bridge) native binding. Static method-descriptor work from
session 2 still holds; the **callback thunk = `0x137625c`** (the genuine v8 FunctionCallback,
~5 appboot calls). Disassembled at the real vaddr:

```
0x137625c  add r2,r2,r3            ; 2-entry API-callback trampoline
0x1376260  b   0x137626c
0x137626c  push {r4,r5,r6,r7,fp,lr}
           ...
0x1376284  mov r4, r1             ; r4 = r1  (the FunctionCallbackInfo&)
0x137628c  bl  0x1376334          ; init out+4
0x1376290  ldr r0,[r7,0x18]       ; check out[0x18] (error slot)
0x1376298  bne ...
0x137629c  mov r0, r6            ; r0 = ctx
0x13762a0  mov r1, r4            ; r1 = info
0x13762a8  bl  0x1a59034          ; <<< REAL WORKER (Gibbon2Bridge::eval C++ impl)
```

Worker `0x1a59034` is called as `worker(r0=ctx, r1=&FunctionCallbackInfo, r2=out+4)`. Its
first instruction is `ldr r0,[r1]` — reading **`[info+0]` = `implicit_args_`**, which matches
the *public, stable* `v8::FunctionCallbackInfo<T>` layout on 32-bit:

```
+0  internal::Address* implicit_args_
+4  internal::Address* values_        // points at ARG0, ascending
+8  intptr_t            length_        // argument count
```

So **arg0 (the script string) tagged pointer = `*( *(info + 4) + 0 )` = `[[r1+4]]`**.

## 4. Reading the source once we have the tagged String pointer

`s = [[r1+4]]` is a tagged `v8::internal::String*` (low bit set → clear it: `sp = s & ~1`).
Classic 32-bit String header (no compression, unchanged for years):

```
sp+0   Map*            (instance_type inside the Map distinguishes the string kind)
sp+4   raw_hash_field
sp+8   length          (in CHARACTERS)
sp+12  char data       <-- SeqOneByteString = Latin1 bytes; SeqTwoByteString = UTF-16
```

Minified appboot JS is Latin1 ⇒ almost certainly **SeqOneByteString**, chars readable as ASCII
at `sp+12` for `length` bytes. (If V8 externalized it, `sp+12` is instead a resource pointer;
the probe below reports enough to tell which — but internal-seq is the overwhelmingly likely
case for freshly-`eval`'d source.)

## 5. Ready-to-run READ-ONLY probe (safe — direct reads only, NO scanSync)

Drop behind an `EVALPROBE_ENABLED` flag in `killads.js`. Rule from memory: `Memory.scanSync`
inside the eval callback HANGS appboot; **direct reads are safe**. This probe does only direct
reads.

```js
// EVALPROBE (read-only) — verify the load-hook ABI on-device. Fires ~5x at appboot.
var EVALPROBE_ENABLED = true;
(function(){
  if(!EVALPROBE_ENABLED) return;
  var mod = Process.findModuleByName('libnetflix.so') || Process.findModuleByName('libandroid_netflix.so');
  if(!mod){ L('EVALPROBE: no libnetflix module'); return; }
  var cb = mod.base.add(0x137625c);            // callback thunk vaddr (ASLR-relative)
  var n = 0;
  Interceptor.attach(cb.or(1), {              // .or(1): thumb-bit tolerance (attach handled it before)
    onEnter: function(a){
      if(n++ > 12) return;                      // cap log volume
      try {
        var info = this.context.r1;             // &FunctionCallbackInfo
        var values = info.add(4).readPointer(); // values_ -> arg0 slot
        var len    = info.add(8).readInt();     // length_ (argc)
        var s   = values.readPointer();          // arg0 tagged String*
        var sp  = s.and(ptr(1).not());           // clear tag bit
        var slen = sp.add(8).readU32();          // char length
        var head = '';
        try { head = sp.add(12).readCString(80); } catch(e){ head = '<+12 read failed>'; }
        L('EVALPROBE#'+n+' argc='+len+' Str@'+sp+' len='+slen+' head='+JSON.stringify(head));
      } catch(e){ L('EVALPROBE#'+n+' ERR '+e); }
    }
  });
  L('EVALPROBE armed @'+cb);
})();
```

**Success criterion:** at least one `EVALPROBE#k head="…"` line shows minified JS, and one of
them contains a household/ad anchor (`getAdMetadata`, `setAccountSharingFlags`, `adBreakHydrator`).
That proves: (a) arg0 ABI correct, (b) source is readable pre-compile, (c) which of the ~5
buffers to gate the rewrite to.

## 6. Then: convert to a rewrite (still no scanSync)

In the same `onEnter`, once `head`/a bounded `sp.add(12).readByteArray(slen)` window contains
our anchor, `Memory.protect` + `writeByteArray` the **length-preserving** edits directly on the
`sp+12` char data **before** the call proceeds → V8 compiles patched source. Gate to the
buffer(s) carrying `getAdMetadata` / `setAccountSharingFlags` so we don't touch every eval.
This is race-free (pre-compile) and drift-resistant (source-level, not byte-offset) — the whole
point of #168.

## 7. LIVE ON-DEVICE VERIFICATION (2026-09-15, `.211` clone) — seam PROVEN + a new wall

Built the probe into `killads.js` behind `EVALPROBE_ENABLED`, flashed the clone, iterated:

- **Attach:** hooking the callback thunk head `0x137625c` fails — Frida can't relocate the
  2-entry trampoline (`add r2,r2,r3; b`) → *"unable to intercept function."* Hook the clean
  **prologue at `0x137626c`** instead (ARM, no `.or(1)`). Works, ~fires per eval.
- **String layout CONFIRMED LIVE:** an eval arg came back as
  `STR@12 @0x4a180008 len=203041 head="!function(){\"use strict\"\nconst e=nrdsocket…"` — a
  real v8 one-byte String with **chars at +12, char-length at +8**, exactly as decoded. The
  `+12` fix (vs session-3's `+0`) is the whole unlock. ✅
- **Reg path:** at the prologue the source String is reachable from **`r2`** via a short
  pointer walk (not the public `values_@+4` — that was the *worker's* ABI, and `0x1a59034`
  turned out to be a shared helper, not eval-only). The robust probe just scans r0/r1/r2 words
  + one hop for a `+12`-ASCII String ≥2000 chars and greps it for anchors — no fixed offset
  needed, no scanSync.
- **⛔ NEW WALL — warm-start bypasses `eval`.** On a normal (warm) launch only ~3–13 evals
  fire, all **helper/transport modules** (nrdsocket, an MSL `headerdata` blob, a 140K bundle) —
  **never** `getAdMetadata` / `setAccountSharingFlags`. Meanwhile `ANCHOR MAP` shows
  `A=0 A2=0 MASTER=0 …` — the heap-scan patches find nothing either, because on this de-trusted
  unit the app is **stuck at the household gate** so the player/ads bundle never loads, AND the
  household bundle is **warm-restored from the compiled `real_*.bin` snapshots** (see
  `netflix-household-prompt-timing-wall` §at-rest) rather than re-parsed from source. **The
  load-hook can only intercept a bundle on a COLD start** (or the first time Netflix pushes a
  new appboot). This is a real constraint #168 did not account for.
- **Consequences / next levers:**
  1. **Force a cold start** to catch the household + appboot bundles: `pm clear
     com.netflix.ninja.clone` (login survives via shared `com.netflix.tokenmanager`), then
     `adb reboot` (tamper self-kill after clear), relaunch → all bundles re-`eval` → probe
     should log `setAccountSharingFlags`/`isNetflixHouseholdAvailable`. No root/run-as/su on
     this production Onn, so deleting just the `real_*.bin` snapshots isn't available — `pm
     clear` is the only cold trigger from shell.
  2. **getAdMetadata is the PLAYER bundle** — it evals on playback start, which is
     household-blocked here. Need past-the-gate (VPN / one-time verify with a household-OFF
     build) to catch it. For the ad-kill half of #168, a cold start + an actual title play is
     required.
  3. **Design implication:** even a perfect load-hook only helps launches that re-parse source.
     For warm launches the compiled bytecode is already in the heap → the existing heap-scan
     patch is still needed there. So the load-hook is a *complement* (wins cold-start races +
     drift-resistance when source is fresh), **not** a full replacement for the scan path.
     Re-scope #168 accordingly, or pair it with a forced-cold tactic.

## 8. COLD-START test + the honest negative (2026-09-15, later)

Forced true cold starts via `pm clear com.netflix.ninja.clone` + `adb reboot` (login DID survive
via shared tokenmanager) and iterated the probe hard:
- Hooked BOTH eval entries: prologue `0x137626c` AND the second trampoline path `0x13763b0`.
- Detected v8 Strings (`+12`) AND raw uint8array/backing-store bytes (`+0`), reading the FULL
  declared length up to 16 MB (the appboot bundle is multi-MB, so anchors could sit past 1.5 MB).
- **Result: still NO `getAdMetadata` / `setAccountSharingFlags` / `isNetflixHouseholdAvailable`
  in ANY eval'd buffer**, cold or warm. Only ~2–8 eval calls fire through these entries, carrying
  helper/transport modules (nrdsocket 203K, a 140K bundle, MSL blobs) and some large printable
  heap blobs (one ~15.8 MB) — none containing our anchors.
- ⚠️ **Reading up to 16 MB inside the eval callback STALLS appboot (black screen)** — same
  thread-blocking class as the scanSync hang. Backed off / reverted.

**Interpretation.** On this build (Netflix ninja 13.0.1/25028, V8 13.2), the appboot/household/
player bundles that carry our anchors do **not** reach the JS engine as a plain
`nrdp.gibbon.eval(String|uint8array)` we can intercept at this boundary. They are almost
certainly **restored as compiled bytecode from the `real_*.bin` warm-snapshots** (and the player/
ad bundle only materialises on playback, which is household-gated on this de-trusted unit). The
heap-scan patch finds `getAdMetadata` *source* only once playback loads it — i.e. the source is
lazily materialised from a snapshot-backed representation, not fed through this eval call.

**Verdict for #168.** The load-hook seam is real and the ABI is fully solved (§7 proves the
`+12` String read live), BUT `nrdp.gibbon.eval` is **not the compile boundary for the bundles we
need to transform** on this build. A source-rewrite load-hook here cannot, by itself, replace the
heap-scan path. Viable directions if #168 is pursued further:
  1. **Find the snapshot/bytecode restore boundary** (V8 `Snapshot::Deserialize` / the code-cache
     load), or the lazy-compile entry that turns snapshot data back into `getAdMetadata`'s source
     — a much deeper native RE task than the eval hook.
  2. **Invalidate the warm snapshot** so bundles re-parse from source through eval (delete
     `files/real_*.bin` — needs root, unavailable on this production Onn), then the eval hook WOULD
     catch them (proven mechanism). Not a shippable user path.
  3. **Accept the load-hook as scoped to genuinely-eval'd modules** and keep the heap-scan +
     wildcard path (v1.36/1.37) as the shipping mechanism for the ad-kill; treat #168 as
     "explored, seam proven, but not the right boundary for our anchors on 13.x."

The ABI/seam work (§1–7) is durable and reusable if a future Netflix build delivers these bundles
through eval, or once the snapshot-restore boundary is mapped.

## 9. Ruling out an alternate compile entrypoint — `eval` is the only one (2026-09-15)

Before committing to deep snapshot RE, enumerated the whole **`Gibbon2Bridge` native method
table** offline (`enum_bridge.py` → `gibbon2bridge-methods.txt`; walks the descriptor structs
{name@+0, class@+4, nargs@+8, argtable@+0xc} by scanning the RW segment for pointers to the
`Gibbon2Bridge` class string). 80 descriptors, 32 unique methods. Relevant surface:

```
eval            addInjectJS      init          bootFailure     showErrorPage
sync            _hash            addFont       setSyncCallback  garbageCollect
getHeapSize     getFrameInfo     ... (rendering/input/font/event methods)
```

**There is NO `loadScript` / `compile` / `run` / `evaluate` / `loadBytecode` / `import` native
method.** The JS-level `nrdp.gibbon.loadScript` is defined *in JS* and calls `nrdp.gibbon.eval`.
So **`eval` is the single source-compile entry** — we were not missing an alternate binding.
(Note: the `cb@desc+0x18` column in the dump is a shared thunk, not the real per-method callback
— the real `eval` callback is the separately-confirmed `0x137625c`; treat that column as a rough
tag only.)

### Conclusion → the actual mechanism, and the real remaining task
Putting §7–§9 together: `eval` fires only ~2–8× per launch and carries just helper modules, cold
**or** warm — even after a `pm clear` wiped the `real_*.bin` snapshots. The only consistent
explanation is that Netflix ships/keeps a **V8 code cache (compiled bytecode)** for the big
bundles and `eval` **consumes the cached bytecode** (`ScriptCompiler::kConsumeCodeCache`) instead
of parsing source — so the source string is never materialised through the hook for
`getAdMetadata` / `setAccountSharingFlags`. (Consistent with the household memo's finding that the
warm snapshots restore *compiled* heap, and with the heap-scan patch only finding `getAdMetadata`
*source* once playback lazily reifies it.)

**Therefore the load-hook, to work for our anchors, must operate at the V8 compile layer, not the
Gibbon `eval` binding.** Two concrete (deep) next tasks:
  1. **Force source-parse:** find where `eval`'s C++ builds the `ScriptCompiler::Source` /
     `CompileOptions` and neutralise the `kConsumeCodeCache` path (or null the cached-data arg) so
     V8 re-parses from source → then the existing eval-source rewrite lands. Locate via the
     `v8::ScriptCompiler` compile call inside the eval worker `0x1a59034` chain.
  2. **Or intercept the deserialize/compile boundary itself** (`Snapshot`/`CodeSerializer::
     Deserialize` / `Compiler::GetSharedFunctionInfoForScript`) — but V8 source-path strings are
     stripped in this build, so this is a hard, symbol-less hunt.

Both are materially deeper than the eval hook. Net: #168's seam is proven, `eval` is the wrong
boundary on 13.x, and the tractable next step is task 1 (kill the code-cache consume so source is
parsed) — a focused follow-up, not this session.

## 10. ⚠️ CORRECTION — code-cache theory (§8/§9) was WRONG; large bundles DO eval as source

A follow-up light-probe pass (bounded 256 KB windowed reads, no black-screen) caught what the
earlier runs missed: **a ~8 MB source String IS `eval`'d at appboot** (`EVALPROBE-DEEP
len=7956073 scanned=7958185`). Window-scanned the *entire* 8 MB body — clean source, no truncation.
Earlier misses were artifacts, not evidence of a code cache:
- the first probe cut off at eval #3 (the big bundle evals at #11–13, ~30–45 s in);
- the 16 MB-read version black-screened (stalled the eval thread) *before* it could log;
- the 512 KB-cap version saw it but only searched the first 512 KB → "capped".

So **the §8/§9 "eval consumes cached bytecode (`kConsumeCodeCache`)" conclusion is retracted.**
Netflix's big appboot bundles come through `nrdp.gibbon.eval` as **plain multi-MB source** — the
load-hook **can** intercept and rewrite them. `ScriptCompiler::CreateCodeCache` strings exist in
the binary but are not gating our bundles.

### The actual remaining fact: our anchors are in ON-DEMAND modules we couldn't trigger
The full 8 MB appboot bundle scan found **none** of `setAccountSharingFlags` /
`isNetflixHouseholdAvailable` / `isActiveMisdetectionSession` / `getAdMetadata` / `adBreakHydrator`.
Yet the heap-scan patch finds those literals in memory once the relevant feature runs. Conclusion:
they live in **separately, lazily-loaded modules** fetched *after* appboot via `loadScript → eval`:
- `getAdMetadata` → the **player/ads bundle**, loaded on **playback start**.
- `setAccountSharingFlags`/MHU → the **household bundle**, loaded when an **active household
  challenge** mounts.

Both are `loadScript → eval` on-demand loads, so the hook **should** catch them the same way it
caught the 8 MB bundle — we simply never triggered either module on this unit:
- playback is **household-gated** (de-trusted `.211`), and
- no **active server-side challenge** was in effect during the probe (challenges are public-IP /
  server driven and can't be manufactured on demand).

### Revised status of #168 (supersedes §8/§9)
- ✅ ABI solved + `+12` String read proven live (§7).
- ✅ Large bundles `eval` as source → **load-hook is viable** (code-cache is NOT the blocker).
- ⬜ The remaining validation is a **device-state** problem, not an RE problem: to catch
  `getAdMetadata` eval'ing, get **past the household gate** (home VPN or a one-time household
  verify with a household-OFF build) so a title plays, then re-run the probe on the player bundle.
  To catch the household module, need an **active challenge** (e.g. VPN region change to provoke
  one).
- ❌ The deep `kConsumeCodeCache` RE (§9 task 1) is **NOT needed** — that premise was wrong.

Recommended next session: unblock playback (VPN/verify), re-arm the windowed probe, start a title,
and confirm the player bundle evals with `getAdMetadata` present → then wire the length-preserving
source rewrite (§6) on that buffer. That closes the ad-kill half of #168 race-free + drift-proof.

### Open checks for the rewrite step
- Confirm the `sp+12` buffer is the *same* memory V8 parses (not a copied/relocated buffer at
  compile). If `eval` internalizes/copies, rewrite must land on the exact buffer read by the
  compiler — verify by re-reading post-write, and by the on-screen effect.
- Keep the existing scan+wildcard `patchMASTER`/`patchHH` path as the flagged fallback until
  the load-hook is proven across ≥2 Netflix versions (issue step 5).
- Anchor bytes must be length-preserving in the source string (same technique as today).
