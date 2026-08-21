"""YTTV client-type spoof experiment: rewrite InnerTube context TVHTML5 -> WEB,
then watch whether the app drops into the client-side doubleclick ad-decision flow.

- request(): on youtubei calls, inspect + (optionally) rewrite the client context.
  Dumps the first player/browse body so we can see JSON-vs-protobuf + exact keys.
- response(): flag any contact with doubleclick / googleads / ad-decision hosts,
  and log adPlacements/playerAds keys appearing in /player.
"""
import json, os, time, binascii
from mitmproxy import http

os.makedirs("spoof", exist_ok=True)
SEEN = {"dumped": 0}
DO_REWRITE = os.environ.get("YTV_REWRITE", "0") == "1"   # start in OBSERVE mode

AD_HOSTS = ("doubleclick.net", "googleads", "googlesyndication", "g.doubleclick",
            "static.doubleclick", "pubads", "/pagead/", "/ptracking")

def request(flow: http.HTTPFlow):
    p = flow.request.path
    if "youtubei/v1/" not in p:
        return
    body = flow.request.raw_content or b""
    ct = flow.request.headers.get("content-type", "")
    is_json = body[:1] in (b"{", b"[") or "json" in ct
    # dump the first couple of player/browse requests for structure analysis
    if SEEN["dumped"] < 4 and ("/player" in p or "/browse" in p or "/next" in p):
        SEEN["dumped"] += 1
        tag = p.split("?")[0].strip("/").replace("/", "_")
        open(f"spoof/req_{SEEN['dumped']}_{tag}.bin", "wb").write(body)
        cn = None
        if is_json:
            try:
                j = json.loads(body.decode("utf-8", "replace"))
                cn = (((j.get("context") or {}).get("client") or {}).get("clientName"))
                cv = (((j.get("context") or {}).get("client") or {}).get("clientVersion"))
                print(f"[REQ] {p.split('?')[0]} JSON clientName={cn} clientVersion={cv}", flush=True)
            except Exception as e:
                print(f"[REQ] {p.split('?')[0]} json-parse-fail {e} head={binascii.hexlify(body[:32]).decode()}", flush=True)
        else:
            print(f"[REQ] {p.split('?')[0]} NON-JSON ct={ct} head={binascii.hexlify(body[:48]).decode()}", flush=True)

    # scope rewrite to playback-setup calls only (leave browse/UI intact)
    if DO_REWRITE and is_json and ("/next" in p or "/player" in p) and "heartbeat" not in p:
        try:
            j = json.loads(body.decode("utf-8", "replace"))
            cl = (j.get("context") or {}).get("client") or {}
            old = cl.get("clientName")
            if old in ("TVHTML5", "TVHTML5_SIMPLY", "TVHTML5_UNPLUGGED"):
                cl["clientName"] = os.environ.get("YTV_TARGET_CLIENT", "WEB")
                cl["clientVersion"] = os.environ.get("YTV_TARGET_VER", "2.20240620.05.00")
                j["context"]["client"] = cl
                flow.request.raw_content = json.dumps(j).encode()
                print(f"[SPOOF] {p.split('?')[0]} clientName {old} -> {cl['clientName']}", flush=True)
        except Exception as e:
            print(f"[SPOOF] rewrite-fail {e}", flush=True)

def response(flow: http.HTTPFlow):
    h = flow.request.pretty_host; p = flow.request.path
    low = (h + p).lower()
    if any(a in low for a in AD_HOSTS):
        print(f"[AD-HOST] {time.strftime('%H:%M:%S')} {flow.request.method} {h}{p.split('?')[0]}", flush=True)
        return
    if "youtubei/v1/player" in p and "heartbeat" not in p:
        try:
            txt = flow.response.get_text() or ""
            hits = [k for k in ("adPlacements","playerAds","adSlots","adBreakHeadsUpParams") if k in txt]
            if hits:
                open(f"spoof/PLAYER_ADS_{int(time.time())}.json","w",encoding="utf-8").write(txt)
                print(f"[PLAYER-ADS] {time.strftime('%H:%M:%S')} keys={hits}", flush=True)
            else:
                print(f"[PLAYER] {time.strftime('%H:%M:%S')} no ad keys (len={len(txt)})", flush=True)
        except Exception as e:
            print("player-parse", e, flush=True)
