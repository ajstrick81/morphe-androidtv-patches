#!/usr/bin/env python
"""PreToolUse guard: block piping fetched/DNS content into a shell/eval.

Defends against the supply-chain reverse-shell class where a command pulls a
payload from the network (or a DNS TXT record) and pipes/substitutes it straight
into a shell interpreter. Reads the tool-call JSON on stdin; if the command
matches a dangerous pattern, emits a PreToolUse "deny" decision. Otherwise stays
silent (allow). Plain downloads-to-file (curl -o, wget -O file) are NOT blocked.
"""
import sys
import json
import re

FETCH = r"(?:curl(?:\.exe)?|wget(?:\.exe)?|iwr|invoke-webrequest|wget|start-bittransfer)"
DNS = r"(?:dig|nslookup|host)"
SHELL = r"(?:sh|bash|zsh|dash|eval|iex|invoke-expression)"

PATTERNS = [
    # 1. network fetch piped into a shell/eval:  curl ... | bash ,  iwr ... | iex
    re.compile(FETCH + r"\b[^|\n]*\|\s*(?:sudo\s+)?" + SHELL + r"\b", re.I),
    # 2. DNS TXT lookup piped into a shell/eval:  dig ... TXT ... | sh
    re.compile(DNS + r"\b[^|\n]*\btxt\b[^|\n]*\|\s*" + SHELL + r"\b", re.I),
    # 3. command-substitution of a fetch/DNS run by eval / shell -c / iex:
    #    eval "$(curl ...)" , bash -c "$(wget ...)" , `curl ...`
    re.compile(
        r"(?:eval|iex|invoke-expression|(?:sh|bash|zsh|dash)\s+-c|-command)\b[^\n]*"
        r"(?:\$\(|`)[^\n]*\b(?:" + FETCH + r"|" + DNS + r")\b",
        re.I,
    ),
    # 4. PowerShell one-liners: iex (iwr ...) , iex (New-Object Net.WebClient).DownloadString(...)
    re.compile(
        r"(?:iex|invoke-expression)\b[^\n]*"
        r"(?:\(\s*(?:iwr|invoke-webrequest|curl|wget)|downloadstring|net\.webclient)",
        re.I,
    ),
]

REASON = (
    "Blocked by fetch-exec guard: this command appears to pipe network- or "
    "DNS-fetched content directly into a shell/eval (the supply-chain "
    "reverse-shell pattern). Download the script to a file first, inspect its "
    "full contents, and only then run it manually if you trust it. Plain "
    "downloads (curl -o file, wget -O file) are allowed."
)


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return  # malformed input -> don't interfere
    cmd = (data.get("tool_input") or {}).get("command") or ""
    if not isinstance(cmd, str) or not cmd.strip():
        return
    if any(p.search(cmd) for p in PATTERNS):
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": REASON,
            }
        }))


if __name__ == "__main__":
    main()
