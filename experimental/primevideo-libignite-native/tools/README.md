# Offset-recovery tooling (`inflate` / `SSL_read` in libignite.so)

How the values in [`../jni/offsets.h`](../jni/offsets.h) were recovered on
2026-07-20, and how to re-derive them when Prime Video updates (the offsets are
per-build; the SHA-256 in `offsets.h` is the guard).

## Why not just Ghidra

The intended path (`../OFFSETS.md`) is Ghidra. On this Windows box Ghidra 12.1.2's
**headless script framework (OSGi/Felix) would not initialize** (NPE in
`handleJavaVersionChange`, even under JDK 21 with cleared caches). `llvm-objdump`
and `radare2` both disassembled fine but **did not resolve** the string
references, because the target strings are reached via Thumb position-independent
`ldr rX,[pc,#k]; add rX,pc` **deltas** (the pool word holds `target-(add+4)`, so
the absolute string address appears nowhere to grep, and no tool here emulated it).

So the references were resolved directly.

## The pipeline (all pure-Python over the binary + an llvm-objdump disassembly)

1. **Disassemble** `.text` as Thumb (NDK `llvm-objdump`):
   ```
   llvm-objdump -d --triple=thumbv7-none-linux-android \
     --start-address=0x79ba80 --stop-address=0xebf00c libignite.so > disasm.txt
   ```
   (objdump annotates each `ldr rX,[pc,…]` with its literal-pool address — that's
   the one thing we need from it.)
2. **`resolve_xrefs.py`** — for every `add rX,pc`, read the pool word `W` for that
   register's most-recent `ldr [pc]`, compute `target = add_site + 4 + W`, and
   match against the anchor strings' vaddrs (`"incorrect header check"` → inflate,
   `"OpenSSL SSL_read: %s, errno %d"` → the SSL read wrapper). Emits the
   referencing instruction addresses.
3. **`find_func_entry.py`** — walks each ref back to its enclosing function entry
   (first Thumb `push {…lr}` after a terminator) and dumps the 32-byte prologue.
   Confirms inflate by its `ldr r4,[r0,#0x1c]` (strm->state) null-check prologue.
4. **`build_sigs.py`** — grows an exact-match prologue window until it is unique in
   `.text`, producing the `SIG`/`MASK` for `sigscan`.
5. **`find_offsets.py`** — the Ghidra Jython post-script (unused here because OSGi
   broke, kept for when Ghidra works: same string-xref approach, one command).

## Result (this build, SHA `0742379…d99ade`)

| function | entry (image-relative) | Thumb |
|---|---|---|
| `inflate(z_streamp,int)` | `0xd32f7a` | yes |
| SSL_read wrapper | `0xc4fe3c` | yes |

## Re-deriving after an app update

Paths in the scripts point at `C:\Users\Adam\ghidra_work\`; adjust to wherever you
extract the new `lib/armeabi-v7a/libignite.so`, update the anchor-string vaddrs
(grep `-abo` for the strings — offset == vaddr in `.rodata`), re-run steps 1–4,
and paste the new entries + SHA into `offsets.h`.
