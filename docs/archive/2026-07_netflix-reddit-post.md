> # 🗄️ ARCHIVED — Historical Reddit announcement (July 2026)
>
> **Status: superseded. Kept for transparency, not for installation.**
>
> This is the original Reddit post that introduced the Netflix Android TV patch.
> It is preserved here so the project's history is visible even after the live
> post is updated.
>
> **Why it's archived:** the patch has kept moving since this went up. The biggest
> thing this post does not mention is **drift**: Netflix's ad logic is downloaded
> JavaScript that Netflix re-minifies server-side without an app update, so ads can
> briefly return until the patch re-anchors. The shipping patch
> now carries rename-tolerant fallbacks for exactly that (issue #166). Otherwise
> the mechanism described below — an ad-free clone that keeps stock Netflix
> installed, killing pre-roll, mid-roll and pause ads in-process — still matches.
>
> **➡️ For the up-to-date write-up and install steps, read
> [`docs/NETFLIX_ANNOUNCEMENT.md`](../NETFLIX_ANNOUNCEMENT.md), the full teardown in
> [`docs/NETFLIX_ATV_SYSTEM_DESIGN.md`](../NETFLIX_ATV_SYSTEM_DESIGN.md), and the
> [repo README](../../README.md).**
>
> Everything below this line is preserved as it was originally posted.

---

# Presenting: The Netflix Android TV Patch for Onn 4K TV/Google TV

## Hey everyone!

**INTRO:** I'm a huge fan of this community and all the hard work the developers
have put into making Morphe an awesome success. I've been working hard on various
patches thanks to being able to learn from the best (**RookieEnough, Hoodles,
Paresh**) and give back to the community.

I'd like to take a moment to acknowledge that I am not a coder. I work in
healthcare and have a decent idea of how things work, but I am in no way
experienced in bytecode patching. I've learned how to use the expertise of LLM and
teach it to understand the complexities of thousands of lines of code and to
reverse engineer what companies spend millions of dollars developing. I don't have
years to study and learn everything and these patches are in need **now**.

**So fair warning:** I've deployed Claude (scary!!!) to help me build my GitHub
repo, and I've leaned on AI extensively to problem-solve and write the code — this
Netflix patch included. I know an AI-assisted patch isn't what everyone wants, but
hey, it's your choice: an AI-generated patch for ad-free Netflix, pay for a Netflix
ad-free plan, or watch Netflix with ads. I know which one I'd choose.

And to be clear up front: this is **NOT** about getting Netflix for free. You need
a valid, paid account and you log in normally — bypassing subscriptions, paywalls,
or DRM is out of scope. This is purely about removing ads and trimming device
telemetry inside an app you're already paying for.

---

## TL;DR:

A Morphe patch that installs an ad-free **clone** of the real living-room Netflix
app on Android TV. Kills pre-rolls, mid-rolls, and the pause-screen ad overlay,
plus an optional privacy toggle. No root, no proxy, no DNS/VPN. **You keep stock
Netflix installed** — the patched clone needs it there (explained below). On-device
verified on an Onn 4K.

---

## What it does

- ✅ **Pre-roll ads** (before playback) — gone
- ✅ **Mid-roll ads** (during a show/movie) — gone
- ✅ **Pause-screen ad overlay** (the full-screen ad when you pause) — gone
- ✅ Playback, resume-to-position, and seeking all work normally
- 🔒 **Optional privacy toggle** ("Minimize Network Fingerprint") — blanks the
  local IP / MAC / Wi-Fi SSID the app reports, and stops it from reporting your
  advertising ID (GAID) in its telemetry. Off by default.

This is the **actual TV app** (`com.netflix.ninja`), not a phone app running on
your TV.

---

## Why there are TWO apps (please read — this trips people up)

You end up with **stock Netflix** & an **"ad-free" Netflix clone** installed side
by side. Both must stay installed. Here's why:

- Netflix on a TV is a **system app** that's cryptographically signed by Netflix.
  You **CANNOT** patch it in place, and you **CANNOT** uninstall it without root. So
  the patch installs as a **separate app** with its own name.
- The clone is protected by a **native anti-tamper check** that reads Netflix's
  original signature. The clone passes this check by *pointing at the stock app and
  borrowing its genuine signature*. **WARNING: Remove or disable stock Netflix and
  the clone will refuse to start.**
- The clone reads stock's **signature, not its version** — so it's totally fine to
  let stock Netflix **auto-update** in the background. It won't break the clone.

**Bottom line:** Keep stock Netflix installed and enabled. You just never open it —
you open the clone instead. Log into the clone once and you're set.

---

## Install

1. **Add the repo to Morphe:** https://morphe.software/add-source?github=ajstrick81/morphe-androidtv-patches
2. In Morphe, select **Netflix** and apply the patch set:
   - **Remove Netflix ads** — the ad kills (on by default)
   - **Clone Netflix** — required; makes it installable alongside stock
   - **Minimize Network Fingerprint** — optional privacy toggle (off by default)
3. Make sure to toggle **"off"** "Optimize for Device Architecture".
4. Install the patched APK it produces. **Do not uninstall stock Netflix.**
5. Use **Send Files to TV** to transfer the cloned apk to your ATV device.
6. Open the new **Netflix Clone** app, sign in, and use it like normal.
7. Enjoy Ad-free Netflix! :)

---

## FAQ

**Do I need root?** No. No root and no proxy. Your ATV device plays it seamlessly
without ads.

**Do I need a VPN or a DNS blocker (AdGuard/Pi-hole)?** No. The ads are removed
*inside the app*, not by blocking network requests.

**Will this stop the "you're traveling / not in your household" prompt?** No.
That's based on your public IP address, which Netflix's servers see no matter what
the app does. Only a VPN/proxy changes that. This patch is about ads and device
fingerprinting, not household enforcement.

**Does the privacy toggle make me anonymous?** No — be realistic. Netflix still
knows who you are (you're logged in) and what you watch (it streams it to you). The
toggle trims *granular device analytics* (local network details, advertising ID).
It's a reduction, not a cloak.

**Will Netflix updating break it?** No. Let stock Netflix update freely. If a future
Netflix version changes the internals enough that ads come back, the patch
fingerprints get refreshed and you rebuild.

**Two Netflix icons is annoying — can I hide stock?** You can move it out of the
way, but **don't disable or uninstall it** — the clone needs it present to start.

**Is my login safe / is this a fake app?** It's the real Netflix APK, re-signed and
cloned locally by you with Morphe. It talks to Netflix's real servers with your
real account. Nothing is proxied through a third party.

**Does this get me Netflix for free / bypass the subscription?**

**No — and that's not what this is.**

You need a valid, paid Netflix account and you log in normally. This patch only
changes how ads and device telemetry are handled *inside* an app you're already
entitled to use. Bypassing paywalls, subscription checks, DRM, or any access control
is explicitly **out of scope** for these patching projects — don't ask for it and
don't expect it here.

---

## Verified

Tested on an Onn 4K (Android 14, Netflix 13.0.1). Across repeated runs the Netflix
servers **did** deliver real pre-rolls, mid-rolls and pause ads — and none of them
played, with no playback errors and resume position intact. A full technical
write-up of how it works is below:

[Technical Breakdown](https://github.com/ajstrick81/morphe-androidtv-patches/blob/main/docs/NETFLIX_ATV_SYSTEM_DESIGN.md)
