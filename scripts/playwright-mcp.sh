#!/usr/bin/env bash
# Launches the Playwright MCP server (https://github.com/microsoft/playwright-mcp)
# so Claude Code can drive a real browser: read JS-rendered pages, click through
# sites, take screenshots. Registered for this repo in .mcp.json.
#
# Works in both places Claude Code runs for this project:
#   - Claude Code on the web (cloud container): uses the pre-installed Chromium
#     at /opt/pw-browsers/chromium (never downloads one) and routes the browser
#     through the container's egress proxy ($HTTPS_PROXY).
#   - A local machine: Playwright uses its own browser (run
#     `npx playwright install chromium` once if it asks).
#
# A browser does NOT get around the cloud environment's network policy: hosts
# the policy blocks (e.g. apkmirror.com, gist.githubusercontent.com) stay
# blocked. Change "Network access" in the environment settings for those.
set -euo pipefail

# Pinned (not @latest) so a compromised or broken upstream release can't run
# here unreviewed. Bump deliberately.
PLAYWRIGHT_MCP_VERSION="0.0.82"

args=(--headless --isolated)

if [ -x /opt/pw-browsers/chromium ]; then
  args+=(--executable-path /opt/pw-browsers/chromium)
fi
if [ -n "${HTTPS_PROXY:-}" ]; then
  # Keep local previews (e.g. `python3 -m http.server` for docs/diagrams/)
  # off the proxy; it rejects plain-HTTP requests with 405.
  args+=(--proxy-server "$HTTPS_PROXY" --proxy-bypass "localhost,127.0.0.1,::1")
fi
# Running as root in a container: Chromium's sandbox needs user namespaces
# that containers usually don't provide.
if [ "$(id -u)" = 0 ]; then
  args+=(--no-sandbox)
fi

exec npx -y "@playwright/mcp@${PLAYWRIGHT_MCP_VERSION}" "${args[@]}" "$@"
