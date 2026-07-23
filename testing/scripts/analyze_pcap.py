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
    analyze_pcap.py CAPTURE.pcap [--ad MM:SS-MM:SS] [--phases M:S,M:S,...]
                                 [--json OUT.json] [--csv OUT.csv]

Times are OFFSETS FROM THE FIRST PACKET (how you'd note "ad break 2:14 into the
session"), given as MM:SS or HH:MM:SS. Example:
    analyze_pcap.py netflix-capture.pcap --ad 2:14-2:45

Requires scapy (pip install scapy). Reads pcap and pcapng.
"""
import argparse, csv, json, sys
from collections import defaultdict

try:
    from scapy.all import PcapReader, DNS, DNSRR, IP, IPv6, TCP, UDP, Raw
except Exception as e:  # pragma: no cover
    sys.exit("scapy is required: pip install scapy  (%s)" % e)

# Known ad / measurement domains — substring match, purely a hint in the report.
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
    h, m, sec = parts[-3], parts[-2], parts[-1]
    return h * 3600 + m * 60 + sec


def extract_sni(payload: bytes):
    """Parse SNI out of a TLS ClientHello. Returns hostname or None.
    Manual byte parse (no scapy TLS layer) for robustness."""
    try:
        if len(payload) < 45 or payload[0] != 0x16:      # TLS handshake record
            return None
        # record: type(1) ver(2) len(2) then handshake
        if payload[5] != 0x01:                            # ClientHello
            return None
        p = 5 + 4                                          # skip record hdr + hs type/len
        p += 2 + 32                                        # client_version + random
        if p >= len(payload):
            return None
        sid_len = payload[p]; p += 1 + sid_len             # session id
        if p + 2 > len(payload):
            return None
        cs_len = int.from_bytes(payload[p:p+2], "big"); p += 2 + cs_len   # cipher suites
        if p >= len(payload):
            return None
        comp_len = payload[p]; p += 1 + comp_len           # compression methods
        if p + 2 > len(payload):
            return None
        ext_total = int.from_bytes(payload[p:p+2], "big"); p += 2
        end = min(len(payload), p + ext_total)
        while p + 4 <= end:
            etype = int.from_bytes(payload[p:p+2], "big")
            elen = int.from_bytes(payload[p+2:p+4], "big"); p += 4
            if etype == 0x0000:                            # server_name
                # server_name_list len(2), name_type(1), name_len(2), name
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


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pcap")
    ap.add_argument("--ad", help="ad-break window as MM:SS-MM:SS (offset from first packet)")
    ap.add_argument("--phases", help="comma list of phase boundaries MM:SS (extra buckets)")
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

    stash = []                         # (tag, remote_ip, t_rel, plen, up, flow)
    ip_to_names = defaultdict(set)     # resolved-IP  -> {dns names}
    dns_queries = []                   # (t_rel, qname)
    ip_to_sni = defaultdict(set)       # dst-IP       -> {sni names}
    # host aggregation keyed by best-known name (sni > dns > ip)
    hosts = defaultdict(lambda: {"first": None, "last": None, "bytes_up": 0,
                                 "bytes_down": 0, "flows": set(), "ips": set(),
                                 "in_ad": False, "out_ad": False})
    t0 = None
    total = 0

    # local IP heuristic: the device side. We infer it as the most common src of
    # outbound TLS ClientHellos; simpler: treat RFC1918 / device as "up".
    def is_local(ip):
        return (ip or "").startswith(("10.", "192.168.", "172.")) or ip == "::1"

    with PcapReader(args.pcap) as pr:
        for pkt in pr:
            t = float(pkt.time)
            if t0 is None:
                t0 = t
            trel = t - t0
            src, dst = pkt_addrs(pkt)
            plen = len(pkt)
            total += 1

            # DNS answers → IP↔name map; DNS queries → list
            if pkt.haslayer(DNS):
                dns = pkt[DNS]
                if dns.qr == 0 and dns.qd is not None:      # query
                    try:
                        qname = dns.qd.qname.decode("idna").rstrip(".")
                    except Exception:
                        qname = str(dns.qd.qname)
                    dns_queries.append((trel, qname))
                elif dns.qr == 1:                            # response
                    for i in range(dns.ancount or 0):
                        try:
                            rr = dns.an[i]
                        except Exception:
                            break
                        if isinstance(rr, DNSRR) and rr.type in (1, 28):  # A / AAAA
                            try:
                                nm = rr.rrname.decode("idna").rstrip(".")
                                ip_to_names[rr.rdata if isinstance(rr.rdata, str)
                                            else str(rr.rdata)].add(nm)
                            except Exception:
                                pass

            # TLS SNI on outbound ClientHello
            if pkt.haslayer(TCP) and pkt.haslayer(Raw):
                sni = extract_sni(bytes(pkt[Raw].load))
                if sni and dst:
                    ip_to_sni[dst].add(sni)

            # flow/byte accounting for TCP+UDP with an IP dst
            if (pkt.haslayer(TCP) or pkt.haslayer(UDP)) and dst:
                l4 = pkt[TCP] if pkt.haslayer(TCP) else pkt[UDP]
                # orient: remote IP is the non-local endpoint
                if is_local(src) and not is_local(dst):
                    remote, up = dst, True
                elif is_local(dst) and not is_local(src):
                    remote, up = src, False
                else:
                    remote, up = dst, True
                flow = (src, dst, getattr(l4, "sport", 0), getattr(l4, "dport", 0))
                # defer naming until we know SNI/DNS; stash by remote IP for now
                rec = ("ip", remote, trel, plen, up, flow)
                # accumulate into a temp per-IP structure via hosts later; do inline:
                stash.append(rec)

    # resolve each remote IP to a name: SNI first, then DNS, then raw IP
    def name_for(ip):
        if ip in ip_to_sni and ip_to_sni[ip]:
            return sorted(ip_to_sni[ip], key=len)[0]
        if ip in ip_to_names and ip_to_names[ip]:
            return sorted(ip_to_names[ip], key=len)[0]
        return ip

    for _, ip, trel, plen, up, flow in stash:
        name = name_for(ip)
        h = hosts[name]
        h["first"] = trel if h["first"] is None else min(h["first"], trel)
        h["last"] = trel if h["last"] is None else max(h["last"], trel)
        h["bytes_up" if up else "bytes_down"] += plen
        h["flows"].add(flow)
        h["ips"].add(ip)
        if ad_lo is not None and ad_lo <= trel <= ad_hi:
            h["in_ad"] = True
        else:
            h["out_ad"] = True

    # build report rows
    rows = []
    for name, h in hosts.items():
        b = h["bytes_up"] + h["bytes_down"]
        if b < args.min_bytes:
            continue
        ad_only = h["in_ad"] and not h["out_ad"] if ad_lo is not None else None
        hint = any(k in name.lower() for k in AD_DOMAIN_HINTS)
        rows.append({
            "host": name, "bytes": b, "up": h["bytes_up"], "down": h["bytes_down"],
            "flows": len(h["flows"]), "first_s": round(h["first"], 1),
            "last_s": round(h["last"], 1), "ips": len(h["ips"]),
            "ad_only": ad_only, "ad_hint": hint,
        })
    rows.sort(key=lambda r: (r["ad_only"] is True, r["ad_hint"], r["bytes"]), reverse=True)

    # ── print report ──────────────────────────────────────────────────
    dur = round((max((r["last_s"] for r in rows), default=0)), 1)
    print(f"\n=== {args.pcap} ===")
    print(f"packets: {total}   duration: {dur}s   hosts: {len(rows)}   dns queries: {len(dns_queries)}")
    if ad_lo is not None:
        print(f"ad-break window: {ad_lo}s–{ad_hi}s")
    print()
    hdr = f'{"HOST":<48} {"BYTES":>10} {"FLOWS":>6} {"FIRST":>7} {"LAST":>7}  FLAGS'
    print(hdr); print("-" * len(hdr))
    for r in rows:
        flags = []
        if r["ad_only"] is True: flags.append("AD-ONLY")
        if r["ad_hint"]:         flags.append("ad-domain?")
        print(f'{r["host"][:48]:<48} {r["bytes"]:>10} {r["flows"]:>6} '
              f'{r["first_s"]:>7} {r["last_s"]:>7}  {" ".join(flags)}')

    if ad_lo is not None:
        adonly = [r["host"] for r in rows if r["ad_only"]]
        print("\n>>> HOSTS SEEN ONLY DURING THE AD BREAK "
              f"({len(adonly)}) — the candidates to block/inspect:")
        for hst in adonly:
            print("   ", hst)
        if not adonly:
            print("    (none — consistent with pure same-host SSAI)")

    if args.json:
        json.dump({"rows": rows, "dns_queries": dns_queries,
                   "ad_window": [ad_lo, ad_hi]}, open(args.json, "w"), indent=2)
        print(f"\n[json] {args.json}")
    if args.csv:
        with open(args.csv, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else ["host"])
            w.writeheader(); w.writerows(rows)
        print(f"[csv]  {args.csv}")



if __name__ == "__main__":
    main()
