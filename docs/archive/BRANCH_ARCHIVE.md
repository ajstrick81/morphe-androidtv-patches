# Branch archive (cleanup of 2026-09-23)

On 2026-09-23 the repo's 32 stale branches were archived as git tags and then
deleted, leaving `main` as the only long-lived branch. **No commit was lost:**
every branch tip lives on as the tag `archive/<branch-name>`.

Any reference material on those branches that had never reached `main`
(docs, analysis notes, probes, runbooks, toolkits) was copied into `main` in
the same cleanup. Abandoned patch *code* (patches that never shipped, or were
deliberately removed) was **not** copied, so it can't end up in a release. It
stays in the tags below.

## Restoring a branch

```sh
git fetch origin 'refs/tags/archive/*:refs/tags/archive/*'
git push origin archive/<branch-name>:refs/heads/<branch-name>   # recreate on GitHub
# or just look around locally:
git checkout archive/<branch-name>
```

On GitHub you can also browse any tag from the branch/tag picker.

## Archiving more branches later

The monthly **Stale branch report** workflow opens (or updates) an issue
labelled `stale-branches` that lists branches with no commits in 30+ days.
To archive some, run Actions → **Archive branches** with their names
(`dry-run` first, then `execute`), then add them to the tables below.

## Why the old branches looked "hundreds of commits ahead"

`main`'s history was rewritten on 2026-08-28. Its oldest commit is
`fix(paramount): PTS-align live sports slate…` (#139). Branches created before
then share no ancestor with the new `main`, so GitHub showed them as 600–900
commits ahead and marked many older PRs "closed, not merged" even though their
content had landed. Before deleting anything, every file on those branches was
compared against `main`. Everything that was on the pre-rewrite `main` is on the
current `main`.

## What each archived branch held

**Research/reference.** The unique content was copied into `main`:

| Tag `archive/…` | Last commit | Copied into `main` |
|---|---|---|
| `claude/prime-video-continuation-kaplat` | 2026-07-23 | `experimental/native-adstrip-toolkit/`, `experimental/primevideo-research/2026-07-23_continuation/` |
| `claude/primevideo-interception-handoff-pxhsn0` | 2026-07-25 | `experimental/primevideo-research/2026-07-25_interception-handoff/` |
| `claude/scte35-youtube-tv-signaling-vyy0cy` | 2026-08-22 | `docs/SCTE35_AD_SIGNALING_REFERENCE.md`, `docs/YOUTUBE_TV_*`, `experimental/youtubetv-scte35/`, `testing/youtubetv-capture-runbook.md` (the YouTube TV patch code stays in the tag) |
| `claude/youtube-tv-apkm-decompile-hb84gr` | 2026-08-19 | `analysis/youtubetv/` (YouTube TV patch code stays in the tag) |
| `claude/powershell-atv-youtube-upload-0m7mg2` | 2026-08-19 | `analysis/youtube-tv/711300320/*.md` (the APK split and patch code stay in the tag) |
| `claude/onn-usb-install-method-zr8qd7` | 2026-08-24 | `docs/ONN_WAYNE_RTD1325_ROOT_GUIDE.md`, `testing/onn-wayne/` |
| `claude/twitch-ad-blocking-techniques-x0pi28` | 2026-08-14 | `docs/twitch-ad-delivery-map.md`, `experimental/twitchatv-ad-probe/` |
| `claude/netflix-household-android-tv-bou10v` | 2026-08-23 | `experimental/netflix-household-loadhook/lolomo_probe.js`, `experimental/netflix-native-adstrip/frida/ad_drift_probe.js` |
| `claude/paramount-patch-install-guide-65v2vk` | 2026-08-23 | `experimental/live-dai-probe/`, `testing/scripts/{capture-dai.sh,parse-dai-probe.py}` (`DaiProbePatch.kt` stays in the tag) |
| `claude/toolkit-cross-apk-compat-ku719r` | 2026-07-23 | `testing/capture-runbook.md`, `testing/scripts/{capture.sh,analyze_pcap.py}` |
| `claude/morphe-cli-test-env-di231x` | 2026-07-02 | `docs/patching-techniques.md` |
| `claude/morphe-attribution-tracking-qcwmug` | 2026-07-23 | `docs/REFERENCES.md` |
| `claude/spotilol-project-research-tgjtzh` | 2026-07-29 | `docs/ADJACENT_PROJECTS.md` |
| `docs/netflix-loadhook-abi` (PR #176) | 2026-09-15 | Merged: `experimental/netflix-household-loadhook/` ABI decode |
| `experiment/paramount-slate-manifest-continuity` | 2026-08-28 | Merged: `experimental/paramount-slate-continuity/` (failed experiment, kept as a record) |

**Abandoned or superseded code.** Nothing copied; the tag is the record:

| Tag `archive/…` | Last commit | Notes |
|---|---|---|
| `feat/primevideo-phone-on-tv` | 2026-07-26 | Prime Video *phone* app on TV (D-pad controls, density, VMAP/AdPlan skips). Never shipped |
| `claude/purple-tv-android-tv-ke9wrl` | 2026-07-28 | Twitch phone-app ad patch, cross-checked against Purple TV. The repo went ATV-only (#128) |
| `claude/twitch-atv-webview-adblock` | 2026-08-11 | Twitch ATV WebView ad-block (PR #101, landed). Its only files not in `main` are the old Twitch phone patch, removed on purpose in #128 |
| `claude/optimistic-cannon-a521v7` | 2026-08-20 | Twitch ATV greeting/attribution tweak. Its only files not in `main` are the removed Twitch phone patch (#128) |
| `feat/hbomax-strip-stitched-ad-periods` | 2026-08-26 | HBO stitched-period strip. Superseded by the ad-free FALLBACK stream (#137) |
| `claude/intelligent-edison-67tjpu` | 2026-06-23 | Early MLB "Commercial Break" overlay |
| `claude/upbeat-heisenberg-noys1i` | 2026-07-07 | MLB manifest marker census |
| `claude/fork-analysis-java-94e5dn` | 2026-07-06 | Prime Video DNS rules. The DNS layer was removed as obsolete (#79) |
| `claude/readme-tubi-apk-info-y1lsvw` | 2026-07-20 | Tubi README bump (landed via #69) plus old DNS files |
| `claude/vix-apk-patch-analysis-xrhhqe`, `feat/vix-4.47.2-support` | 2026-07-12 | ViX 4.47.2 support (landed via #54) |

**Already fully in `main`.** Nothing unique:

`dev` (old prerelease channel, last used 2026-06-26),
`claude-push-test-1782171992`, `chore/espn-readme-and-desc`,
`feat/espn-live-slate`, `fix/paramount-harden-fingerprints-1619`,
`fix/paramount-slate-revert-hotpath`.
