#!/bin/bash
# SessionStart hook (Claude Code on the web only): let the pre-installed
# Chromium used by the Playwright MCP server (scripts/playwright-mcp.sh,
# registered in .mcp.json) load HTTPS pages.
#
# The cloud container's egress proxy re-signs TLS with its own CA
# (/root/.ccr/agent-proxy-ca.crt). Chromium only trusts CAs in its NSS store
# (~/.pki/nssdb), which starts empty, so every page fails with
# ERR_CERT_AUTHORITY_INVALID. This imports that one CA. Certificate
# verification stays ON for everything else; never use --ignore-https-errors.
#
# Idempotent and fast when already done; never fails the session (the browser
# just won't load HTTPS pages if a step here fails).
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

CA=/root/.ccr/agent-proxy-ca.crt
NSSDB="$HOME/.pki/nssdb"
NICK=ccr-agent-proxy

warn() { echo "session-start: $*" >&2; }

[ -f "$CA" ] || exit 0

if ! command -v certutil >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get install -y -qq libnss3-tools >/dev/null 2>&1 \
    || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq libnss3-tools >/dev/null 2>&1; } \
    || { warn "could not install libnss3-tools; the Playwright browser will not trust the proxy CA"; exit 0; }
fi

mkdir -p "$NSSDB"
if [ ! -f "$NSSDB/cert9.db" ]; then
  certutil -N -d "sql:$NSSDB" --empty-password >/dev/null 2>&1 \
    || { warn "could not create $NSSDB"; exit 0; }
fi

if ! certutil -L -d "sql:$NSSDB" -n "$NICK" >/dev/null 2>&1; then
  certutil -A -d "sql:$NSSDB" -t "C,," -n "$NICK" -i "$CA" >/dev/null 2>&1 \
    || { warn "could not import the proxy CA into $NSSDB"; exit 0; }
fi

# Pre-fetch the pinned Playwright MCP package so the MCP server starts quickly
# (the container state is cached after this hook). Non-fatal.
PW_VER=$(sed -n 's/^PLAYWRIGHT_MCP_VERSION="\(.*\)"$/\1/p' "$CLAUDE_PROJECT_DIR/scripts/playwright-mcp.sh" 2>/dev/null)
if [ -n "$PW_VER" ]; then
  npm cache add "@playwright/mcp@$PW_VER" >/dev/null 2>&1 || warn "could not pre-fetch @playwright/mcp@$PW_VER"
fi

exit 0
