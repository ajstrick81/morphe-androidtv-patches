# Project Philosophy

My Reflection - As it relates to this GitHub repo, it's worth reflecting on the journey. We went from stumbling through the GitHub passing basic checks to a highly sophisticated apk patching resource. I believe part of this whole process is embracing curiosity, accepting challenges, and looking beyond "walls" that seem impenetrable. Our focus has been growth mindset and we have moved away from architectural limitations. It has been incredible and I couldn't have made it this far without Claude. Hoping the journey continues to be successful and one that overcomes adversity as it undoubtedly will come. So thankful for having built a great project beyond my expectations.

This project runs on a **growth mindset**. What started as basic patches that
just passed checks has grown into a sophisticated APK patching resource
because of a deliberate approach:

- **Stay curious.** Treat unfamiliar app internals, obfuscation, and manifest
  quirks as things to investigate, not blockers to route around.
- **Accept the hard problems.** Prefer tackling the real ad-injection or
  DRM/anti-tamper mechanism over a shallow workaround, even when it takes
  longer.
- **Walls are usually not walls.** When something looks architecturally
  impossible (a "can't be done" limitation), dig one level deeper before
  accepting that conclusion — most limitations turn out to be assumptions,
  not hard constraints.
- **Expect adversity and keep going.** Apps update, obfuscation changes,
  detection improves — setbacks are the normal cost of this work, not a
  signal to stop.

When picking up new patching challenges (new apps, broken patches after an
app update, anti-tamper/detection countermeasures, etc.), default to this
mindset: explore first, question assumed limitations, and iterate rather
than settling for the first workaround.

## Why we build this: ownership

> **You bought the TV. You control the TV. You own the glass.**
> — Rory Gallagher, [lg-webos-dashboard](https://github.com/rorygallagher2024/lg-webos-dashboard) (MIT).
> This project is inspired by and aligned with Rory's work.

Growth mindset is *how* we work; ownership is *why*. Owning the device and
paying for the service should mean the viewer decides what plays on their
screen. Every patch should serve that, so when designing or reviewing one:

- **Stay local.** Patches run on the device. Never add calls to a
  project-controlled server, analytics, or telemetry of our own.
- **Take away, don't add.** Remove or mask ads and ad-tracking; don't
  introduce new tracking, accounts, or cloud dependencies in their place.
- **Be transparent.** Keep patch names and descriptions honest about what
  they change, and keep `docs/SEAM_MAP.md` current.
- **No dark patterns.** Anything that changes behavior beyond ad removal
  (clones, household prompts, origin blocks) stays opt-in and clearly
  labeled.
- **Don't weaken the device by default.** Patches that lower an app's own
  protections (TLS trust overrides for HTTPS inspection, and the like) stay
  opt-in unless ad removal truly needs them, and their descriptions state the
  trade-off.
- **Be honest about reach.** The patches work inside the app only. They don't
  touch TV-level ACR, voice capture or the OS's own network scanning, so docs
  should never imply they do.

# Repository Conventions

Learned during the 2026-09 cleanup (PR #191); keep the repo this way.

**Branches**
- `main` is the only long-lived branch. Work on short-lived feature branches
  and merge them via PR. There is no `dev` branch or prerelease channel
  anymore; semantic-release only releases from `main`.
- `feat`/`fix`/`perf`/`revert` commits cut a release. Use `docs`/`chore`/
  `refactor`/`ci`/`experiment` for anything that shouldn't.
- When a branch is abandoned, don't just let it rot. Copy any docs or
  research worth keeping into `main`, then archive it with the **Archive
  branches** workflow (Actions → Run workflow, paste names, `dry-run` then
  `execute`). It tags the branch `archive/<branch>` before deleting it.
  `docs/archive/BRANCH_ARCHIVE.md` is the index of archived branches. Add new
  ones there.
- The **Stale branch report** workflow runs on the 1st of each month and
  keeps one open issue (label `stale-branches`) listing branches with no
  commits in 30+ days. It only reports; a human decides what to archive.
- `main`'s history was rewritten on 2026-08-28. Branches or PRs older than
  that share no ancestor with `main`, so GitHub shows them as "hundreds of
  commits ahead" or "closed, not merged" even if their content landed.
  Compare file contents, not commit graphs.

**Code layout**
- All patches live under one package root:
  `patches/src/main/kotlin/ajstrick81/morphe/patches/<app>/`. All
  extensions live under
  `extensions/extension/src/main/java/ajstrick81/morphe/extension/<app>/`.
  Never add new code under `app.morphe.*`. That namespace belongs to the
  upstream Morphe libraries (`app.morphe.patcher`, `app.morphe.util`, ...),
  which we import but don't own.
- Morphe identifies patches by their `name`, not their class or package, so
  moving or renaming packages is safe for users. Renaming a patch's `name`
  is not.
- Extension entry points that are only called from injected smali need a
  `-keep` rule in `extensions/proguard-rules.pro`, or R8 strips them.

**Native code**
- `experimental/primevideo-libignite-native/jni/` is **production source**,
  despite the path. CI/release compile `libpvhook.so` from it with the NDK.
  Host unit test:
  `g++ -std=c++17 test_remote_strip.cpp remote_strip.cpp -o t && ./t`.
- `patches/src/main/resources/netflix/native/armeabi-v7a/libgadget.so` must
  stay committed. It ships in the bundle.

**Where research goes**
- `docs/`: cross-app references, playbooks, handoffs, guides.
- `experimental/<topic>/`: probes, frida scripts, prototypes and failed
  experiments (keep a README recording what was learned). Nothing here
  ships unless CI builds it explicitly.
- `analysis/<app>/`: decompile and recon notes for an app.
- `testing/`: device test harness, runbooks, capture scripts.
- Never commit APKs or decompiled app sources (`.gitignore` covers
  `*.apk` and `decompiled/`).

**Announcements & archived posts**
- Each shipping app has a maintained current-state write-up
  `docs/<APP>_ANNOUNCEMENT.md` (the source the Reddit post mirrors) and a full
  teardown `docs/<APP>_ATV_SYSTEM_DESIGN.md`. The announcement is authoritative:
  its top blockquote links the archived original and states "If those two
  documents ever disagree, this one is correct."
- Superseded Reddit posts are preserved verbatim under
  `docs/archive/<YYYY-MM>_<app>-reddit-post.md` with an "ARCHIVED for
  transparency" banner (status + why + link to the current write-up +
  "Everything below this line is preserved verbatim"). Done for Prime Video,
  Netflix, and Pluto TV (PRs #199/#200). Archive to preserve history; never
  silently delete an old post.

**Browsing the web (Playwright MCP)**
- `.mcp.json` registers a `playwright` MCP server (`scripts/playwright-mcp.sh`,
  pinned `@playwright/mcp` version) so Claude can drive a real headless browser:
  read JS-rendered pages, click through sites, take screenshots. Use it for
  gists, upstream projects, forum threads and release pages the user points at.
- In Claude Code on the web, `.claude/hooks/session-start.sh` makes Chromium
  trust the egress proxy's CA (imports `/root/.ccr/agent-proxy-ca.crt` into
  `~/.pki/nssdb`). Without it every page fails with
  `ERR_CERT_AUTHORITY_INVALID`. Never "fix" that with `--ignore-https-errors`.
- A browser does **not** get around the cloud environment's network policy.
  `ERR_TUNNEL_CONNECTION_FAILED` means the policy blocked the host (e.g.
  `apkmirror.com`, `gist.githubusercontent.com`, `reddit.com`); `github.com`
  pages outside this repo return 403. Ask the user to allow the host in the
  environment's Network access settings. For gists, `gist.github.com/<user>/<id>`
  pages are reachable even by `curl` (the file text is in the page HTML), while
  the API and `gist.githubusercontent.com` raw URLs are not.
- Three separate layers, don't confuse them: the **GitHub MCP** (`mcp__github__*`)
  is GitHub API only (PRs, issues, checks, merges) and has nothing to do with
  browsing; the **Playwright MCP** can drive a browser but is still bound by the
  **network policy**, which is the actual gate. Fixing GitHub access does not open
  general web access.
- The network access level lives in the **cloud environment menu (session title
  bar) → Edit → Network access**. `Full` allows all hosts, but the edit dialog
  says changes **apply only to new sessions** — the current session keeps the
  policy it started with, so a host newly allowed (or `Full` newly set) only
  takes effect in a fresh session.
- **Reddit is unreliable to read even when allowed** (logged-in content, API
  friction). For refreshing Reddit posts, working from user-pasted **screenshots**
  is the dependable path, not fetching the live post.

**Diagrams**
- For diagrams inside Markdown (docs, READMEs, gists), prefer ```` ```mermaid ````
  blocks. GitHub and gists render them natively.
- For interactive diagrams, use the vendored **archify** skill
  (`.claude/skills/archify/`, pinned and reviewed; see its `VENDORED.md`).
  Specs and output live in `docs/diagrams/`. Gists can't run HTML, so embed the
  preview PNG via a `raw.githubusercontent.com` URL and link the interactive
  HTML on GitHub Pages
  (`https://ajstrick81.github.io/morphe-androidtv-patches/diagrams/<name>.html`,
  served from `main` `/docs`).
- To preview a local HTML file in the Playwright browser, serve it with
  `python3 -m http.server --bind 127.0.0.1` (`file:` URLs are blocked).

**Building**
- A local Gradle build needs a GitHub token for the Morphe GitHub Packages
  registry (`gpr.user`/`gpr.key` or `GITHUB_ACTOR`/`GITHUB_TOKEN`). Without
  one, push the branch and let CI build it (CI runs on every non-`main`
  push and on PRs).
