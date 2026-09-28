# HANDOFF — Tubi "Block analytics & tracking" patch: install and test on the Onn

_State as of 2026-09-26. Written by the cloud session that built the patch, for a
local Claude Code session on the PC that has the Onn TV and `E:\Morphe`._

## Device test results (2026-09-26) — PASSED after a fix

Onn 4K Plus "coffey" (Android 14, `192.168.12.210`), Tubi **10.28.5000**
(APKMirror `.apkm`), installed in place with `adb install -r` (same Morphe key,
so the sign-in was kept).

**First run (commit `b8d0fef`) crashed at launch** with Skip ads + Block analytics
both enabled:

```
java.lang.VerifyError: Verifier rejected class Po.C$c: WebResourceResponse
Po.C$c.shouldInterceptRequest(WebView, WebResourceRequest): invalid branch target -95 (-> 0x7) at 0x66
```

Cause: both patches prepend code to `Po/C$c.shouldInterceptRequest`. A branch to an
internal smali label keeps the address it had at insert time, so when Skip ads
prepended its block after this patch, the privacy branch landed mid-instruction.
Each patch works on its own (confirmed: privacy-only build launched and played),
and CI only compiles, so neither caught it.

**Fix (commit `ecd9f58`):** both WebView blocks now branch only to the method's
original first instruction via `ExternalLabel`; Skip ads ORs its host matches into
one register instead of jumping to an internal label. Disassembly of the combined
method confirmed every branch lands on an instruction boundary.

**Retest with both patches:**

| Check | Result |
|---|---|
| 1–3: listed/off by default, applies cleanly, 6 manifest switches once each | ✅ |
| Cold launch | ✅ |
| Sign-in kept | ✅ |
| TV show and movie playback, ads removed | ✅ |
| Pause, resume, seek | ✅ |
| Continue watching after relaunch | ✅ history unaffected by the `analytics-ingestion` block |
| Live TV | ✅ no delay, clean playback |
| Crash / ANR / VerifyError | none |

Blocked hosts seen (deduplicated, across runs):
`okhttp` → `analytics-ingestion-v3.main-production-custom.production.k8s.tubi.io`,
`analytics-ingestion.production-public.tubi.io`, `secure-gl.imrworldwide.com`;
`urlconnection` → `sdk.iad-01.braze.com`; `webview` → both `analytics-ingestion` hosts.

**Lesson for the rollout to other apps:** when a new hook prepends code to a method
another patch already hooks, don't use internal labels, and device-test with both
patches enabled.

Minor harness note: `testing/scripts/build.sh`'s patch listing calls `list-patches`
without `--patches=` and fails; the build itself is fine.

## Your job

Build this branch, patch Tubi **10.28.5000** with the new opt-in patch, install
it on the Onn TV, and report whether it works. **Test and report only.** Don't
change patch code, push, open a PR or merge unless the user asks. If something
fails, capture the evidence (section 6) and stop.

## 1. What changed and why

Branch: **`claude/sleepy-ramanujan-52uk53`** (CI green, run 472, commit `b8d0fef`).

A privacy study of the streaming apps we patch found no audio fingerprinting
(ACR), but plenty of tracking. Tubi was the heaviest: Adjust, Branch, Braze,
Nielsen, Conviva, NPAW/Youbora, Mux, Firebase/Google Analytics and Meta App
Events. Tubi's own analytics endpoint also receives the advertiser ID, device ID
and ZIP/location fields. The new patch **"Block analytics & tracking"** (Tubi
only, **default off**) stops them on the device:

| Layer | What it does | Where |
|---|---|---|
| Manifest | Sets the SDKs' own kill switches: `firebase_analytics_collection_deactivated=true`, `google_analytics_ssaid_collection_enabled=false`, `google_analytics_adid_collection_enabled=false`, `google_analytics_default_allow_ad_personalization_signals=false`, `com.facebook.sdk.AutoLogAppEventsEnabled=false`, `com.facebook.sdk.AdvertiserIDCollectionEnabled=false` | `patches/.../tubi/privacy/BlockAnalyticsPatch.kt` |
| Adjust | `Adjust.initSdk` / `Adjust.onCreate` return immediately, so Adjust never starts | same, `Fingerprints.kt` |
| Host blocklist | `TrackerBlocker` rejects tracker hosts at three places: every `URL.openConnection()` call site (rewritten app-wide), every OkHttp client (`OkHttpClient(Builder)` constructor), and the web app's WebView `shouldInterceptRequest` | `extensions/.../tubi/privacy/TrackerBlocker.java` |

Blocked hosts (the host itself or any subdomain): `adjust.com adjust.io
adjust.world adjust.net.in branch.io braze.com braze.eu appboy.com conviva.com
imrworldwide.com nielsen.com npaw.com youbora.com gnsnpaw.com litix.io
app-measurement.com google-analytics.com`, plus Tubi's first-party
`analytics-ingestion*` hosts on `tubi.io` / `tubitv.com`.

**Deliberately not blocked:** crash/performance reporting (Sentry, Crashlytics,
Firebase Performance), Statsig feature flags, OneTrust consent, Facebook sign-in
(graph API), and all ad, playback and DRM hosts. A blocked request fails the same
way it would with no internet.

**What's verified vs. not:**
- It compiles (CI).
- The hook points were checked against Tubi **10.36.5000** (Android TV build),
  not 10.28.5000. They use un-obfuscated SDK/OkHttp names, so they should match,
  but only this test proves it.
- It hasn't been run on a device. That's your job.

## 2. Setup

Use the repo's harness in `testing/` (read `testing/README.md` once). On this
PC, earlier handoffs used Git Bash paths like `/d/Tools/...`, and the user keeps
APKs at **`E:\Morphe\apks`** (Git Bash: `/e/Morphe/apks`) and decompiles at
`E:\Morphe\decompiled`.

```bash
git fetch origin claude/sleepy-ramanujan-52uk53
git checkout claude/sleepy-ramanujan-52uk53
git pull
```

- The Gradle build needs a GitHub token for the Morphe registry
  (`GITHUB_ACTOR`/`GITHUB_TOKEN`, or `gpr.user`/`gpr.key` in
  `~/.gradle/gradle.properties`). If it's missing, ask the user; never commit it.
- `testing/config/device.env` holds the Onn's IP (gitignored). The ESPN handoff
  used `192.168.12.211`; confirm with the user if `adb connect` fails.
- Find the Tubi **10.28.5000** Android TV APK/APKM in `E:\Morphe\apks`. If only
  another version is there, stop and ask; the patch's compatibility is pinned to
  10.28.5000.

```bash
cd testing
./scripts/setup.sh --check          # java, adb, CLI and APKEditor present?
adb connect <onn-ip>:5555
./scripts/build.sh                  # builds out/patches-local.mpp and lists patches
```

**Check 1:** the `build.sh` listing (or
`java -jar tools/morphe-cli.jar list-patches --with-options out/patches-local.mpp`)
shows **"Block analytics & tracking"** under Tubi, and it's off by default.

## 3. Baseline run (without the new patch)

This gives a comparison point. Skip it if the user already runs a known-good
patched Tubi and says so.

```bash
./scripts/patch.sh tubi /e/Morphe/apks/<tubi-10.28.5000 file>
./scripts/deploy.sh tubi
```

Play one movie for a minute and note anything odd. If the user runs AdGuard
Home or private AdGuard DNS, note which tracker domains show up in its query log
for the TV (see section 5).

## 4. Test run (with the new patch)

`-e` adds a patch to the default set (which includes **Skip ads**). The Morphe
CLI silently ignores a misspelled `-e` name, so copy it exactly.

```bash
./scripts/patch.sh tubi /e/Morphe/apks/<tubi-10.28.5000 file> -e "Block analytics & tracking"
./scripts/deploy.sh tubi
./scripts/logs.sh tubi 'MORPHE-TUBI-PRIVACY|FATAL|VerifyError|AndroidRuntime'
```

`deploy.sh` uninstalls the existing Tubi first (signatures differ), so the user
has to sign in again if they use an account.

**Check 2:** the patch applied cleanly. The CLI output shows it succeeded with
no "failed" line for "Block analytics & tracking".

**Check 3:** the manifest switches landed:

```bash
java -jar tools/APKEditor.jar d -i out/tubi-patched.apk -o out/tubi-dec -t xml
grep -E 'firebase_analytics_collection_deactivated|ssaid_collection|adid_collection|ad_personalization|AutoLogAppEvents|AdvertiserIDCollection' out/tubi-dec/AndroidManifest.xml
```

Expect the six names from section 1 with the values listed there.

## 5. What to check on the TV

Work through these with the user watching the screen. Keep the log running.

| # | Check | Pass looks like |
|---|---|---|
| 1 | Cold launch | Home screen loads; no crash, no endless spinner |
| 2 | Sign in (if the user has an account) | Works; profile/"My List" loads |
| 3 | Browse and search | Rows, artwork and search results load |
| 4 | Play a movie | Starts; ads still removed (Skip ads); 5+ min without errors |
| 5 | Pause, resume, seek | Behave normally |
| 6 | Live TV channel | Plays |
| 7 | Continue watching | After exit and relaunch, the movie shows in "Continue watching" at the right spot |
| 8 | Log evidence | `MORPHE-TUBI-PRIVACY` lines like `blocked okhttp -> …`, `blocked urlconnection -> …`, `blocked webview -> analytics-ingestion…`. Each host is logged once per layer |
| 9 | No crashes | No `FATAL EXCEPTION`, `VerifyError` or `AbstractMethodError` in the log |

Check 7 matters most for the first-party analytics block. If watch progress is
lost, that points at `analytics-ingestion` being needed for history.

**Optional DNS cross-check.** Blocking happens before any DNS lookup. With the
patch, the TV's DNS log (AdGuard Home / private AdGuard DNS) should stop showing
queries from Tubi for the blocked hosts above, while `tubitv.com` content and
playback hosts still appear. That confirms blocking from outside the app.

## 6. If something breaks

- **Crash at launch with `VerifyError`:** most likely the injected call at the
  top of the `OkHttpClient(Builder)` constructor, or an `openConnection` rewrite.
  Save the full stack trace (`adb logcat -d > tubi-crash.txt`).
- **"Block analytics & tracking" failed to apply:** save the CLI output. A
  fingerprint didn't match 10.28.5000. Note which one.
- **Something specific stopped working** (sign-in, history, a row):
  `adb logcat -d | grep MORPHE-TUBI-PRIVACY` shows which hosts were blocked
  around that moment. Note the host.
- **Roll back:** re-run section 3 (patch without `-e "Block analytics & tracking"`)
  and deploy. That's the previous behaviour.

Don't try to fix the patch in this session unless the user asks. Report instead.

## 7. Report back

Give the user a short report they can paste to the cloud session or into a PR:

- Tubi file used (name and version), Onn model and Android version
- Checks 1–3 and table rows 1–9: pass/fail each, one line of detail for failures
- The distinct `blocked … -> host` lines seen (deduplicated)
- Any crash trace or failure evidence (attach the saved files)
- DNS cross-check result, if done

Housekeeping: never commit APKs, `out/`, `tools/`, keystores or `device.env`
(see `CLAUDE.md` and `testing/.gitignore`).
