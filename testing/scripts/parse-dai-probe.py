#!/usr/bin/env python3
"""Parse a Paramount+ DAI Probe capture (from capture-dai.sh) into a readable
per-slot breakdown of a commercial break.

The probe logs every "/linear/pods/v1/..." request URL. Google DAI pod-serving
segment URLs look like:

    .../linear/pods/v1/.../<pod>/<slot>/<adIdx>/<hash>/<N>.ts|.aac[?d=<ms>]

and the slate rendition reuses the same shape with slot/adIdx == slate/0:

    .../linear/pods/v1/.../<pod>/slate/0/<hash>/<N>.ts|.aac[?d=<ms>]

This groups the captured URLs by (pod, slot, adIdx, hash) and reports, for each
group: the media kind, the segment count (max index + 1), and the per-segment
"?d=" durations when present. That is the ground truth for the "segment-count
mismatch": how many ad slots/segments a real break has, versus what the slate
covers, and whether segment durations are carried in the URL (so Patch 3 can
derive them instead of hardcoding ?d=4972).

Usage:
    ./parse-dai-probe.py testing/out/dai-probe-*.log
"""

import re
import sys
from collections import defaultdict
from glob import glob
from urllib.parse import urlparse, parse_qs

HEX32 = re.compile(r"^[0-9a-fA-F]{32}$")
SEG = re.compile(r"^(\d+)\.(\w+)$")  # e.g. 5.ts / 12.aac
URL = re.compile(r"https?://\S+")


def extract_urls(paths):
    for path in paths:
        try:
            with open(path, "r", errors="replace") as fh:
                for line in fh:
                    m = URL.search(line)
                    if m and "/linear/pods/v1/" in m.group(0):
                        yield m.group(0).strip()
        except OSError as e:
            print(f"! could not read {path}: {e}", file=sys.stderr)


def classify(url):
    """Return (pod, slot, adIdx, hash, kind, seg_index, ext, d_ms) or None."""
    parsed = urlparse(url)
    parts = parsed.path.split("/")
    # Find the 32-hex hash component; the segment layout is anchored to it.
    idx = next((i for i, p in enumerate(parts) if HEX32.match(p)), None)
    if idx is None or idx < 3 or idx + 1 >= len(parts):
        return None
    pod, slot, ad_idx, hsh = parts[idx - 3], parts[idx - 2], parts[idx - 1], parts[idx]
    segfile = parts[idx + 1]
    q = parse_qs(parsed.query)
    d_ms = q.get("d", [None])[0]
    sm = SEG.match(segfile)
    if sm:
        return pod, slot, ad_idx, hsh, "segment", int(sm.group(1)), sm.group(2), d_ms
    return pod, slot, ad_idx, hsh, "manifest", None, segfile, d_ms


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)
    paths = [p for a in args for p in (glob(a) or [a])]

    groups = defaultdict(lambda: {"segs": set(), "durs": {}, "kinds": set(),
                                  "ext": set(), "manifests": 0})
    total = 0
    for url in extract_urls(paths):
        c = classify(url)
        if not c:
            continue
        total += 1
        pod, slot, ad_idx, hsh, kind, seg_i, ext, d_ms = c
        g = groups[(pod, slot, ad_idx, hsh)]
        g["kinds"].add(kind)
        if kind == "segment":
            g["segs"].add(seg_i)
            g["ext"].add(ext)
            if d_ms is not None:
                g["durs"][seg_i] = int(d_ms)
        else:
            g["manifests"] += 1

    if not groups:
        print("No /linear/pods/v1/ URLs found in the capture(s).")
        print("Confirm the DAI Probe patch was enabled and a break actually played.")
        return

    print(f"Parsed {total} pod request(s) across {len(groups)} group(s).\n")

    slate_keys = [k for k in groups if k[1] == "slate"]
    ad_keys = [k for k in groups if k[1] != "slate"]

    def dump(title, keys):
        if not keys:
            return
        print(f"== {title} ==")
        hdr = f"{'pod':>10} {'slot':>6} {'adIdx':>6} {'hash':>10} {'kind':>10} {'segs':>5} {'maxN':>5} {'ext':>8} {'?d= (ms)':>28}"
        print(hdr)
        print("-" * len(hdr))
        for k in sorted(keys):
            pod, slot, ad_idx, hsh = k
            g = groups[k]
            segs = g["segs"]
            n = len(segs)
            maxn = max(segs) if segs else "-"
            durs = g["durs"]
            if durs:
                vals = [durs[i] for i in sorted(durs)]
                dsum = sum(vals) / 1000.0
                # show first few + total
                shown = ",".join(str(v) for v in vals[:4])
                more = "..." if len(vals) > 4 else ""
                dstr = f"{shown}{more}  Σ={dsum:.2f}s"
            else:
                dstr = "(none in URL)"
            kind = "+".join(sorted(g["kinds"]))
            ext = "/".join(sorted(g["ext"])) or "-"
            print(f"{pod[:10]:>10} {slot:>6} {ad_idx:>6} {hsh[:8]+'..':>10} "
                  f"{kind:>10} {n:>5} {str(maxn):>5} {ext:>8} {dstr:>28}")
        print()

    dump("AD SLOTS (real ad pods — what a break actually contains)", ad_keys)
    dump("SLATE (Commercial in Progress rendition)", slate_keys)

    # ---- Summary / the mismatch, stated plainly ------------------------------
    print("== SUMMARY ==")
    print(f"ad slots seen        : {len(ad_keys)}")
    ad_segtotal = sum(len(groups[k]['segs']) for k in ad_keys)
    print(f"total ad segments    : {ad_segtotal}")
    if slate_keys:
        slate_segtotal = sum(len(groups[k]['segs']) for k in slate_keys)
        print(f"slate groups seen    : {len(slate_keys)}")
        print(f"total slate segments : {slate_segtotal}")
    have_d = any(groups[k]["durs"] for k in groups)
    print(f"per-segment ?d= in URL: {'YES — durations can be read per segment' if have_d else 'NO — durations live in the playlist body, not the URL'}")
    all_hashes = {k[3] for k in groups}
    print(f"distinct hashes      : {len(all_hashes)} "
          f"({'ad and slate SHARE a hash' if len(all_hashes) == 1 else 'multiple hashes — check slate uses the same hash as the ad it replaces'})")


if __name__ == "__main__":
    main()
