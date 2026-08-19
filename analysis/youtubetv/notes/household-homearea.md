# YouTube TV — household / profile-gate / home-area analysis

Question: does YouTube TV have a suppressible client-side gate like the
Disney+ / Netflix household-overlay prompts this repo already patches?

**Short answer: not in the same way.** YouTube TV has TWO gates, but both are
**server-authoritative** (InnerTube-driven), not client-side boolean checks —
so neither offers the clean "no-op the show-prompt method" lever that the
Disney/Netflix household prompts did.

## 1. "Who's watching" profile gate — server-rendered browse endpoint

Found in smali as enum values, not a client gate:
- `WHOS_WATCHING_BROWSE_SERVICE_REQUEST` (aigy) — a **browse request type**.
- `WORK_MANAGER_TASK_TAG_WHOS_WATCHING_STORE_MANAGER` (bhxc) — a background
  store-sync task tag.

The "Who's watching" screen is a **server-rendered browse response** (InnerTube),
reached by issuing a browse request — the client renders whatever the server
returns. There is no local "show profile picker" boolean to flip; suppressing it
would mean intercepting/rewriting a navigation endpoint, not neutering a method.

## 2. Home-area enforcement — the one the user actually sees (CONFIRMED)

This is the "you're not in your home area / update your home area" gate. It is a
whole **client subsystem that reports to and obeys the server**, not a local
check:

Client pieces (obfuscated but named):
- `.../unplugged/location/dma/impl/HomeLocationZipCodeAvailabilityCheckerImpl`
  — client resolves the device DMA / zip code.
- `HomeLocationService` — `beginUpdate` / `checkAvailability` / `completeUpdate`
  RPCs (client ↔ server home-area updates).
- `HomeLocationCommandResolver`,
  `UnpluggedCheckZipCodeCommandHandler`,
  `UnpluggedCheckZipCodeElementsCommandResolver` — handlers for **InnerTube
  commands the SERVER sends** telling the client to (re)check its location.
- flags `geofences_with_callback`, `home_location_check_availability` — client
  geofencing that feeds the server.

Enforcement delivery:
- Playback is gated by **`playabilityStatus`** (`PlayerInterstitialData{
  playabilityStatus=...}`, `initialPlayabilityStatus`,
  `remotePlayabilityStatusParams`). When the server decides you're out of home
  area, it returns a non-OK `playabilityStatus` with the interstitial/error, and
  **withholds the stream URLs**. The client just renders it.

### Why there's no clean Disney/Netflix-style lever

- Disney+/Netflix household prompts had a **client-side decision** ("show the
  household overlay") we could no-op. YouTube TV's home-area result is
  **decided server-side** (IP geo + the client's reported zip/geofence) and
  enforced by `playabilityStatus` + withheld media. Forcing `playabilityStatus`
  to OK on the client typically yields an **empty/broken player** (the server
  never sent the stream), not free content.
- The only client-side surface is the **geofence / zip-code reporting**
  (`HomeLocationZipCodeAvailabilityCheckerImpl`, `HomeLocationService`,
  `geofences_with_callback`). Feeding a consistent in-home DMA/zip is the *only*
  conceivable client lever — but the server cross-checks against your **IP-based
  geolocation**, so client-reported location alone almost certainly won't beat
  it. (This is the same class of server-trust problem as the ad-blocker
  enforcement: the authority is the server, not the binary.)

### Research candidate (NOT a quick patch)

If pursued later, the honest path is the geofence/DMA reporting surface —
`HomeLocationZipCodeAvailabilityCheckerImpl` / `HomeLocationService` — pinned to
a fixed home DMA, tested on-device to see how much the server trusts
client-reported location vs. IP. Expectation: low success without also
controlling the network egress IP (VPN/residential-IP at the home location),
which is a network solution, not a bytecode patch. Documented here so the
"is there a lever?" question is answered and the dead-end is recorded rather
than re-explored.
