"""A local credential belt: pure-code patterns for credential-shaped text.

Ported from jev-kit's airlock/belt.py (MIT, Copyright (c) 2026 Jonathan Avis),
which in turn adapted valentynkit/jev-commit's jev_commit/belt.py (MIT,
Copyright (c) 2026 Valentyn Kit). Notices are in the repository NOTICE file.

Two ideas came across:
  - the split between HIGH-PRECISION patterns, specific enough to act on, and
    HIGH-RECALL ones, which only ever inform; and
  - placeholder suppression, which stops `sk-your_key_here` and
    `password: <redacted>` from being treated as findings.

Nothing here talks to a network or a model. Callers only ever see the first
four characters of a match; the value itself is never returned or printed.
"""
import math
import re

# Anchored on the left so a prefix only counts at the start of a token:
# otherwise `sk-[A-Za-z0-9]{20,}` fires on `risk-<20 chars>`.
LEFT = r"(?<![A-Za-z0-9])"

HIGH_PRECISION = [
    ("aws_access_key", re.compile(LEFT + r"(?:AKIA|ASIA)[0-9A-Z]{16}")),
    ("github_pat", re.compile(LEFT + r"github_pat_[A-Za-z0-9_]{20,}")),
    ("github_token", re.compile(LEFT + r"gh[pousr]_[A-Za-z0-9]{20,}")),
    ("anthropic_key", re.compile(LEFT + r"sk-ant-[A-Za-z0-9_\-]{20,}")),
    ("openai_key", re.compile(LEFT + r"sk-(?:proj-)?[A-Za-z0-9_\-]{20,}")),
    ("slack_token", re.compile(LEFT + r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("google_api_key", re.compile(LEFT + r"AIza[0-9A-Za-z_\-]{35}")),
    ("google_oauth_token", re.compile(LEFT + r"ya29\.[0-9A-Za-z_\-]{20,}")),
    ("gitlab_token", re.compile(LEFT + r"glpat-[\w-]{20}")),
    ("sendgrid_key", re.compile(LEFT + r"SG\.[\w-]{22}\.")),
    ("npm_token", re.compile(LEFT + r"npm_[A-Za-z0-9]{36}")),
    ("stripe_key", re.compile(LEFT + r"[sr]k_live_[0-9A-Za-z]{20,}")),
    ("vercel_token", re.compile(LEFT + r"vck_[A-Za-z0-9]{20,}")),
    ("typesafe_key", re.compile(LEFT + r"apikey_[A-Za-z0-9_]{16,}")),
    ("private_key", re.compile(
        r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY( BLOCK)?-----")),
    ("jwt", re.compile(LEFT + r"eyJ[\w-]{10,}\.eyJ[\w-]{10,}\.")),
    ("url_credentials", re.compile(LEFT + r"\w[\w+.-]*://[^/\s:@'\"]+:[^/\s:@'\"]+@")),
]

# Never blocks. Useful for a report ("this looked credential-shaped").
HIGH_RECALL = [
    ("config_credential", re.compile(
        r"(?i)(pass(word|wd)?|secret|token|api[_-]?key|storepass|keypass)"
        r"\s*[:=]\s*[\"']?(?P<value>[^\s\"']{8,})")),
]

ENTROPY_RUN = re.compile(r"[A-Za-z0-9+/=_-]{32,}")
ENTROPY_MIN = 4.0

PLACEHOLDER_WORDS = (
    "example", "placeholder", "dummy", "changeme", "redacted", "sample",
    "fake", "your_", "your-", "xxxx", "test-value", "user:password",
    "user:pass", "username:password", "<token>", "...",
)
PLACEHOLDER_SHAPES = [
    re.compile(r"^[xX*.]+$"),
    re.compile(r"^<[^>]*>$"),
    re.compile(r"^\$\{[^}]*\}$"),
    re.compile(r"^\$\{\{[^}]*\}\}$"),
    re.compile(r"^\$[A-Z_][A-Z0-9_]*$"),
    re.compile(r"^%[A-Z_][A-Z0-9_]*%$"),
]

# Files whose mere presence in a commit is the problem: signing keys.
# Binary keystores (the patch-signing keystore lives in the gitignored
# testing/keystore/). PEM private keys are caught by content instead, and the
# tracked experimental/*/profiles/*.env files are plain config, so neither is here.
KEY_FILE_RE = re.compile(
    r"(?i)(?:\.(?:jks|keystore|bks|p12|pfx|ppk)$"
    r"|(?:^|/)id_(?:rsa|dsa|ecdsa|ed25519)$)"
)
KEY_FILE_ALLOW_RE = re.compile(r"(?i)(?:template|example|sample)")


def shannon(text):
    if not text:
        return 0.0
    counts = {}
    for ch in text:
        counts[ch] = counts.get(ch, 0) + 1
    n = len(text)
    return -sum((c / n) * math.log2(c / n) for c in counts.values())


def is_placeholder(value):
    low = (value or "").lower()
    if any(word in low for word in PLACEHOLDER_WORDS):
        return True
    stripped = (value or "").strip("\"'`,;")
    return any(shape.match(stripped) for shape in PLACEHOLDER_SHAPES)


def says_placeholder(line):
    """A matched value has no context of its own, so read the whole line: a
    false block is the costlier mistake."""
    low = (line or "").lower()
    return any(word in low for word in PLACEHOLDER_WORDS)


def redact(value):
    """First four characters, never the secret."""
    value = value or ""
    return value[:4] + "..." if len(value) > 4 else "..."


def scan_text(text, include_recall=False):
    """Credential-shaped hits, at most one per line: [{kind, precision, lineno, redacted}]."""
    hits = []
    for lineno, line in enumerate((text or "").splitlines(), 1):
        found = None
        for kind, pattern in HIGH_PRECISION:
            m = pattern.search(line)
            if m and not is_placeholder(m.group(0)) and not says_placeholder(line):
                found = (kind, "high", m.group(0))
                break
        if not found and include_recall:
            for kind, pattern in HIGH_RECALL:
                m = pattern.search(line)
                if m and not is_placeholder(m.group("value")):
                    found = (kind, "recall", m.group("value"))
                    break
            if not found:
                for run in ENTROPY_RUN.findall(line):
                    if shannon(run) >= ENTROPY_MIN and not is_placeholder(run):
                        found = ("high_entropy_string", "recall", run)
                        break
        if found:
            hits.append({"kind": found[0], "precision": found[1],
                         "lineno": lineno, "redacted": redact(found[2])})
    return hits


def blocking_hits(text):
    return [h for h in scan_text(text) if h["precision"] == "high"]


def is_key_file(path):
    return bool(KEY_FILE_RE.search(path)) and not KEY_FILE_ALLOW_RE.search(path)
