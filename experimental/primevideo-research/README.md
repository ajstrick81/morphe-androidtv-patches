# Prime Video — native interception research snapshots (July 2026)

Reference only. **Nothing in this folder is built or shipped.**

The shipping native ad-hook (`libpvhook.so`) is compiled by CI/release from
[`../primevideo-libignite-native/jni/`](../primevideo-libignite-native/jni/) and
wired in by `patches/.../primevideo/nativehook/`. Before that design settled on
the libignite memcpy/memmove GOT/PLT import hook, two parallel research branches
explored in-process TLS/manifest interception (SSL_read / inflate hooks,
signature scanning, PRS `Remote`-item stripping). They never merged; they are
preserved here so the reasoning, offsets worksheets, frida scripts and tools
aren't lost.

| Folder | Source branch (now tag `archive/<branch>`) | What it holds |
|---|---|---|
| [`2026-07-25_interception-handoff/`](./2026-07-25_interception-handoff/) | `claude/primevideo-interception-handoff-pxhsn0` | Full snapshot of that branch's `primevideo-libignite-native/`: design README, HANDOFF, OFFSETS (Ghidra worksheet), PRODUCTIZATION plan, frida scripts, sigscan tools, prototype `jni/` + `patch/` |
| [`2026-07-23_continuation/`](./2026-07-23_continuation/) | `claude/prime-video-continuation-kaplat` | The two docs from that branch not already in the toolkit copy: its HANDOFF and `MEMORY-pull-seam.md` (the pull-seam / request-side ad-kill lead) |

The rest of the continuation branch's native work was generalised into
[`../native-adstrip-toolkit/`](../native-adstrip-toolkit/), whose
`examples/primevideo/` is that branch's full native snapshot.

Relative links inside these docs point at their original layout
(`experimental/primevideo-libignite-native/...`), so some may not resolve here.
The exact original tree is always available from the archive tag, e.g.
`git checkout archive/claude/primevideo-interception-handoff-pxhsn0`.
