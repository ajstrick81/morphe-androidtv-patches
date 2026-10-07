#!/usr/bin/env python
"""Block commits that carry a credential or a signing keystore. Offline.

Two modes:

  PreToolUse hook (stdin JSON, registered in .claude/settings.json):
    On a Bash `git ... commit ...`, scans the ADDED lines about to be committed
    (staged, plus unstaged tracked changes for `commit -a`, plus files a
    `git add` in the same command would pick up) with secret_belt.py's
    high-precision patterns, and denies the call on a hit. Silent otherwise.
    Fails open on any error: a broken guard must never block work.

  CLI (CI backstop, and for humans):
    python3 .claude/hooks/guard_commit_secrets.py --range origin/main...HEAD
    python3 .claude/hooks/guard_commit_secrets.py --staged
    Exits 1 and prints file:line kind first-4-chars for each hit.

Only the first four characters of a match are ever printed. Nothing leaves the
machine. A deliberate, reviewed exception goes on the line itself as the
comment `secret-belt: allow`, so it shows up in the diff.
"""
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import secret_belt  # noqa: E402

ALLOW_MARK = "secret-belt: allow"
MAX_FILE_BYTES = 1 << 20
GIT_TIMEOUT = 10

# `git [-C dir] [-c k=v] ... commit`, in command position.
COMMIT_RE = re.compile(
    r"(?:^|[;&|(\n]|\$\()\s*git(?:\s+-[Cc]\s+\S+|\s+--?[\w-]+(?:=\S+)?)*\s+commit\b")
COMMIT_ALL_RE = re.compile(r"\bcommit\b[^;&|\n]*\s(?:-[a-zA-Z]*a[a-zA-Z]*|--all)\b")
ADD_RE = re.compile(
    r"(?:^|[;&|(\n])\s*git(?:\s+-[Cc]\s+\S+)*\s+add\b([^;&|\n]*)")
GIT_C_RE = re.compile(r"\bgit\s+-C\s+(\"[^\"]+\"|'[^']+'|\S+)")


def git(cwd, *args):
    out = subprocess.run(["git", *args], cwd=cwd, capture_output=True,
                         text=True, timeout=GIT_TIMEOUT, errors="replace")
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout


def added_lines(diff_text):
    """Yield (path, new_lineno, text) for every added line of a -U0 diff."""
    path, lineno = None, 0
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            target = line[4:]
            path = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
        elif line.startswith("@@"):
            m = re.search(r"\+(\d+)", line)
            lineno = int(m.group(1)) if m else 0
        elif line.startswith("+") and path is not None:
            yield path, lineno, line[1:]
            lineno += 1


def scan_lines(items):
    findings = []
    for path, lineno, text in items:
        if ALLOW_MARK in text:
            continue
        for hit in secret_belt.blocking_hits(text):
            findings.append("%s:%d %s %s" % (path, lineno, hit["kind"], hit["redacted"]))
    return findings


def scan_key_files(paths):
    return ["%s keystore/private-key file" % p for p in paths if secret_belt.is_key_file(p)]


def read_untracked(cwd, path):
    full = os.path.join(cwd, path)
    try:
        if os.path.getsize(full) > MAX_FILE_BYTES:
            return []
        with open(full, "rb") as fh:
            data = fh.read()
    except OSError:
        return []
    if b"\0" in data[:8192]:
        return []
    text = data.decode("utf-8", "replace")
    return [(path, n, line) for n, line in enumerate(text.splitlines(), 1)]


def pending_findings(cwd, command):
    """What `command` would commit, scanned. Raises on git trouble (caller fails open)."""
    diff_args = ["diff", "--no-color", "--no-ext-diff", "-U0"]
    lines = list(added_lines(git(cwd, *diff_args, "--cached")))
    new_files = git(cwd, "diff", "--cached", "--name-only", "--diff-filter=A").split("\n")

    adds = [m.group(1) for m in ADD_RE.finditer(command)]
    if COMMIT_ALL_RE.search(command) or adds:
        lines += list(added_lines(git(cwd, *diff_args)))
    if adds:
        untracked = [p for p in git(cwd, "ls-files", "--others", "--exclude-standard").split("\n") if p]
        wanted = []
        for args in adds:
            tokens = [t.strip("\"'") for t in args.split()]
            if any(t in ("-A", "--all", ".", ":/", "*") for t in tokens):
                wanted = untracked
                break
            paths = [t.rstrip("/") for t in tokens if not t.startswith("-")]
            wanted += [u for u in untracked
                       if any(u == p or u.startswith(p + "/") for p in paths)]
        for path in sorted(set(wanted)):
            lines += read_untracked(cwd, path)
        new_files += wanted
    return scan_key_files([p for p in new_files if p]) + scan_lines(lines)


def deny(findings):
    shown = findings[:10]
    more = "" if len(findings) <= 10 else "\n  ... and %d more" % (len(findings) - 10)
    reason = (
        "Blocked by commit-secret guard: the commit would add credential-shaped "
        "text or a keystore file:\n  " + "\n  ".join(shown) + more + "\n"
        "Remove it (and rotate it if it was ever real, see "
        "docs/SECURITY_PRACTICES.md rule 3). If a line is a deliberate, "
        "reviewed exception, add the comment `secret-belt: allow` to that line."
    )
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    }}))


def hook():
    try:
        data = json.load(sys.stdin)
        command = (data.get("tool_input") or {}).get("command") or ""
        if not isinstance(command, str) or not COMMIT_RE.search(command):
            return
        cwd = data.get("cwd") or os.getcwd()
        m = GIT_C_RE.search(command)
        if m:
            cwd = os.path.join(cwd, os.path.expanduser(m.group(1).strip("\"'")))
        findings = pending_findings(cwd, command)
    except Exception:
        return  # fail open
    if findings:
        deny(findings)


def cli(argv):
    import argparse
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--range", help="git revision range, e.g. origin/main...HEAD")
    g.add_argument("--staged", action="store_true", help="scan the index")
    args = ap.parse_args(argv)
    cwd = os.getcwd()
    diff = ["diff", "--no-color", "--no-ext-diff", "-U0"]
    spec = ["--cached"] if args.staged else [args.range]
    lines = added_lines(git(cwd, *diff, *spec))
    added = git(cwd, "diff", "--name-only", "--diff-filter=A", *spec).split("\n")
    findings = scan_key_files([p for p in added if p]) + scan_lines(lines)
    for f in findings:
        print("SECRET %s" % f)
    if findings:
        print("\n%d finding(s). Remove and rotate, or mark a reviewed line with "
              "`%s`." % (len(findings), ALLOW_MARK))
        return 1
    print("OK: no credential-shaped text or keystore files in %s"
          % (args.range or "the index"))
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1:
        sys.exit(cli(sys.argv[1:]))
    hook()
