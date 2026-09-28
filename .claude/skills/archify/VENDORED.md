# Vendored: Archify

Third-party Claude Code skill for interactive HTML diagrams, vendored into this
repo instead of installed with `npx skills add … -g` (which always tracks the
latest version and can't reach github.com from our cloud sessions anyway).

| | |
|---|---|
| Upstream | https://github.com/tt-a1i/archify (MIT, see `LICENSE`) |
| Commit | `9e35d2b0b39b155553ba9fcfe0b4f2a5198dd993` (2026-09-23), skill version 2.17.0-dev.1 |
| Copied | the `archify/` skill folder |
| Left out | `test/` and the five rendered `examples/*.html` gallery pages (3.9 MB). The JSON examples the skill reads are kept |

## Review notes (2026-09-23)

- No runtime dependencies (only dev ones: ajv, parse5, saxes, simple-icons),
  and no install or postinstall scripts. `node bin/archify.mjs` works with no
  `npm install`.
- No `eval` or `new Function`. Child processes: `git` (read-only, for
  repository evidence) and Node running its own scripts.
- The only network call is `scripts/check-update.mjs`: one `GET` to a fixed
  URL (`https://tt-a1i.github.io/archify/skill-updates/archify/stable.json`)
  that refuses any other URL. It is informational only and never downloads or
  installs anything. In our cloud sessions it's blocked and exits silently.
- Output HTML is fully self-contained: inline SVG, CSS and JS, no CDN or
  external scripts.

## Using it here

- Sources and output live in `docs/diagrams/` (`<name>.<type>.json` →
  `<name>.html`, plus a `<name>.png` preview for READMEs and gists).
- Validate, deliver, then browser-check. Run these from this folder:
  `node bin/archify.mjs validate <type> <spec> --quality showcase --json`,
  then `deliver`, then `ARCHIFY_CHROME=/opt/pw-browsers/chromium node bin/archify.mjs visual-check <html> --json`
  (the `ARCHIFY_CHROME` path is for Claude Code on the web).
- Commit only the spec, the HTML and one preview PNG, not the
  `*.visual-check.*` sidecars.

## Updating

Re-clone upstream at a new commit, review the diff (especially `bin/`,
`scripts/`, anything touching the network or child processes), copy the
`archify/` folder with the same exclusions, and update the commit above.
