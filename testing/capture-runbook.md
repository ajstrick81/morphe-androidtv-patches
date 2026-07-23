# 📡 Network-capture runbook (PCAPdroid over ADB)

Drive **PCAPdroid** on the Onn TV from your PC over ADB to capture an app's
traffic, then pull the `.pcap` back for analysis. Reconstructs the Prime Video
capture workflow on top of the `testing/` harness (`scripts/capture.sh` reuses
the same `device.env` + adb helpers as the rest of the loop).

Primary use right now: **map Netflix's ad-delivery footprint** — answer whether
ad insertion has a separate, blockable surface, or is pure same-host SSAI.

---

## Why this works even though Netflix is hardened

We already know (from the binary + milo analysis) that Netflix wraps its core
in **TLS → MSL → appboot signature**. So expect the MSL/appboot/manifest flows
to **resist TLS decryption** (Cronet validates certs; the binary has
`untrustedcert` handling). That's fine — two things are visible *without*
decryption and answer the key question:

- **DNS queries + TLS SNI** — hostnames are plaintext in the handshake, so we
  see *every host* the app contacts.
- **Per-flow timing & byte volume** — an ad break looks different from content.

If ads pull from a distinct host, or fire beacons to a measurable domain, that's
a **DNS-blockable** surface (→ AdGuard). If it's all identical
`*.oca.nflxvideo.net` byte-ranges, the "hard ceiling" is real. Either way we get
a definitive answer. `--decrypt` is a bonus that may crack *un-pinned* side
flows (some telemetry / ad beacons carry break & creative IDs).

---

## Prerequisites

**On the TV (one time):**
1. `testing/` harness set up (`config/device.env` has your TV IP; adb reachable —
   see `testing/README.md`).
2. Install **PCAPdroid** (`com.emanuelef.remote_capture`).
3. PCAPdroid → **Settings → grant the "Control permission"** (this is what lets
   `adb` drive it via the `CaptureCtrl` API).
4. *(Only for `--decrypt`)* install the **PCAPdroid mitm addon** and trust its
   CA. On Android TV, user CAs don't cover everything and Cronet may still
   refuse — treat decryption of Netflix core flows as unlikely; do it for the
   side flows.

**Account:** you must be on the **Netflix ad-supported plan** — no ad tier, no
ad breaks to capture.

---

## The capture protocol (timestamped A/B)

The analysis hinges on being able to bucket traffic by phase, so **note
wall-clock times** as you go.

```bash
cd testing

# 1. Start capturing Netflix traffic (host-footprint mode, no decryption)
./scripts/capture.sh start netflix
#    → accept PCAPdroid's VPN-consent prompt on the TV if it appears

# 2. On the TV, run a clean scripted session — WRITE DOWN THE TIMES:
#    t0  home screen, let it sit ~30s
#    t1  start an ad-tier title, let content play ~2 min
#    t2  AD BREAK STARTS   ← note precisely
#    t3  AD BREAK ENDS     ← note precisely
#    t4  content resumes ~1 min

# 3. Stop and pull
./scripts/capture.sh stop
./scripts/capture.sh pull        # → testing/out/netflix-capture.pcap
```

Or the one-shot (less precise, but easy) — capture a fixed window:

```bash
./scripts/capture.sh run netflix 300          # 5-minute capture, auto start→stop→pull
./scripts/capture.sh run netflix 300 --decrypt # same, attempt TLS decryption too
```

Then **upload `testing/out/netflix-capture.pcap`** along with your timestamp
notes (e.g. "ad break 2:14–2:45"). That timestamp map is what lets the analysis
diff ad-break hosts from content hosts.

---

## Analyze the pcap  (`scripts/analyze_pcap.py`)

You can run the first pass yourself (or just upload the pcap and I'll run it):

```bash
# needs scapy:  pip install scapy   (reads .pcap and .pcapng, no TLS decryption needed)
python3 scripts/analyze_pcap.py testing/out/netflix-capture.pcap --ad 2:14-2:45
```

`--ad MM:SS-MM:SS` is your noted ad-break window as an **offset from the first
packet**. The report prints the DNS/SNI host inventory with per-host bytes,
flow count, and first/last-seen time, and — the key output — a list of
**HOSTS SEEN ONLY DURING THE AD BREAK**. That AD-ONLY set (plus any host the
tool flags `ad-domain?`) is exactly what to test-block in AdGuard or point the
mitm at. `--csv out.csv` / `--json out.json` dump the full table.

What it extracts:
- Full **DNS/SNI host inventory**, bucketed by your `--ad` window.
- **Hosts that appear only during the ad break** — the tell for a separate ad plane.
- Per-flow **timing/volume** to distinguish ad segments from content.
- If `--decrypt` cracked anything: the **HTTP payloads** of un-pinned flows
  (ad-decision calls, impression/quartile beacons → break & creative IDs).

## Then we escalate based on what shows up

- **Ad-specific hosts found** → block them in **AdGuard**; does it kill ads or
  break playback?
- **Beacons to non-Netflix hosts** → point the **mitm** at *those* (often
  un-pinned) to read break/creative IDs — the ad-break schema we've chased.
- **All identical same-host SSAI** → definitive "not reachable at the network
  layer," honestly earned; fall back to the appboot-signature analysis.

---

## Troubleshooting

- **`CaptureCtrl` does nothing** — grant PCAPdroid's *Control permission*
  (step 3). Without it the API is ignored.
- **VPN prompt every time** — accept once and check "always"; PCAPdroid uses a
  local VPN to capture without root.
- **`pull` can't find the file** — open PCAPdroid on the TV; it shows the saved
  file's exact path. Pull it by hand:
  `adb -s <TV_IP:PORT> pull '<path>' testing/out/`.
- **Nothing decrypts with `--decrypt`** — expected for Netflix's core flows
  (cert validation). The host-footprint (non-decrypted) data is still the point.
- **Onn shows no ads** — confirm the account/profile is on the *ad-supported*
  plan.
