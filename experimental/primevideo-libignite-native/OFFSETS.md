# Ghidra worksheet — recovering `SSL_read` and `inflate` from `libignite.so`

Fill in `jni/offsets.h` from this. Do the **armeabi-v7a** binary first (that's
the shipping target ABI).

## 0. Pull the exact library off the device

```
adb shell pm path com.amazon.amazonvideo.livingroom      # find the APK(s)
adb pull <base.apk or split_config.armeabi_v7a.apk> .
unzip -o split_config.armeabi_v7a.apk 'lib/armeabi-v7a/libignite.so'
sha256sum lib/armeabi-v7a/libignite.so   # record this in offsets.h — offsets are per-build
```

Record the SHA-256 in `offsets.h`. Signatures/offsets are only valid for the
build they were derived from; the SHA is how a future session knows whether
they still apply.

## 1. Load in Ghidra

- New project → import `libignite.so` → analyze with defaults (let it finish;
  it's large, give it time).
- If Ghidra recognizes any exported crypto symbols, great — but these two are
  stripped, so expect to identify them structurally.

## 2. Find `SSL_read` (BoringSSL, statically linked)

BoringSSL's `SSL_read` is a thin wrapper around `ssl_read_impl` /
`SSL_read_ex`. Recovery strategies, cheapest first:

1. **String cross-ref.** Search for BoringSSL assert strings near the read
   path, e.g. `"SSL_read"`, `"ssl3_read_app_data"`, `"tls_read"` or the file
   path `"ssl/ssl_lib.cc"`. XREF back to the enclosing function.
2. **Signature shape.** `SSL_read(SSL*, void* buf, int num)` → 3-arg, returns
   int, calls into a `*_read_internal` that touches the SSL record layer.
   Look for the wrapper that clamps `num` and forwards to the impl.
3. **Confirm** by checking it's called from the same TU as `SSL_write`
   (adjacent in BoringSSL sources → usually adjacent in `.text`).

Once found:
- Note the **function file offset** (Ghidra "Memory Map"/address minus image
  base) → `SSL_READ_FALLBACK_OFFSET`.
- Grab **~16–24 bytes** of the function prologue (stable, non-relocated
  instructions — avoid bytes that are branch targets / relocated) → build the
  masked signature for `SSL_READ_SIG` / `SSL_READ_MASK`.
  - In the mask, use `x` for a byte that must match and `?` for a byte that
    varies (immediate operands, addresses). `sigscan` uses this convention.

Signature format used by `sigscan` (see `sigscan.h`):
```
// bytes:  hex pairs, one per instruction byte
// mask:   same length, 'x' = must match, '?' = wildcard
static const char* SSL_READ_SIG  = "\xF0\xB5\x03\xAF\x2D\xE9...";
static const char* SSL_READ_MASK = "xxxx??xx...";
```

## 3. Find `inflate` (zlib, statically linked)

Easier than SSL_read — zlib has very recognizable fingerprints:

1. **String XREF** to `"invalid distance too far back"`,
   `"incorrect data check"`, `"1.2."` (zlib version string), or
   `"need dictionary"`. These live in `inflate.c` / `inftrees.c`; XREF to the
   state machine.
2. `inflate(z_streamp strm, int flush)` is a large function with a giant
   switch on `state->mode` (`HEAD`, `FLAGS`, `TIME`, … `TYPE`, `LEN`, …).
   The switch/jump table is a dead giveaway.
3. Its output lands in `strm->next_out`; the hook reads exactly there.

Fill `INFLATE_FALLBACK_OFFSET`, `INFLATE_SIG`, `INFLATE_MASK` the same way.

> Only actually needed if the manifest bodies come back **compressed**. Check
> first on device: if the `SSL_read` tap already sees plaintext `#EXTM3U` /
> `<MPD` text, gzip isn't in play and you can leave the inflate hook disabled
> (`ENABLE_INFLATE_HOOK 0`).

## 4. Sanity-check before trusting a signature

- Search the signature across the whole `.so` in Ghidra (or `ropper`/`radare2`
  `/x`) — it must match **exactly one** site. If it matches several, lengthen
  it or move the window past the shared prologue.
- Verify the fallback offset lands on the same function the signature finds.
  `sigscan` prefers the signature and logs if the two disagree.

## 5. Paste results into `jni/offsets.h` and rebuild

That file is the only thing you edit after this worksheet. Everything else
consumes it.
