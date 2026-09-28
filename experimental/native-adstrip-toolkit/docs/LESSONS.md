# Hard-won lessons

Each of these cost real time on the Prime Video project. Read before you
rediscover them.

## Interception

**The seam is not where you assume.** The scaffold was built to hook
`inflate` / `SSL_read`; the payload turned out to be decrypted *inside* the
app's statically-linked BoringSSL and only visible at `memcpy`. Never write the
transform against a guessed seam — find it empirically first
([FINDING-THE-SEAM.md](FINDING-THE-SEAM.md)).

**A silent "0 ads found" is a lie until proven otherwise.** The Frida
`Module.getExportByName` API was dead in the bench's Frida build — it silently
no-op'd the hooks and returned zero hits. Always confirm a hook *fires* on real
traffic (log every invocation during bring-up) before trusting a zero. The
host-test analog: a test that passes without executing the code. **Mutation-test
everything** (see below).

**Pull vs push changes the whole transform.**
- A **pull** seam (`SSL_read`/`inflate`) lets you withhold and re-serve, so you
  can reassemble the whole body and *shrink* it.
- A **push** seam (`memcpy`) is one-shot: you can only edit the source buffer in
  place, so edits must be **same-length** (blank with spaces, keep JSON valid)
  and can only touch a payload that is **complete in that one copy**.

## Safety

**Never edit a truncated payload.** Large bodies arrive in chunks. Blanking a
truncated chunk smashes a partial content element → **black screen**. Parse the
whole unit; if the closing bracket/quote isn't there, leave the buffer
byte-for-byte untouched. On the Prime Video bench this meant safely skipping
hundreds of truncated buffers per session and acting only on the complete one.

**Movies and TV are different.** Movies fit one complete copy and stripped 100%;
TV playlists are denser/larger, arrive only as truncated chunks (or exceed a
size gate), and the same transform skipped them all. The fix wasn't a new
transform — it was **reassembling the whole body** (what an off-device proxy did
for free). Always test the large/dense case explicitly.

**Shrinking a body breaks HTTP framing.** If your transform shrinks the
response, the app's HTTP layer still expects the original `Content-Length` and
will hang waiting for bytes you'll never send. Rewrite `Content-Length` to the
served length, or switch the response to chunked transfer-encoding. (Same-length
blanking sidesteps this — another reason the push-seam transform blanks.)

**Watch for ad frequency-capping while you test.** After a couple hours of
repeated launches on one account, the server may stop serving ads regardless of
your hook. Confirm ads are *actually being served* (a heap oracle, or just a
fresh look) before concluding "ads gone."

## Tooling

**Dobby was a dead end; use ShadowHook.** `bytedance/android-inline-hook`
(ShadowHook) is the recommended inline-hook engine. Don't sink time into Dobby.

**Signatures are per-build; keep a fallback.** A prologue signature that's too
short matches many sites; too long and an app update breaks it. Keep BOTH a
unique masked signature AND a fallback file offset in `offsets.h`, and record
the `.so` SHA-256 so a later session knows whether they still apply. Prefer the
signature at runtime; log if it disagrees with the fallback.

**Content-keyed markers survive updates; addresses don't.** Key your filter on
stable content (`type:"Remote"`, `/iad_`, a manifest tag), not on byte offsets.
The offsets get re-derived per build; the marker logic is version-robust.

**`extractNativeLibs="true"` for bring-up.** If the app ships
`extractNativeLibs="false"`, the OS mmaps `.so`s straight from the APK and
requires them page-aligned + uncompressed; an injected-then-repackaged `.so`
fails with `UnsatisfiedLinkError`. Forcing extraction unpacks libs at install
and sidesteps alignment entirely. The bundle patch does this.

**Frida CModule gotcha:** the C source lives inside a JS template literal, so
there can be **no backticks** in the C — not even in comments.
