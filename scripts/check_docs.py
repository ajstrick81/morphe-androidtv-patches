#!/usr/bin/env python3
"""Check that every relative link in our Markdown resolves, and that every
Mermaid block is structurally sound.

    python3 scripts/check_docs.py

Exits non-zero and prints one line per problem. Stdlib only, no network.

Adapted from jev-kit's tools/check_docs.py (MIT, Copyright (c) 2026 Jonathan
Avis, https://github.com/jonathanavis96/jev-kit). Changes for this repo:
- scans README/CLAUDE/CONTRIBUTING, docs/, testing/, analysis/ and
  experimental/ instead of README + docs/ only;
- skips docs/archive/*-reddit-post.md, which are preserved verbatim on purpose;
- heading anchors follow GitHub's slug rule (one hyphen per space, so
  "A — B" becomes "a--b");
- the Mermaid check is structural only (fences, header, quote-aware bracket
  balance) and accepts every diagram type we use. jev-kit's "every label must
  be quoted" style rule is dropped: GitHub renders our unquoted labels fine.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCAN_DIRS = ("docs", "testing", "analysis", "experimental")
ROOT_FILES = ("README.md", "CLAUDE.md", "CONTRIBUTING.md")
PRUNE_DIRS = {".git", "node_modules", "build", "decompiled", ".gradle"}
VERBATIM_RE = re.compile(r"docs/archive/.*-reddit-post\.md$")

LINK_RE = re.compile(r"!?\[[^\]]*\]\(<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\)")
SRC_RE = re.compile(r"<(?:img|a)[^>]*\s(?:src|href)=\"([^\"]+)\"")
FENCE_RE = re.compile(r"^\s*(```+|~~~+)\s*([\w-]*)")
HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
SKIP_SCHEMES = ("http://", "https://", "mailto:", "tel:", "data:")

MERMAID_TYPES = (
    "flowchart", "graph", "sequenceDiagram", "stateDiagram", "stateDiagram-v2",
    "classDiagram", "erDiagram", "gantt", "pie", "journey", "timeline",
    "mindmap", "gitGraph", "quadrantChart", "xychart-beta", "block-beta",
    "sankey-beta", "requirementDiagram", "C4Context",
)


def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def markdown_files():
    out = [os.path.join(ROOT, f) for f in ROOT_FILES
           if os.path.isfile(os.path.join(ROOT, f))]
    for d in SCAN_DIRS:
        for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, d)):
            dirnames[:] = sorted(n for n in dirnames if n not in PRUNE_DIRS)
            for f in sorted(filenames):
                p = os.path.join(dirpath, f)
                if f.endswith(".md") and not VERBATIM_RE.search(rel(p)):
                    out.append(p)
    return out


def strip_code(text):
    """Blank out fenced code blocks and inline code (links there are examples)."""
    out, fence = [], None
    for line in text.splitlines():
        m = FENCE_RE.match(line)
        if fence is None and m:
            fence = m.group(1)[0] * 3
            out.append("")
            continue
        if fence is not None:
            if line.strip().startswith(fence):
                fence = None
            out.append("")
            continue
        out.append(INLINE_CODE_RE.sub("", line))
    return "\n".join(out)


def slug(title):
    title = re.sub(r"`([^`]*)`", r"\1", title)
    title = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", title)
    title = re.sub(r"<[^>]+>", "", title)
    s = re.sub(r"[^\w\s-]", "", title.strip().lower())
    return re.sub(r"\s", "-", s)


_anchor_cache = {}


def anchors_in(path):
    if path in _anchor_cache:
        return _anchor_cache[path]
    slugs, seen = set(), {}
    try:
        text = open(path, encoding="utf-8").read()
    except (OSError, UnicodeDecodeError):
        _anchor_cache[path] = slugs
        return slugs
    for line in strip_code(text).splitlines():
        m = re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", line)
        if not m:
            continue
        base = slug(m.group(2))
        n = seen.get(base, 0)
        seen[base] = n + 1
        slugs.add(base if n == 0 else "%s-%d" % (base, n))
    for m in re.finditer(r"(?:id|name)=\"([^\"]+)\"", text):
        slugs.add(m.group(1))
    _anchor_cache[path] = slugs
    return slugs


def check_links(path, problems):
    raw = open(path, encoding="utf-8").read()
    text = strip_code(HTML_COMMENT_RE.sub("", raw))
    targets = [m.group(1) for m in LINK_RE.finditer(text)]
    targets += [m.group(1) for m in SRC_RE.finditer(text)]
    for target in targets:
        if target.startswith(SKIP_SCHEMES):
            continue
        file_part, _, anchor = target.partition("#")
        file_part = file_part.split("?", 1)[0]
        if not file_part:
            if anchor and anchor.lower() not in anchors_in(path):
                problems.append("%s: in-page anchor #%s has no matching heading"
                                % (rel(path), anchor))
            continue
        if file_part.startswith("/"):
            resolved = os.path.join(ROOT, file_part.lstrip("/"))
        else:
            resolved = os.path.join(os.path.dirname(path), file_part)
        resolved = os.path.normpath(resolved.replace("%20", " "))
        if not os.path.exists(resolved):
            problems.append("%s: link target does not exist: %s"
                            % (rel(path), target))
        elif anchor and resolved.endswith(".md") and not re.match(r"L\d+", anchor):
            if anchor.lower() not in anchors_in(resolved):
                problems.append("%s: %s exists but has no anchor #%s"
                                % (rel(path), file_part, anchor))


def check_mermaid(path, problems):
    lines = open(path, encoding="utf-8").read().splitlines()
    fence, block, start, is_mermaid = None, [], 0, False
    for n, line in enumerate(lines, 1):
        m = FENCE_RE.match(line)
        if fence is None:
            if m:
                fence, block, start = m.group(1)[0] * 3, [], n
                is_mermaid = m.group(2) == "mermaid"
            continue
        if line.strip().startswith(fence) and line.strip().strip(fence[0]) == "":
            if is_mermaid:
                check_one_mermaid(rel(path), start, block, problems)
            fence = None
            continue
        block.append(line)
    if fence is not None and is_mermaid:
        problems.append("%s:%d: unterminated mermaid block" % (rel(path), start))


def check_one_mermaid(name, start, block, problems):
    body = [b for b in block
            if b.strip() and not b.strip().startswith("%%")]
    if not body:
        problems.append("%s:%d: empty mermaid block" % (name, start))
        return
    first = body[0].strip()
    if first == "---":  # front-matter config block; header follows it
        rest = body[1:]
        while rest and rest[0].strip() != "---":
            rest = rest[1:]
        body = rest[1:] or [""]
        first = body[0].strip()
    if first.split(" ")[0].split(":")[0] not in MERMAID_TYPES:
        problems.append("%s:%d: mermaid block has no known diagram header: %r"
                        % (name, start, first))
    for offset, line in enumerate(block):
        lineno = start + 1 + offset
        if line.strip().startswith("%%"):
            continue
        if line.count('"') % 2:
            problems.append("%s:%d: odd number of quotes in mermaid line"
                            % (name, lineno))
            continue
        bare = re.sub(r'"[^"]*"', '""', line)  # brackets inside quotes are text
        for opener, closer in (("[", "]"), ("(", ")"), ("{", "}")):
            if bare.count(opener) != bare.count(closer):
                problems.append("%s:%d: unbalanced %s%s in mermaid line"
                                % (name, lineno, opener, closer))


def main():
    problems = []
    for path in markdown_files():
        try:
            check_links(path, problems)
            check_mermaid(path, problems)
        except UnicodeDecodeError:
            problems.append("%s: not valid UTF-8" % rel(path))
    for p in problems:
        print("FAIL %s" % p)
    if problems:
        print("\n%d problem(s)" % len(problems))
        return 1
    print("OK: %d Markdown files, every relative link resolves and every "
          "mermaid block is well formed" % len(markdown_files()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
