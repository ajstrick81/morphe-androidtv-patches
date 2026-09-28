# `esm` — fast smali/APK search

Two indexes, each best at its job, wired through `esm.sh`:

| Job | Engine | After install |
|---|---|---|
| Find a file/class by **name** | **es.exe** ([voidtools Everything](https://www.voidtools.com/)) | instant |
| Search **contents / code** (method bodies, strings) | **csearch / cindex** ([Google Codesearch](https://github.com/google/codesearch)) | instant, after a one-time index |
| Fallback (no index) | ripgrep | always works, slow on big trees |

## Why

A tree-wide content grep over a decompiled app is the workflow's slowest step.
Measured on **HBO Max 7.12.0.68 (33,601 smali files)**:

| Operation | Old way (measured) |
|---|---|
| Locate a class by name (`find`) | ~1.0 s |
| List all `*.smali` (`find`) | 0.6 s |
| **Tree-wide content search (`rg`)** | **~165 s** — over our 120 s tool budget |

`es.exe` makes file location instant, but it indexes **names, not contents** — so
the real bottleneck (content search) is solved by a trigram **code** index:
`cindex` reads the tree once (~decompile speed), then every `esm code` query is
milliseconds. We decompile an app once and search it dozens of times a session,
so the index pays for itself immediately.

## Install (binaries are installed by a human, not by an agent)

1. **Everything + es.exe** — from <https://www.voidtools.com/downloads/>. Install
   Everything (app + service; it indexes NTFS in seconds and stays live), and put
   `es.exe` ("ES - Everything command-line interface") at `E:\Tools\es.exe`.
2. **Google Codesearch** — `cindex` + `csearch`:
   ```
   go install github.com/google/codesearch/cmd/cindex@latest
   go install github.com/google/codesearch/cmd/csearch@latest
   ```
   or drop prebuilt `cindex.exe` / `csearch.exe` at `E:\Tools\`.

`esm` finds the binaries on `PATH` or at `E:\Tools\{es,cindex,csearch}.exe`.
Override with `ES_EXE` / `CINDEX_EXE` / `CSEARCH_EXE`, or the default search root
with `ESM_ROOT`.

## Use

```bash
esm=scripts/smali-search/esm.sh          # or copy to E:\Tools and put on PATH
APP=E:/Morphe/decompiled/HBOMax

# filenames (es.exe) — instant
"$esm" class ExoPlayerWrapper "$APP"
"$esm" find  '*Ad*Overlay*.smali' "$APP"

# content/code (csearch) — index once per decompiled app, then query in ms
"$esm" index "$APP"
"$esm" code  'getAdBreaks|adTagUrl' "$APP"
"$esm" code  'invoke-.*getVideoAds' "$APP" '\.smali$'   # 3rd arg restricts by path regex

# un-indexed content (rg only) — correct but slow; the fallback when no index
"$esm" grep  'SGAI' "$APP"
```

Defaults: root = `E:/Morphe/decompiled`, glob = `*.smali`. Per-tree index lives at
`<root>/.csearchindex`; rebuild with `esm index <root>` after re-decompiling
(read-only trees never need it). `esm code`/`grep` **fail open** to ripgrep when
`csearch` or the index is missing, so a search never just breaks.

Nothing here uses the network or any API key — pure local search.

## Credit

The idea of steering an agent's file search at an **index** instead of walking the
tree comes from **[jonathanavis96/jev-kit](https://github.com/jonathanavis96/jev-kit)**
(MIT, © Jonathan Avis), whose `filesearch` component points searches at Everything's
`es.exe`. jev-kit is an excellent, carefully measured toolkit — thank you. This
script is our own small implementation for the Morphe smali-search need (no jev-kit
code is copied) and adds the content/code index that Everything alone cannot
provide. See the repository `NOTICE`.
