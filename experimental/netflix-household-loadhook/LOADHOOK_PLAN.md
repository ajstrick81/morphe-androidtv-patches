# Netflix household prompt — at-rest / load-hook plan (feasibility + design)

> Status: **SCOPED, NOT STARTED.** This is a plan, not a shipped fix. The household
> ("This is my account / you're traveling") prompt is an **opt-in, default-OFF** cosmetic;
> the ads (the main event) are fully handled. Zero-effort fallback that works TODAY:
> a home-region VPN clears the misdetection server-side (it is public-IP driven).

Date: 2026-08-14. Device: Onn 4K `192.168.12.211:5555`, `com.netflix.ninja.clone`
`13.0.1 build 25028`. Shipped patch version at time of writing: **v1.26.3**.

---

## 0. TL;DR

The current household suppression (`patchHH` 4-anchor flip + `neuterMhuRenders`, opt-in
"Suppress Household Prompt") is **not broken by anchor drift** — verified on-device, all
four HH anchors still flip. It is defeated by a **timing wall**: our patches rewrite JS
*source* in the heap, Hermes lazily compiles each function from that source on first call,
and the household gate compiles/runs at **appboot, before our heap scanner wins the race**.
Re-anchoring cannot fix a race.

The fix vector is a **load-hook**: intercept the appboot JS *before Hermes compiles it* and
rewrite the household gate in the source buffer. Then timing stops mattering. This is
viable because Netflix loads the appboot bundle as **plain JS source (not HBC bytecode)**
via `nrdp.gibbon.loadScript`, and the **hash check is upstream** (on the milo download), so
a rewrite at the eval boundary is post-hash (no signature fight) and pre-compile.

**Gating unknown = one native-RE task:** locate the Hermes eval / `loadScript` entrypoint
in the stripped, statically-linked `libnetflix.so` and confirm its source buffer is
mutable in place. Everything downstream is straightforward.

---

## 1. Corrected diagnosis (evidence)

Earlier in the session this looked like anchor drift because the on-device build had NOT
actually updated (see §5, the stale-`.mpp` process bug). Once the correct probe build ran,
the ADPROBE JS-VM tap + the `PATCH HH` log lines showed **all four anchors flipping**:

```
PATCH HH: flag:isActiveMisdetectionSession 0->1
PATCH HH: flag:isNetflixHouseholdAvailable 0->1   (x2 regions)
PATCH HH: render:misdetectionAvailable(y) f->0
PATCH HH: render:netflixHouseholdAvailable(w) h->0   <- the operative household gate
```

…and the prompt was **still up**. Across runs the flips landed 0/4 one launch and 4/4
another — the signature of a **race**, not drift. This matches the long-standing memory
note `netflix-household-prompt-timing-wall`: the ownership screen renders during appboot
(~20-30s) before the gadget can reliably scan the freshly-materialised source (~20s late).

### Seam data captured today (source-resident, for the eventual edit)

The account-sharing redux reducer (Hermes heap, plaintext source):

```js
// initial state
i = { accountSharing:{ status:"pending" }, enterCount:0 }
// the reducer we key on
setAccountSharingFlags: function(e,t){ var n=t.payload;
  return (0,o.spread)(e, { accountSharing:(0,o.spread)(e.accountSharing, n) }) }
setAccountSharingMisdetectionResponse: function(e,t){ var n=t.payload; ... }   // server challenge
```

Current `killads.js` `HH_ANCHORS` (all four still match on-device):
1. `setAccountSharingFlags)({isActiveMisdetectionSession:!0`  → flip `0`→`1`
2. `setAccountSharingFlags)({isNetflixHouseholdAvailable:!0`  → flip `0`→`1`
3. `(y=null!=f&&f,`   off 12, `f`→`0`   (render: misdetectionAvailable)
4. `w=null!=h&&h)`    off 11, `h`→`0`   (render: netflixHouseholdAvailable — operative gate)

`neuterMhuRenders` still finds and nulls the `MHU_*,render:function(e){…}` screens too.
None of this is the problem — timing is.

---

## 2. Why the load-hook is the right vector (and viable)

From `experimental/netflix-native-adstrip/HANDOFF.md` (our own native recon):

- Runtime = **Hermes** (`HERMESATOM=1`), BUT appboot/milo load as **plain minified JS
  source, NOT HBC** via **`nrdp.gibbon.loadScript`** (proof: `milo_ignore_hash_errors` and
  `appboot_fail_nas_verify` appear *inside readable JS* in the heap).
- The bundle **hash check is upstream** on the milo download (`milo_ignore_hash_errors`
  bypass path exists in the JS itself), i.e. **before** `loadScript` → Hermes eval.

Therefore: **hook the eval/`loadScript` boundary, rewrite the household gate in the source
buffer before Hermes compiles it.** Post-hash (safe), pre-compile (no race). Bonus: the
same hook would make the *ad* patches deterministic instead of poll-and-race.

---

## 3. Nikflix synthesis (github.com/YidirK/Nikflix, GPL-3.0)

Nikflix is a **web** extension, but it has the most mileage on this exact prompt. What
transfers (concepts only — no code shared; our impl is an independent native appboot byte
edit):

- **Target:** it suppresses the household prompt by blocking the
  **`CLCSInterstitialPlaybackAndPostPlayback`** GraphQL op — i.e. CLCS is the delivery
  vehicle for the household screen. Our load-hook edit should neutralise the CLCS/household
  gate specifically.
- **Dual-use scoping (v2.0.3):** Nikflix had to **scope the API block to `/watch`** and use
  CSS/DOM cleanup on browse pages, because CLCS *also* drives movie-detail popups/previews.
  **Lesson for us:** whatever we neutralise at load must be narrow enough not to break
  browse-page previews/detail popups. Prefer the specific household render gate
  (`isNetflixHouseholdAvailable`) over a blanket CLCS kill.
- Credit: CLCS enforcement seam identified with reference to Nikflix (author YidirK) and
  API-block contributor Buckibarnes17. Already credited in `NOTICE` + `killads.js`.

---

## 4. Build plan

### Step 1 (gating) — find the eval/loadScript entrypoint [native RE]
In the in-process gadget, locate where `nrdp.gibbon.loadScript`'s source buffer is passed
to Hermes. Candidates: Hermes `evaluateJavaScript` / `hermes::hbc::BCProviderFromSrc::create`
/ the JSI `evaluateJavaScript`. Symbols are stripped (Hermes statically linked into
`libnetflix.so`, soname `libandroid_netflix.so`, ~84MB). Approaches:
- Scan for the `gibbon.load` / `loadScript` string xrefs; walk to the native binding.
- Breakpoint/trace on functions that receive a large ASCII buffer that contains our known
  household anchor bytes just before the household gate first executes.
- Reuse `experimental/netflix-native-adstrip/frida/dump_appboot.js` (already hooks SSL_read
  and scans markers incl. `gibbon.load`, `evaluate`, `runBytecode`) as the starting point.

### Step 2 — read-only interception probe [low-risk]
Hook the found entrypoint; when the buffer contains the household anchor (or `gibbon`),
just LOG (address, size, offset of anchor, timestamp). Success criteria:
- (a) we intercept the source **pre-compile**, and
- (b) it fires **before** the household screen mounts (compare timestamps to the MHU/OBS
  lines and the on-screen prompt).

### Step 3 — convert to a rewrite hook
In the same hook, apply the existing HH edits (the 4 anchors, length-preserving) to the
buffer in place before returning. Verify: prompt gone on cold launch, browse previews still
load (the §3 dual-use check), no boot regression, ads still clean.

### Step 4 — productionise
Fold into `killads.js` as a load-hook path (guard behind the existing "Suppress Household
Prompt" opt-in). Keep the heap-scan `patchHH`/MHU as a fallback. Re-verify per app version.

---

## 4b. RE PROGRESS — eval entrypoint FOUND (2026-08-14 session 2)

Static RE on `libnetflix.so` (armeabi-v7a, 88MB, extracted to `/tmp/nfverify/re/`) + one
on-device dynamic probe. **Gating unknown is essentially solved.**

**Engine = V8** (not Hermes — the old HANDOFF "HERMESATOM=1" was a red herring; 794 `V8`
strings, 0 hermes). So the entrypoint is V8-shaped, not `evaluateJavaScript`.

**The seam, in Netflix's own embedded JS** (`.so` file offset `0x3fd7c60`):
```js
nrdp.gibbon.loadScript = function(d,n){ return d.format="uint8array",
  nrdp.gibbon.load(d, function(o){
    (o.statusCode>=200||o.statusCode<300) && nrdp.gibbon.eval(o.data, d.url),  // <- compile boundary
    delete o.data, n&&n(o) }) }
```
`loadScript` fetches (hash-checked in `load`/milo) then calls **`nrdp.gibbon.eval(script,
fileNameOrOptions)`** — post-hash, pre-compile. Perfect hook point.

**`eval` is a native binding on `Gibbon2Bridge`.** Method descriptor (in `.data.rel.ro`) at
preferred-base **vaddr `0x3e12588`**:
```
0x3e12588: "eval"           (name @ 0x7ae9a4)
0x3e1258c: "Gibbon2Bridge"  (class @ 0x8a41cd)
0x3e12590: 0x2              (arg count)
0x3e12594: -> 0x3e12558     (arg table: "script", "fileNameOrOptions")
0x3e125a0: CALLBACK 0xecd92c   <- first callback after the descriptor = the eval method impl
```

**Dynamic confirmation (on-device, EVALPROBE):** hooked the candidate callbacks; only
`0xecd92c` (635 calls during appboot — once per module eval) and `0x137625c` (5 calls, the
`script` arg accessor) fired. **`0xecd92c` = the `eval` method callback.** Observed runtime
module base `0xb3bb1000` (ASLR — recompute each run: `Process.findModuleByName('libnetflix.so').base`).

**Addressing:** first LOAD segment has `vaddr == file offset`; exec seg `vaddr = off+0x1000`;
data segs `+0x2000` / `+0x3000`. Runtime addr = `module.base + preferred_vaddr`
(mind the ARM Thumb bit; attach fell back to `addr.or(1)` cleanly).

### What's left (small, next session)
1. **Locate the source arg offset.** `FunctionCallbackInfo` layout for this V8 build is
   unknown (the naive `length_` @ +8 read returned garbage). `EVALPROBE2` (written, NOT yet
   built/run — was in the working tree, since reverted) walks `r0`→implicit/values pointers,
   untags each candidate v8 arg, and scans object offsets for readable appboot JS to find
   exactly where the `script` string bytes live in `nrdp.gibbon.eval`'s frame.
2. **Convert the hook to a rewrite.** In the `0xecd92c` onEnter, scan the `script` buffer for
   the household anchors (the 4 in §1) and apply the same length-preserving flips **before**
   the call proceeds → V8 compiles the already-patched source → race gone. Keep it narrow
   (household only) per the Nikflix dual-use caution.
3. Verify on a real misdetected state (cold launch): prompt gone, browse previews intact,
   ads still clean, no boot regression.

### Tooling notes
- `libnetflix.so`: fully stripped (0 dynsyms). `nm`/`readelf`/`objdump`/`strings`/`r2` on host;
  `capstone`/`pyelftools` NOT installed (used raw `struct`+`re` pointer scans in python instead).
- Static-analysis scripts were ad-hoc python one-liners over the mmap'd `.so`; the pointer-table
  walk (find data-seg pointers to a string vaddr, then read the `{name,class,flags,argtable}`
  descriptor + adjacent CALLBACK entries) is the reusable technique.

## 5. Process note — the stale-`.mpp` trap (cost us a wrong diagnosis)

`semantic-release` bumps the version on every shipped fix, so the built artifact name
changes (`patches-1.26.0.mpp` → `1.26.1` → `1.26.3` …). A **hardcoded** `-p patches-X.Y.Z.mpp`
path silently goes stale and re-patches with the OLD bundled `killads.js` — the CLI still
reports "Applied", so nothing looks wrong. This made an un-updated build look like "anchor
drift" for several iterations.

**Rule:** always select the freshly-built bundle, e.g.
`MPP=$(ls -t patches/build/libs/patches-*.mpp | grep -vE 'sources|javadoc' | head -1)`,
and VERIFY the edit is actually in the artifact before installing:
`unzip -p "$MPP" netflix/native/killads.js | grep -c <your-marker>`
and/or `unzip -p <built.apk> lib/armeabi-v7a/libgadget.script.so | grep -c <marker>`.

---

## 6. Risks / honest scope

- Stripped-symbol hunt may be slow; the eval entrypoint might be inlined or reached via a
  vtable, complicating the hook.
- Source buffer might be consumed as a non-mutable/relocated copy at eval — if so, rewrite
  must happen on the exact buffer Hermes reads.
- Even pre-compile, the household decision may partly derive from the server
  `setAccountSharingMisdetectionResponse` payload; may need to neutralise that response too
  (Nikflix's actual target is the CLCS *response*, not just a local flag).
- Fallback that already works: **home-region VPN** (public-IP driven; no in-app patch can
  change your public IP).
