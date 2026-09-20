# ESPN Ad-Break Scoreboard — Schema Reference & `eventId` Seam

Reference for the live-game scoreboard strip on the ESPN commercial-break slate
(`EspnAdBreakOverlayHelper`). Covers (1) the data model we render, (2) where the
**exact** playing-game identity comes from inside the ESPN app, and (3) the public
ESPN endpoints that fill it in.

> ## Credit / provenance
> The scoreboard **data-model shape** below (a flat, sport-agnostic
> `Linescore` / `TeamSide` / `GameStatus` split) was **borrowed from and inspired
> by the Tivra TV Android app** (a Flutter IPTV client, `api.tivratv.com`), whose
> `SportsScoreboard` module we studied for its clean progressive-disclosure and
> spoiler-safe UX. We reimplemented the *idea*, not their code — nothing from Tivra
> is copied or bundled. Thanks to their team for a genuinely well-built reference.
> ESPN endpoint shapes are courtesy of the community hidden-API docs already
> credited in `EspnAdBreakOverlayHelper.startScoreStrip`
> (pseudo-r/Public-ESPN-API, akeaswaran's gist, cwendt94/espn-api, et al.).

---

## 1. Why this exists — the "match the game" problem

The break slate needs to show *the score of the game the viewer is actually
watching*. The old strip guessed: it scanned a league's scoreboard and showed the
**first in-progress game** (`fetchScoreLine` → `state == "in"`), which is wrong
whenever more than one game is live. The fix is to key off the **real event id**
the app already holds.

## 2. The `eventId` chain (verified in ESPN `com.espn.score_center` 6.11.1)

The canonical ESPN event id is carried all the way from the WatchESPN SDK into the
player's program model:

```
com.espn.watchespn.sdk.Airing.eventId : Long        ← canonical ESPN scoreboard event id
  └ com/espn/video/dmp/utils/ExtensionsKt.getEventId(GraphResult)   →  airing.eventId.longValue().toString()
      └ com/espn/video/dmp/model/ProgramDataFactory                 →  builds ProgramData
          └ com/espn/video/dmp/model/ProgramData.eventId : String
              └ MediaPlayerViewModel emits it via programDataFlow (MutableSharedFlow)
```

`getEventId` literally reads `Airing.eventId` — the **same integer ESPN's own
scoreboard/summary API uses** — so no title/time fuzzy matching is needed.

### `ProgramData` fields (all confirmed present in 6.11.1)

| Field (getter) | Type | Use |
| --- | --- | --- |
| `eventId` (`getEventId`) | `String` | **the key** — ESPN scoreboard event id |
| `leagueName` (`getLeagueName`) | `String` | league label (map → API league slug) |
| `sportName` (`getSportName`) | `String` | sport label (map → API sport slug) |
| `title` (`getTitle`) | `String` | e.g. "Patriots at Seahawks" |
| `subtitle` (`getSubtitle`) | `String` | secondary line |
| `isLive` (`getIsLive`) | `boolean` | live vs VOD |
| `isLinear` / `networkId` / `programCode` / `videoId` / `videoType` / `duration` / `progressData` | — | other playback metadata |

### The hook

We already hold `MediaPlayerViewModel` (injected at
`attachSession`). The sibling seam that carries `ProgramData` **directly, as a
non-suspend static arg**, is the side-effect lambda that fires when the stream
title becomes available:

```
MediaPlayerViewModel.createAndEmitProgramData$lambda$1(ProgramData p0, MediaPlayerViewState p1) : MviSideEffect
```

Inject at offset 0:

```smali
invoke-static {p0}, Lajstrick81/morphe/extension/espn/ads/EspnAdBreakOverlayHelper;->onProgramData(Ljava/lang/Object;)V
```

⚠️ `$lambda$1` is an R8-generated name that drifts across versions — match it
**structurally** (method in `MediaPlayerViewModel` whose params are
`(ProgramData, MediaPlayerViewState)` returning `MviSideEffect`, referencing the
`MediaPlayerSideEffect$StreamTitleAvailable` type), the way the other ESPN
fingerprints self-validate and fail loud. See `espn/Fingerprints.kt`.

## 3. Rendering model (the Tivra-inspired shape)

Kept deliberately minimal — a single-game strip, not a full hub (ESPN's native UI
already does standings/box scores three feet away):

```
Scoreboard
 ├─ TeamSide home   { shortName, score, record?, winner?, logoUrl? }
 ├─ TeamSide away   { shortName, score, record?, winner?, logoUrl? }
 ├─ GameStatus      { state: pre|in|post, detail, shortDetail, period, clock, completed }
 └─ Linescore       { rows: [ periods:[int]  per team ] }   // "SCORE BY PERIOD"
```

**Spoiler-safe** (Tivra's signature idea): keep a `hidden` toggle, but **default
to visible** on ESPN — the viewer deliberately opened this one live game, unlike
Tivra's lean-back channel-surf context.

## 4. ESPN public endpoints (no auth)

Given `eventId` (+ sport/league slug):

- **Summary (single call, preferred on-device):**
  `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/summary?event={eventId}`
  Flattens header → competitors, linescores, status into one response.
- **Scoreboard (already used by the strip):**
  `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard`
  — each `events[].id` lets us **match our `eventId` exactly** (drop-in upgrade to
  the current first-in-progress guess; needs no sport/league mapping if we scan
  the configured leagues and match by id).
- **Core API (HATEOAS, many round-trips; good for grounding):**
  `https://sports.core.api.espn.com/v2/sports/{sport}/leagues/{league}/events/{eventId}`

### Real-response field names (grounded 2026-09-15 against core.api, event 401872656 "NE @ SEA")

```
competitors[] : { homeAway, winner, team{$ref}, score{$ref}, linescores{$ref}, record{$ref}, leaders, curatedRank }
score         : { value: 13.0, displayValue: "13" }
linescores[]  : { value, displayValue, period }          // one per quarter/inning/period
team          : { abbreviation:"SEA", shortDisplayName:"Seahawks", displayName, color, logos[] }
status.type   : { state:"pre|in|post", completed:bool, detail:"Final", shortDetail:"Final" }
status        : { period:4, displayClock:"0:00" }
```

> ⚠️ Network note: ESPN 403s browser/default-Java UAs — `httpGet` already sends
> `okhttp/4.9.0`, which answers 200. `site.api.espn.com` may also refuse
> datacenter IPs (it 403s from this dev box) but is reachable from the TV's home
> network; `sports.core.api.espn.com` answered 200 even from the dev box.

## 4b. Break-slate UI (as built)

Rendered as a full-screen **WebView card** (`SlateMode.SCORECARD`) built from an
HTML string — team logos load straight from `a.espncdn.com`, nothing bundled.
The break-slate picker is trimmed to **two options: Scoreboard and Blank**, and
Scoreboard is the default.

**Rotation:** the card shows **your game first** (pinned, matched by
`ProgramData.eventId`), then rotates through every other in-progress game in the
league every ~8s (`CARD_ROTATE_MS`), looping. Scores re-fetch every ~2 rotations
(~16s). A dot row shows position in the rotation. If the exact game isn't found it
falls back to rotating all live games.

## 5. Status

Recon + schema complete; helper `onProgramData` seam and structural fingerprint
prototyped. On-device verification (exact-game match during a real multi-game
window on `.211`) still pending.
