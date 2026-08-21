"""YTTV SCTE-35-in-SABR hunter.

For each googlevideo /videoplayback response:
  - walk UMP parts with the CORRECT custom-varint framing (from ytv_vidmon.py)
  - fully decode protobuf for MEDIA_HEADER(20), LIVE_METADATA(31), SABR_CTX(57)
  - scan EVERY part payload (and nested protobuf string subfields) for a
    SCTE-35 splice_info_section (table_id 0xFC ...) and hand candidates to threefive
Also brackets the ad break via heartbeat cueRangeSet + segment video_id/itag.

Dumps raw parts to bodies/ so anything the live scan misses can be re-decoded offline.
"""
import time, os, json, binascii
from mitmproxy import http

os.makedirs("bodies", exist_ok=True)
os.makedirs("scte", exist_ok=True)

PART = {20:"MEDIA_HEADER",21:"MEDIA",22:"MEDIA_END",31:"LIVE_METADATA",
        57:"SABR_CONTEXT_UPDATE",58:"STREAM_PROTECTION_STATUS",
        35:"FORMAT_INITIALIZATION_METADATA",42:"NEXT_REQUEST_POLICY",
        43:"USTREAMER_VIDEO_AND_FORMAT",44:"SABR_SEEK",45:"SABR_ERROR",
        46:"SABR_CONTEXT_SENDING_POLICY"}

# --- UMP custom-varint (little-endian, size encoded in the high bits of byte 0) ---
def uvar(b, o):
    f = b[o]
    if f < 128: return f, o+1
    if f < 192: return (f & 0x3f) | (b[o+1] << 6), o+2
    if f < 224: return (f & 0x1f) | (b[o+1] << 5) | (b[o+2] << 13), o+3
    if f < 240: return (f & 0x0f) | (b[o+1] << 4) | (b[o+2] << 12) | (b[o+3] << 20), o+4
    return b[o+1] | (b[o+2] << 8) | (b[o+3] << 16) | (b[o+4] << 24), o+5

# --- generic protobuf walk: yield (path, wire_type, value) ---
def pb_walk(buf, prefix=""):
    p = 0; out = []
    n = len(buf)
    while p < n:
        try:
            tag = buf[p]; p += 1
            fn = tag >> 3; wt = tag & 7
            if wt == 0:  # varint
                v = 0; s = 0
                while p < n:
                    c = buf[p]; p += 1; v |= (c & 0x7f) << s
                    if not c & 0x80: break
                    s += 7
                out.append((f"{prefix}{fn}", "var", v))
            elif wt == 2:  # length-delimited
                ln = 0; s = 0
                while p < n:
                    c = buf[p]; p += 1; ln |= (c & 0x7f) << s
                    if not c & 0x80: break
                    s += 7
                val = buf[p:p+ln]; p += ln
                out.append((f"{prefix}{fn}", "bytes", val))
                # recurse only if it plausibly is a nested message
                if val and (val[0] & 7) in (0,2) and len(val) >= 2:
                    sub = pb_walk(val, f"{prefix}{fn}.")
                    if sub: out.extend(sub)
            elif wt == 5: p += 4; out.append((f"{prefix}{fn}", "i32", None))
            elif wt == 1: p += 8; out.append((f"{prefix}{fn}", "i64", None))
            else: break
        except Exception:
            break
    return out

# --- SCTE-35 candidate test: splice_info_section starts 0xFC + 0x30/0x3?? ---
def scte_candidates(payload):
    hits = []
    for i in range(len(payload) - 3):
        if payload[i] == 0xFC and (payload[i+1] & 0xF0) == 0x30:
            seclen = ((payload[i+1] & 0x0F) << 8) | payload[i+2]
            end = i + 3 + seclen
            if 0 < seclen < 4096 and end <= len(payload):
                hits.append(payload[i:end])
    return hits

def decode_scte(raw):
    """Return decoded dict ONLY for a genuinely-parsing splice_info_section, else None."""
    try:
        import threefive
        cue = threefive.Cue(raw)
        if cue.decode() is False:
            return None
        d = cue.get()
        cmd = (d or {}).get("command", {})
        # require a real command type to reject coincidental 0xFC30 byte runs
        if not cmd or "command_type" not in cmd:
            return None
        return d
    except Exception:
        return None

VPN = {"c": 0}
FOUND = {"n": 0}
CENSUS = {}

def response(flow: http.HTTPFlow):
    h = flow.request.pretty_host; path = flow.request.path
    ts = time.strftime("%H:%M:%S")

    if "youtubei/v1/player/heartbeat" in path:
        try:
            d = json.loads(flow.response.get_text() or "")
            crs = d.get("playerCueRangeSet")
            emb = crs == [{"cueRangeSetIdentifier":"PLAYER_CUE_RANGE_SET_IDENTIFIER_EMBARGO"}]
            note = "" if emb else "  <<< NON-EMBARGO CUE"
            print(f"[HB] {ts} cueRangeSet={json.dumps(crs)}{note}", flush=True)
        except Exception as e:
            print("hberr", e, flush=True)
        return

    if "googlevideo" not in h or "videoplayback" not in path:
        return
    rs = flow.response.raw_content or b""
    if not rs: return
    VPN["c"] += 1; idx = VPN["c"]

    o = 0; seg_summ = []
    while o < len(rs):
        try:
            pt, o = uvar(rs, o); sz, o = uvar(rs, o)
        except Exception:
            break
        payload = rs[o:o+sz]; o += sz
        name = PART.get(pt, f"?{pt}")
        CENSUS[pt] = CENSUS.get(pt, 0) + 1

        # emsg-box carriage: SCTE-35 is commonly boxed INSIDE fMP4 media segments.
        # Detect by literal ISO-BMFF 'emsg' box type + scte35 scheme (low false-positive).
        if pt in (21, 22):
            ei = payload.find(b"emsg")
            while ei != -1:
                box = payload[max(0, ei-4):ei+300]
                # scheme_id_uri is a null-terminated string shortly after 'emsg'
                try:
                    tail = box[8:200]
                    scheme = tail.split(b"\x00")[0].decode("latin1", "replace")
                except Exception:
                    scheme = "?"
                FOUND["n"] += 1
                fn = f"scte/emsg_{FOUND['n']:03d}_VP{idx}_part{pt}.bin"
                open(fn, "wb").write(box)
                print(f"[EMSG] {ts} part={name} @{ei} scheme='{scheme}' box={binascii.hexlify(box[:48]).decode()}", flush=True)
                ei = payload.find(b"emsg", ei + 1)
            continue
        for cand in scte_candidates(payload):
            dec = decode_scte(cand)
            if dec is None:
                continue
            FOUND["n"] += 1
            fn = f"scte/hit_{FOUND['n']:03d}_VP{idx}_part{pt}.bin"
            open(fn, "wb").write(cand)
            print(f"[SCTE35] {ts} part={name} len={len(cand)} -> {json.dumps(dec)[:500]}", flush=True)
            open(fn + ".json", "w", encoding="utf-8").write(json.dumps(dec, indent=2, default=str))

        # structured decode for the signal-bearing parts
        if pt in (20, 31, 57, 42):
            fields = pb_walk(payload)
            interesting = [(k, wt, (v if wt=="var" else (binascii.hexlify(v).decode()[:48] if isinstance(v,(bytes,bytearray)) else v)))
                           for k, wt, v in fields if wt in ("var","bytes")]
            if pt == 20:
                vid = next((v.decode(errors="replace") for k,wt,v in fields if k=="2" and wt=="bytes"), None)
                itag = next((v for k,wt,v in fields if k=="3" and wt=="var"), None)
                seg_summ.append(f"vid={vid} itag={itag}")
            if pt in (31, 57, 42):
                open(f"bodies/VP{idx}_part{pt}_{name}.bin", "wb").write(payload)
                print(f"[{name}] {ts} VP{idx} fields={interesting[:24]}", flush=True)

    # dump every non-media signaling part for offline decode
    o2 = 0
    while o2 < len(rs):
        try:
            pt2, o2 = uvar(rs, o2); sz2, o2 = uvar(rs, o2)
        except Exception:
            break
        pl = rs[o2:o2+sz2]; o2 += sz2
        if pt2 not in (20, 21, 22) and pl:
            open(f"bodies/VP{idx}_p{pt2}_{PART.get(pt2,'x')}.bin", "wb").write(pl)

    if seg_summ:
        print(f"[SEG] {ts} VP{idx} {' | '.join(seg_summ[:3])} resplen={len(rs)} census={CENSUS}", flush=True)
