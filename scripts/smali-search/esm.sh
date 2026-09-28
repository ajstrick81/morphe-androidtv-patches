#!/usr/bin/env bash
# esm — fast smali/APK search for the Morphe workflow.
#
#   FILENAME search : es.exe (voidtools Everything)      — instant, name/path index
#   CONTENT search  : csearch/cindex (Google Codesearch) — instant, trigram CODE index
#   fallback        : ripgrep (always works, no index)
#
# WHY: a tree-wide content grep over a decompiled app is the workflow's slowest
# step. Measured on HBO Max 7.12.0.68 (33,601 smali files): `rg` across the tree
# took ~165 s (over our 120 s tool budget); `find` by name was ~1 s. es.exe makes
# file location instant; csearch makes CONTENT search instant after a one-time
# index. We decompile an app once and search it dozens of times a session, so the
# index amortises immediately.
#
# Inspiration & credit: the "steer the agent's file search at an index instead of
# walking the tree" idea comes from jonathanavis96/jev-kit (MIT), whose filesearch
# component points searches at Everything's `es.exe`. jev-kit is an excellent,
# well-measured toolkit — thank you to Jonathan Avis. This script is our own small
# implementation for the Morphe smali-search need (no jev-kit code is copied), and
# it adds the content/code index (Google Codesearch) that Everything alone can't
# provide. See NOTICE and scripts/smali-search/README.md.
#
# Install (binaries are installed by a human, not by an agent): see README.md.
#
# Usage:
#   esm class <Name>        [root]            # locate <Name>.smali        (es.exe)
#   esm find  <name-glob>   [root]            # locate files by name       (es.exe)
#   esm index               [root]            # (re)build content index    (cindex)
#   esm code  <regex> [root] [file-regex]     # content/code search        (csearch)
#   esm grep  <regex> [root] [glob]           # un-indexed content         (rg only)
#
# root defaults to E:/Morphe/decompiled ; glob defaults to *.smali
# Binary overrides: ES_EXE, CINDEX_EXE, CSEARCH_EXE. Default root: ESM_ROOT.
set -euo pipefail

DEFAULT_ROOT="${ESM_ROOT:-E:/Morphe/decompiled}"
ES="${ES_EXE:-es}";           command -v "$ES"  >/dev/null 2>&1 || ES="/e/Tools/es.exe"
CIDX="${CINDEX_EXE:-cindex}";  command -v "$CIDX" >/dev/null 2>&1 || CIDX="/e/Tools/cindex.exe"
CSR="${CSEARCH_EXE:-csearch}"; command -v "$CSR"  >/dev/null 2>&1 || CSR="/e/Tools/csearch.exe"

winpath() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || echo "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

sub="${1:-}"; shift || true
case "$sub" in
  find|class)
    if ! have "$ES"; then echo "esm: es.exe not found (see README.md)." >&2; exit 127; fi
    if [ "$sub" = class ]; then pat="${1:?ClassName}.smali"; else pat="${1:?name glob}"; fi
    root="${2:-$DEFAULT_ROOT}"
    "$ES" -path "$(winpath "$root")" "$pat"
    ;;

  index)
    root="${1:-$DEFAULT_ROOT}"
    if ! have "$CIDX"; then echo "esm: cindex not found (see README.md)." >&2; exit 127; fi
    idx="$(winpath "$root/.csearchindex")"
    echo "esm: indexing $root -> $idx" >&2
    # Fresh per-tree index: reset so this index holds ONLY this root.
    CSEARCHINDEX="$idx" "$CIDX" -reset >/dev/null 2>&1 || true
    CSEARCHINDEX="$idx" "$CIDX" "$(winpath "$root")"
    ;;

  code)
    re="${1:?regex}"; root="${2:-$DEFAULT_ROOT}"; frx="${3:-}"
    idx="$(winpath "$root/.csearchindex")"
    if have "$CSR" && [ -f "$root/.csearchindex" ]; then
      if [ -n "$frx" ]; then
        CSEARCHINDEX="$idx" "$CSR" -n -f "$frx" "$re"
      else
        CSEARCHINDEX="$idx" "$CSR" -n "$re"
      fi
    else
      # Fail open: no csearch or no index yet -> ripgrep the tree (slow but correct).
      [ -f "$root/.csearchindex" ] || echo "esm: no index at $root/.csearchindex (run 'esm index $root'); using rg" >&2
      have "$CSR" || echo "esm: csearch not found; using rg" >&2
      rg -n --no-messages -g '*.smali' "$re" "$root"
    fi
    ;;

  grep)
    re="${1:?regex}"; root="${2:-$DEFAULT_ROOT}"; glob="${3:-*.smali}"
    rg -n --no-messages -g "$glob" "$re" "$root"
    ;;

  *)
    echo "usage: esm {class <Name>|find <glob>|index|code <regex>|grep <regex>} [root] [file-regex|glob]" >&2
    exit 2
    ;;
esac
