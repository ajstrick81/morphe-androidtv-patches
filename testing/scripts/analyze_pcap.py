#!/usr/bin/env python3
"""analyze_pcap.py — turn a PCAPdroid capture into an ad-footprint report.

Reconstructs the Prime Video capture analysis: given a .pcap/.pcapng from
scripts/capture.sh, it extracts the DNS + TLS-SNI host inventory, per-host
timing/volume, and — if you pass the ad-break window — flags the hosts that
appear ONLY during the ad break. That AD-ONLY set is the tell for a separate,
blockable ad plane (vs. pure same-host SSAI).

It needs no TLS decryption: DNS names and TLS SNI are plaintext, so this works
even though Netflix's core flows resist mitm.

Usage:
    analyze_pcap.py CAPTURE.pcap [--ad MM:SS-MM:SS] [--json OUT.json] [--csv OUT.csv]
    analyze_pcap.py AD_TITLE.pcap --vs ADFREE_TITLE.pcap     # A/B diff

A/B diff (--vs): capture an *ad-supported* title's startup and an *ad-free*
title's startup (e.g. a kids/PG movie) on the SAME profile+network, then diff.
Hosts present in the ad title but ABSENT in the ad-free one are the candidate
ad-trigger surface. If the two are identical, the trigger is server-side inside
the (encrypted) manifest.

Times are OFFSETS FROM THE FIRST PACKET (how you'd note "ad break 2:14 into the
session"), given as MM:SS or HH:MM:SS.

Requires scapy (pip install scapy). Reads pcap and pcapng.
"""
import argparse, csv, json, sys
from collections import defaultdict

try:
    from scapy.all import PcapReader, DNS, DNSRR, IP, IPv6, TCP, UDP, Raw
except Exception as e:  # pragma: no cover
    sys.exit("scapy is required: pip install scapy  (%s)" % e)

AD_DOMAIN_HINTS = (
    "doubleclick", "googlesyndication", "adnxs", "xandr", "adservice",
    "nielsen", "imrworldwide", "moatads", "scorecardresearch", "adsafeprotected",
    "innovid", "spotx", "freewheel", "conviva", "kaltura", "adform", "amazon-adsystem",
    "monet", "ads.", ".ads", "advertising", "-ad-", "adtech",
)


def hhmmss_to_secs(s):
    parts = [int(p) for p in s.strip().split(":")]
    while len(parts) < 3:
        parts.insert(0, 0)
    return parts[-3] * 3600 + parts[-2] * 60 + parts[-1]


def extract_sni(payload: bytes):
    """Parse SNI out of a TLS ClientHello (manual byte parse). None if not found."""
    try:
        if len(payload) < 45 or payload[0] != 0x16 or payload[5] != 0x01:
            return None
        p = 5 + 4 + 2 + 32                              # rec hdr, hs hdr, ver, random
        if p >= len(payload):
            return None
        p += 1 + payload[p]                            # session id
        if p + 2 > len(payload):
            return None
        p += 2 + int.from_bytes(payload[p:p+2], "big")  # cipher suites
        if p >= len(payload):
            return None
        p += 1 + payload[p]                            # compression methods
        if p + 2 > len(payload):
            return None
        p += 2                                         # extensions total len
        while p + 4 <= len(payload):
            etype = int.from_bytes(payload[p:p+2], "big")
            elen = int.from_bytes(payload[p+2:p+4], "big"); p += 4
            if etype == 0x0000:
                if p + 5 > len(payload):
                    return None
                nlen = int.from_bytes(payload[p+3:p+5], "big")
                host = payload[p+5:p+5+nlen]
                try:
                    return host.decode("idna") if host else None
                except Exception:
                    return host.decode("latin-1", "replace")
            p += elen
    except Exception:
        return None
    return None


def pkt_addrs(pkt):
    if IP in pkt:
        return pkt[IP].src, pkt[IP].dst
    if IPv6 in pkt:
        return pkt[IPv6].src, pkt[IPv6].dst
    return None, None


def is_local(ip):
    return (ip or "").startswith(("10.", "192.168.", "172.")) or ip == "::1"


def collect(pcap_path, ad_lo=None, ad_hi=None):
    """Single pass over a pcap → (hosts dict, dns_queries, total, duration)."""
    stash = []
    ip_to_names = defaultdict(set)
    ip_to_sni = defaultdict(set)
    dns_queries = []
    hosts = defaultdict(lambda: {"first": None, "last": None, "bytes_up": 0,
                                 "bytes_down": 0, "flows": set(), "ips": set(),
                                 "in_ad": False, "out_ad": False})
    t0 = None
    total = 0
    with PcapReader(pcap_path) as pr:
        for pkt in pr:
            t = float(pkt.time)
            if t0 is None:
                t0 = t
            trel = t - t0
            src, dst = pkt_addrs(pkt)
            total += 1
            if pkt.haslayer(DNS):
                dns = pkt[DNS]
                if dns.qr == 0 and dns.qd is not None:
                    try:
                        qname = dns.qd.qname.decode("idna").rstrip(".")
                    except Exception:
                        qname = str(dns.qd.qname)
                    dns_queries.append((round(trel, 1), qname))
                elif dns.qr == 1:
                    for i in range(dns.ancount or 0):
                        try:
                            rr = dns.an[i]
                        except Exception:
                            break
                        if isinstance(rr, DNSRR) and rr.type in (1, 28):
                            try:
                                nm = rr.rrname.decode("idna").rstrip(".")
                                ip_to_names[str(rr.rdata)].add(nm)
                            except Exception:
                                pass
            if pkt.haslayer(TCP) and pkt.haslayer(Raw):
                sni = extract_sni(bytes(pkt[Raw].load))
                if sni and dst:
                    ip_to_sni[dst].add(sni)
            if (pkt.haslayer(TCP) or pkt.haslayer(UDP)) and dst:
                l4 = pkt[TCP] if pkt.haslayer(TCP) else pkt[UDP]
                if is_local(src) and not is_local(dst):
                    remote, up = dst, True
                elif is_local(dst) and not is_local(src):
                    remote, up = src, False
                else:
                    remote, up = dst, True
                flow = (src, dst, getattr(l4, "sport", 0), getattr(l4, "dport", 0))
                stash.append((remote, trel, len(pkt), up, flow))

    def name_for(ip):
        if ip_to_sni.get(ip):
            return sorted(ip_to_sni[ip], key=len)[0]
        if ip_to_names.get(ip):
            return sorted(ip_to_names[ip], key=len)[0]
        return ip

    for ip, trel, plen, up, flow in stash:
        h = hosts[name_for(ip)]
        h["first"] = trel if h["first"] is None else min(h["first"], trel)
        h["last"] = trel if h["last"] is None else max(h["last"], trel)
        h["bytes_up" if up else "bytes_down"] += plen
        h["flows"].add(flow)
        h["ips"].add(ip)
        if ad_lo is not None and ad_lo <= trel <= ad_hi:
            h["in_ad"] = True
        else:
            h["out_ad"] = True

    dur = round(max((h["last"] for h in hosts.values()), default=0), 1)
    return hosts, dns_queries, total, dur


def rows_from(hosts, ad_lo, min_bytes):
    rows = []
    for name, h in hosts.items():
        b = h["bytes_up"] + h["bytes_down"]
        if b < min_bytes:
            continue
        rows.append({
            "host": name, "bytes": b, "up": h["bytes_up"], "down": h["bytes_down"],
            "flows": len(h["flows"]), "first_s": round(h["first"], 1),
            "last_s": round(h["last"], 1), "ips": len(h["ips"]),
            "ad_only": (h["in_ad"] and not h["out_ad"]) if ad_lo is not None else None,
            "ad_hint": any(k in name.lower() for k in AD_DOMAIN_HINTS),
        })
    rows.sort(key=lambda r: (r["ad_only"] is True, r["ad_hint"], r["bytes"]), reverse=True)
    return rows


def print_table(rows):
    hdr = f'{"HOST":<48} {"BYTES":>10} {"FLOWS":>6} {"FIRST":>7} {"LAST":>7}  FLAGS'
    print(hdr); print("-" * len(hdr))
    for r in rows:
        flags = []
        if r["ad_only"] is True: flags.append("AD-ONLY")
        if r["ad_hint"]:         flags.append("ad-domain?")
        print(f'{r["host"][:48]:<48} {r["bytes"]:>10} {r["flows"]:>6} '
              f'{r["first_s"]:>7} {r["last_s"]:>7}  {" ".join(flags)}')


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pcap", help="the ad-supported title's capture")
    ap.add_argument("--vs", metavar="ADFREE.pcap",
                    help="A/B diff against an ad-FREE title's capture (PG/kids)")
    ap.add_argument("--ad", help="ad-break window MM:SS-MM:SS (offset from first packet)")
    ap.add_argument("--json", help="write full result as JSON")
    ap.add_argument("--csv", help="write per-host table as CSV")
    ap.add_argument("--min-bytes", type=int, default=0, help="hide hosts below N bytes")
    args = ap.parse_args()

    ad_lo = ad_hi = None
    if args.ad:
        try:
            a, b = args.ad.split("-")
            ad_lo, ad_hi = hhmmss_to_secs(a), hhmmss_to_secs(b)
        except Exception:
            sys.exit("--ad must look like  2:14-2:45")

    hosts, dns_q, total, dur = collect(args.pcap, ad_lo, ad_hi)
    rows = rows_from(hosts, ad_lo, args.min_bytes)

    print(f"\n=== {args.pcap} ===")
    print(f"packets: {total}   duration: {dur}s   hosts: {len(rows)}   dns queries: {len(dns_q)}")
    if ad_lo is not None:
        print(f"ad-break window: {ad_lo}s–{ad_hi}s")
    print()
    print_table(rows)

    if ad_lo is not None:
        adonly = [r["host"] for r in rows if r["ad_only"]]
        print(f"\n>>> HOSTS SEEN ONLY DURING THE AD BREAK ({len(adonly)}):")
        for h in adonly:
            print("   ", h)
        if not adonly:
            print("    (none — consistent with pure same-host SSAI)")

    # ── A/B diff against an ad-free title ─────────────────────────────
    if args.vs:
        base_hosts, _, base_total, base_dur = collect(args.vs)
        base_names = set(base_hosts.keys())
        ad_names = set(hosts.keys())
        only_in_ad = sorted(ad_names - base_names)
        only_in_free = sorted(base_names - ad_names)
        print(f"\n=== A/B DIFF ===")
        print(f"ad title  : {args.pcap}  ({len(ad_names)} hosts, {total} pkts, {dur}s)")
        print(f"ad-free   : {args.vs}  ({len(base_names)} hosts, {base_total} pkts, {base_dur}s)")
        print(f"\n>>> HOSTS IN THE AD TITLE BUT NOT THE AD-FREE TITLE ({len(only_in_ad)})")
        print("    — the candidate ad-trigger surface:")
        if only_in_ad:
            for h in only_in_ad:
                hint = "  ad-domain?" if any(k in h.lower() for k in AD_DOMAIN_HINTS) else ""
                b = hosts[h]["bytes_up"] + hosts[h]["bytes_down"]
                print(f"     {h:<46} {b:>9} B{hint}")
        else:
            print("     (none — identical host set → trigger is server-side in the manifest)")
        if only_in_free:
            print(f"\n    (for reference — hosts only in the ad-free capture, {len(only_in_free)}):")
            for h in only_in_free:
                print("      ", h)

    if args.json:
        json.dump({"rows": rows, "dns_queries": dns_q,
                   "ad_window": [ad_lo, ad_hi]}, open(args.json, "w"), indent=2)
        print(f"\n[json] {args.json}")
    if args.csv and rows:
        with open(args.csv, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader(); w.writerows(rows)
        print(f"[csv]  {args.csv}")


if __name__ == "__main__":
    main()
