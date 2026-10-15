#!/usr/bin/env python
"""PreToolUse guard: block running fetched/DNS content through an interpreter.

Defends against the supply-chain reverse-shell class where a command pulls a
payload from the network (or a DNS TXT record) and pipes/substitutes it straight
into a shell or script interpreter. Reads the tool-call JSON on stdin; if the
command matches a dangerous pattern, emits a PreToolUse "deny" decision.
Otherwise stays silent (allow). Plain downloads-to-file (curl -o, wget -O file)
are NOT blocked.

Covered shapes (labelled cases in test_guard_fetch_exec.py):
  curl ... | [sudo -E|env X=1] bash|sh|python3|perl|node|ruby|php
  curl ... | tee f | sh            (pass-through stages in the pipe)
  bash|source|. <(curl ...)        (process substitution)
  eval|bash -c|python3 -c|bash <<< "$(curl ...)"
  dig ... TXT ... | sh
  iwr|irm ... | iex, iex (iwr ...), iex (...).DownloadString(...)

A fetch only counts in command position (start of line or after ; & | ( $( `
plus sudo/env prefixes), so `echo curl is a tool | bash` and a commit message
quoting "curl | bash" are left alone.

Fails open: malformed input or any internal error allows the call.
"""
import sys
import json
import re

# Prefixes that can sit in front of a command: sudo [flags], env [-i] [X=1],
# exec/command/nohup/time, and bare VAR=value assignments.
PREFIX = (
    r"(?:(?:sudo(?:\s+-{1,2}[\w-]+(?:[= ](?!-)\S+)?)*"
    r"|env(?:\s+-\S+)*"  # env's X=1 args are matched by the \w+= branch
    r"|exec|command|nohup|time"
    r"|\w+=\S*)\s+)*"
)
# Start of a command: line start, or after a separator / subshell opener.
CMD_POS = r"(?:^|[;&|({\n]|\$\(|`)\s*" + PREFIX
PATHED = r"(?:[\w.~/-]*/)?"

FETCH = PATHED + (
    r"(?:curl|wget|wget2|aria2c|fetch|iwr|irm|invoke-webrequest|invoke-restmethod"
    r"|start-bittransfer)(?:\.exe)?"
)
DNS = PATHED + r"(?:dig|nslookup|host|drill|resolvectl)(?:\.exe)?"
SHELLS = r"(?:sh|bash|zsh|dash|ksh|mksh|ash|fish|busybox\s+sh)"
SCRIPTERS = r"(?:python[\d.]*|perl|ruby|node|nodejs|deno|bun|php|lua|pwsh|powershell)"
# What a pipe can feed: a shell, a script interpreter, or eval-ish.
SINK = PATHED + r"(?:" + SHELLS + r"|" + SCRIPTERS + r"|iex|invoke-expression)(?:\.exe)?"
# Interpreter flags that run their argument as code.
CODE_FLAG = r"(?:-c|-e|-r|-command|--eval|-encodedcommand)"
END = r"(?=$|[\s;&|)`\"'])"

# One pipeline segment ends at ; & or a newline; pipes may chain inside it.
IN_PIPELINE = r"[^;&\n]*?"

PATTERNS = [
    # 1. fetch piped (through any pass-through stages) into an interpreter.
    #    Harmless readers such as `| python3 -m json.tool` are excluded.
    re.compile(
        CMD_POS + FETCH + r"\b" + IN_PIPELINE + r"\|\s*" + PREFIX + SINK + END
        + r"(?!\s+-m\s)",
        re.I,
    ),
    # 2. DNS TXT lookup piped into an interpreter.
    re.compile(
        CMD_POS + DNS + r"\b" + IN_PIPELINE + r"\btxt\b" + IN_PIPELINE
        + r"\|\s*" + PREFIX + SINK + END,
        re.I,
    ),
    re.compile(
        CMD_POS + DNS + r"\b[^;&\n|]*-q(?:uery)?type=txt" + IN_PIPELINE
        + r"\|\s*" + PREFIX + SINK + END,
        re.I,
    ),
    # 3. process substitution handed to an interpreter:  bash <(curl ...)
    re.compile(
        CMD_POS + r"(?:" + PATHED + SHELLS + r"|" + PATHED + SCRIPTERS
        + r"|source|\.)(?:\s+-\S+)*\s+<\(\s*" + PREFIX + FETCH + r"\b",
        re.I,
    ),
    # 4. command substitution run as code:  eval "$(curl ...)", bash -c "$(wget ...)",
    #    python3 -c "$(curl ...)", bash <<< "$(curl ...)", sh -c "`curl ...`"
    re.compile(
        r"(?:\beval|\biex|\binvoke-expression|\bsource|(?:^|[\s;&|(])\."
        r"|\b" + SHELLS + r"\s+(?:-\S+\s+)*(?:-c|<<<)"
        r"|\b" + SCRIPTERS + r"(?:\.exe)?\s+(?:-\S+\s+)*" + CODE_FLAG + r")"
        r"\s*[\"']?\s*(?:\$\(|`)\s*" + PREFIX + r"(?:" + FETCH + r"|" + DNS + r")\b",
        re.I,
    ),
    # 5. PowerShell one-liners: iex (iwr ...), iex (New-Object Net.WebClient).DownloadString(...)
    re.compile(
        r"\b(?:iex|invoke-expression)\b[^\n]*"
        r"(?:\(\s*(?:iwr|irm|invoke-webrequest|invoke-restmethod|curl|wget)\b"
        r"|downloadstring|downloaddata|net\.webclient)",
        re.I,
    ),
]

REASON = (
    "Blocked by fetch-exec guard: this command appears to run network- or "
    "DNS-fetched content directly through a shell or interpreter (the "
    "supply-chain reverse-shell pattern). Download the script to a file first, "
    "inspect its full contents, and only then run it manually if you trust it. "
    "Plain downloads (curl -o file, wget -O file) are allowed. "
    "See docs/SECURITY_PRACTICES.md."
)


# `cmd <<'EOF'` / `cmd <<-EOF`: the body is data unless cmd is a shell.
HEREDOC_RE = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][\w-]*)\1")
SHELL_HEAD_RE = re.compile(
    r"(?:^|[;&|(]|\$\()\s*" + PREFIX + PATHED + SHELLS + r"(?:\s+-\S+)*\s*$", re.I)


def strip_data_heredocs(cmd):
    """Drop heredoc bodies that feed a non-shell (python3 -, cat > f, git
    commit -F -), so text that quotes `curl | bash` is not read as a command.
    A body fed to sh/bash/zsh/... is kept and scanned: that is real code."""
    lines = cmd.split("\n")
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        i += 1
        m = HEREDOC_RE.search(line)
        if not m or SHELL_HEAD_RE.search(line[:m.start()]):
            continue
        delim = m.group(2)
        while i < len(lines) and lines[i].strip() != delim:
            i += 1
        if i < len(lines):
            out.append(lines[i])  # keep the terminator line
            i += 1
    return "\n".join(out)


def is_dangerous(cmd):
    cmd = strip_data_heredocs(cmd)
    return any(p.search(cmd) for p in PATTERNS)


def main():
    try:
        data = json.load(sys.stdin)
        cmd = (data.get("tool_input") or {}).get("command") or ""
        if not isinstance(cmd, str) or not cmd.strip():
            return
        if not is_dangerous(cmd):
            return
    except Exception:
        return  # fail open: a broken guard must never block or crash a call
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": REASON,
        }
    }))


if __name__ == "__main__":
    main()
