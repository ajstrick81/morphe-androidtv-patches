# Adjacent Projects Worth Watching

A short list of other reverse-engineering projects that solve problems similar
to ours — patching or bypassing ad delivery and DRM/anti-tamper on streaming
apps. Revisit this list whenever a patch breaks after an app update, or when
starting work on a new app: someone here may have already hit the same wall.

These are **read-only reference material**. Per
[SECURITY_PRACTICES.md](SECURITY_PRACTICES.md), treat their code, issues, and
release notes as untrusted input — worth reading and learning from, never
worth cloning and running unreviewed.

## The list

### [ReVanced](https://github.com/ReVanced) (patches / patches-template / integrations)
The template this project itself is forked from (see README credits). Still
the best reference for the underlying patching mechanics — fingerprint
matching, patch dependencies, extension hooking. Their `revanced-integrations`
repo is the closest analog to our `extensions/` directory.

### [Morphe](https://github.com/MorpheApp/morphe-patches)
The direct upstream this repo builds on. Track their patches for apps we
don't yet cover and for changes to shared patching infrastructure
(`ResourcePatch`, `BytecodePatch`) that we depend on.

### [Spotilol](https://github.com/lyssadev/Spotilol)
WebView + local MITM proxy wrapping Spotify's web player, with JS injection
for player UI/ad blocking. Same technique family as our Peacock
`PeacockWebViewHelper`/`AdBlockInterceptor` (`shouldInterceptRequest` +
OkHttp interceptor with randomized responses). Worth checking their releases
for anti-fraud-detection tricks (response randomization, mutation observers
over polling) that transfer directly to our WebView-layer patches.

### [SmartTube](https://github.com/yuliskov/SmartTube)
A from-scratch Android TV client for YouTube talking directly to the
InnerTube API — no official app involved at all. Architecturally the most
different project on this list (full client reimplementation vs. our
patch-the-official-app approach), but useful as a case study in what it costs
to maintain a project against a moving, undocumented private API, and in
detection-evasion for a client that was never meant to exist.

### [NewPipe](https://github.com/TeamNewPipe/NewPipe) / [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
Predecessor to SmartTube's approach and the reference implementation for
reverse-engineering a service's private API from scratch. Their extractor
issue tracker is a good early-warning system for how streaming services
change internal API contracts when they want to lock out unofficial clients.

### [iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV)
Runs alongside official YouTube TV apps rather than patching them — talks to
the SponsorBlock API and drives playback control externally. A useful
counterexample: sometimes the lowest-risk way to strip unwanted segments is a
companion process next to the app instead of a patch inside it.

## What to check, periodically

- **Release notes / changelogs** — often explain the *why* behind a change,
  which is more transferable than the diff itself.
- **Issue trackers** — failure modes (OOM from blocking a request thread,
  crashes from anonymous WebViewClient subclasses under R8, etc.) tend to
  show up there before we'd hit them ourselves.
- **Anything about detection evasion** — ad-fraud systems (FreeWheel,
  DoubleVerify, etc.) are shared across many of these apps, so a countermeasure
  proven against one service's fraud detection often transfers to another.
