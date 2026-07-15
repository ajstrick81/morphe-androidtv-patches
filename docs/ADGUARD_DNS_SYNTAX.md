# AdGuard DNS rule syntax — house reference

Reference for maintaining the filter lists in [`dns/`](../dns/). Validated by
[`tools/lint-dns-rules.mjs`](../tools/lint-dns-rules.mjs) on every PR (`npm run lint:dns`).

Upstream: <https://adguard-dns.io/kb/general/dns-filtering-syntax/>

## The one that bites: two different engines

"AdGuard" is three products with overlapping-but-unequal rule syntax:

| | What it is | Our use |
|---|---|---|
| **AdGuard Home** | Self-hosted resolver | **This is what `dns/*.txt` targets** |
| **AdGuard DNS** | Hosted service, rules in their Dashboard | Not used by this repo |
| **AdGuard (browser ext.)** | Content blocker | Irrelevant — different syntax |

The docs interleave all three, so **always check which engine a modifier belongs to
before using it.** The linter enforces this.

## Modifier table

Rule structure: `["@@"] pattern [ "$" modifiers ]`. Per the docs, *"Modifiers must be
located at the end of the rule after the `$` character and be separated by commas."*

| Modifier | Engine | Notes |
|---|---|---|
| `client` | Home + DNS | IPs, CIDRs, or client names |
| `denyallow` | both | `*$denyallow=com\|net` |
| `dnstype` | both | `$dnstype=A\|AAAA`, `~` to exclude. Don't mix inclusion and exclusion |
| `dnsrewrite` | both | Higher priority than other rules. In *hosted* DNS, custom rules only |
| `important` | both | Beats exception rules; only another `$important` overrides |
| `badfilter` | both | Does **not** work with `/etc/hosts`-style rules |
| `ctag` | **Home only** | `device_tv`, `os_android`, `user_child`, … |
| `respgeo` | **DNS only** | ⚠️ **Silently useless in our lists.** Country (ISO 3166-1 alpha-2), ASN (`AS15169`), `--` = unknown |

`@@` = exception. `/regexp/` = regex pattern — note the regex body may itself contain
`$`, so modifier parsing must start after the closing `/`.

### Precedence

`$important` allow beats a plain block. So a safe-harbor line like
`@@||main.amazon.pv-cdn.net^$important` will override a later block rule — which is why
a contradictory block can sit in a list looking active while doing nothing.

## Gotchas we have actually hit

- **`$important$dnsrewrite=…` is wrong** — modifiers separate with a **comma**, not a
  second `$`. AdGuard Home drops malformed rules at load time *without reporting them*,
  so the failure mode is a silently unenforced block. This is the bug the linter exists
  to catch.
- **Don't use `$respgeo`.** It's evaluated server-side by the hosted service. AdGuard
  Home has no equivalent. It also can't see through a CDN — anything fronted by
  Cloudflare/Akamai geolocates to the edge node, not the operator — and it does nothing
  against SSAI ads baked into the content stream (i.e. Prime Video's native pipeline).
- **Don't reach for AGLint.** `@adguard/aglint` cannot validate these lists: its
  compatibility tables contain no AdGuard Home/DNS platform, so it flags every
  `$dnsrewrite` as a non-existent modifier (25 false positives on `dns/prime-video.txt`)
  while missing the comma bug entirely. Tested and rejected 2026-07-15.
- **akamai`zed` vs akamai`hd`** (Prime Video): `akamaized.net` = content, **allow**;
  `akamaihd.net` = ads, **block**. One character apart.

## Adding a rule

1. Confirm the host is ad-only, not dual-use — a dual-use block stalls playback and is a
   loud anti-adblock tell. Capture evidence first (PCAPdroid, ads-vs-noads diff).
2. Add it with a comment saying what it is and how you confirmed it.
3. `npm run lint:dns`.
4. Verify **on-device** that ads stop and content still plays — not just that the rule
   loads. The AdGuard Home rules log shows which rule actually matched.
