# Recovering offsets & building runtime signatures

You have a seam function's runtime address from the Frida bench. Now turn it
into something the shipping `.so` can re-find on every launch, on this build and
(ideally) the next. Output goes into `offsets.h` (template in
`../scaffold/jni/offsets.h.template`).

## 1. Pull the exact library off the device

```
adb shell pm path <app.package.name>            # find the APK / splits
adb pull <base.apk or split_config.<abi>.apk> .
unzip -o split_config.<abi>.apk 'lib/<abi>/<TARGET_SONAME>'
sha256sum lib/<abi>/<TARGET_SONAME>             # record in offsets.h — per-build guard
```

Do the **shipping ABI first** (often `armeabi-v7a`). Signatures are per-ABI:
an armv7 signature will not match an arm64 binary.

## 2. Identify the seam function in Ghidra

Import the `.so`, analyze with defaults (large libs take a while). If the seam
is a known library function that's stripped, identify it structurally:

- **String cross-reference** to a nearby assert/log string is the cheapest
  anchor. E.g. BoringSSL's read path references `"OpenSSL SSL_read: %s, errno %d"`
  / `"ssl/ssl_lib.cc"`; zlib's `inflate` references `"incorrect header check"` /
  `"invalid distance too far back"` / the `"1.2.x"` version string. XREF back to
  the enclosing function.
- **Signature shape.** Match the arg count / return / call pattern (e.g.
  `inflate` is a huge function with a giant switch on `state->mode`; a
  `*_read(SSL*, void*, int)` clamps `num` and forwards to an impl).
- If the bench gave you `libfoo.so+0xNNNN`, just go to that offset directly and
  confirm the function.

## 3. If headless Ghidra won't cooperate — the pure-Python fallback

On the reference project Ghidra's headless OSGi framework wouldn't initialize.
The `tools/` scripts resolve string XREFs **without** Ghidra, straight over an
`llvm-objdump` disassembly. This matters for Thumb: PC-relative `ldr rX,[pc,#k];
add rX,pc` deltas mean the absolute string address appears nowhere to grep. The
pipeline:

1. `llvm-objdump -d --triple=thumbv7-none-linux-android <lib>.so > disasm.txt`
2. `resolve_xrefs.py` — for each `add rX,pc`, read the literal-pool word, compute
   `target = add_site + 4 + W`, match against your anchor strings' vaddrs.
3. `find_func_entry.py` — walk each ref back to its function entry (first Thumb
   `push {…lr}` after a terminator); dump the prologue.
4. `build_sigs.py` — grow the prologue window until it's unique in `.text`.

(`find_offsets.py` is the equivalent Ghidra Jython script for when Ghidra works.)

## 4. Build the signature + fallback

For the resolved function:
- **Fallback offset** = image-relative entry address → `SEAM_FALLBACK_OFFSET`.
- **Signature** = ~16–24 bytes of the prologue. Use stable, non-relocated
  instruction bytes; mask out immediate operands / addresses. Convention
  (`sigscan`): `SIG` is the byte array, `MASK` is same length, `'x'` = must
  match, `'?'` = wildcard.

**Verify uniqueness:** the signature must match **exactly one** site in `.text`
(`ropper`/`radare2` `/x`, or Ghidra search). If it matches several, lengthen it
or move the window past a shared prologue. Confirm the fallback offset lands on
the same function the signature finds.

## 5. Thumb note

If the target is Thumb, the callable address needs bit0 set (`addr |= 1`) for
the hook engine to hook in Thumb mode, even though the signature matches at the
even address. `hooks.cpp` in the example does this.

## 6. Paste into `offsets.h` and rebuild

That file is the only thing you edit after this. Everything else consumes it.
Re-derive (new SHA, new offsets, re-run the signature check) after any app
update — the content-keyed marker logic stays; the addresses don't.
