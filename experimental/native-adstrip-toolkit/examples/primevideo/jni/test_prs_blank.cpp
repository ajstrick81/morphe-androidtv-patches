// Host tests for prs_blank — the in-place, same-length PRS ad blanker.
//
//   g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_blank.cpp prs_blank.cpp -o t && ./t
//
// Exits non-zero on any failed assertion. Prints one `ok:` per passing check so
// a green run is self-evidently non-vacuous (the bench-yesterday lesson: never
// trust a "0 failures" you can't see execute).
//
// The safety-critical cases here are the TRUNCATION ones: a truncated buffer
// MUST come back byte-for-byte untouched (blanking a partial chunk = black
// screen). A real JSON validator (below) backs the "JSON stays valid" claim
// instead of substring spot-checks.

#include "prs_blank.h"

#include <cstdio>
#include <string>
#include <vector>

static int g_fail = 0;
static int g_ok = 0;
#define CHECK(cond, what) do { \
    if (!(cond)) { std::printf("FAIL: %s  (%s:%d)\n", (what), __FILE__, __LINE__); ++g_fail; } \
    else { std::printf("ok:   %s\n", (what)); ++g_ok; } \
} while (0)

// ── a minimal but real JSON validator (objects/arrays/strings/num/kw/ws) ──────
// Rejects trailing commas, leading commas, empty elements — exactly the failure
// modes a same-length blank could introduce. Not a full RFC 8259 impl (numbers
// are lax), but strict about structure, which is what we are testing.
namespace jv {
static bool value(const std::string& s, size_t& i);
static void ws(const std::string& s, size_t& i) {
    while (i < s.size() && (s[i]==' '||s[i]=='\t'||s[i]=='\n'||s[i]=='\r')) ++i;
}
static bool str(const std::string& s, size_t& i) {
    if (i >= s.size() || s[i] != '"') return false;
    for (++i; i < s.size(); ++i) {
        if (s[i]=='\\') { if (++i >= s.size()) return false; continue; }
        if (s[i]=='"') { ++i; return true; }
    }
    return false;
}
static bool arr(const std::string& s, size_t& i) {
    ++i; ws(s, i);
    if (i < s.size() && s[i]==']') { ++i; return true; }   // empty array
    while (true) {
        if (!value(s, i)) return false;
        ws(s, i);
        if (i >= s.size()) return false;
        if (s[i]==',') { ++i; ws(s, i); continue; }        // MUST be another value
        if (s[i]==']') { ++i; return true; }
        return false;
    }
}
static bool obj(const std::string& s, size_t& i) {
    ++i; ws(s, i);
    if (i < s.size() && s[i]=='}') { ++i; return true; }
    while (true) {
        ws(s, i);
        if (!str(s, i)) return false;                      // key
        ws(s, i);
        if (i >= s.size() || s[i] != ':') return false;
        ++i; ws(s, i);
        if (!value(s, i)) return false;
        ws(s, i);
        if (i >= s.size()) return false;
        if (s[i]==',') { ++i; continue; }
        if (s[i]=='}') { ++i; return true; }
        return false;
    }
}
static bool keyword(const std::string& s, size_t& i, const char* kw) {
    size_t n = 0; while (kw[n]) ++n;
    if (s.compare(i, n, kw) == 0) { i += n; return true; }
    return false;
}
static bool value(const std::string& s, size_t& i) {
    ws(s, i);
    if (i >= s.size()) return false;
    char c = s[i];
    if (c=='"') return str(s, i);
    if (c=='[') return arr(s, i);
    if (c=='{') return obj(s, i);
    if (c=='t') return keyword(s, i, "true");
    if (c=='f') return keyword(s, i, "false");
    if (c=='n') return keyword(s, i, "null");
    if (c=='-' || (c>='0'&&c<='9')) {                       // lax number
        if (c=='-') ++i;
        bool any=false;
        while (i<s.size() && ((s[i]>='0'&&s[i]<='9')||s[i]=='.'||s[i]=='e'||s[i]=='E'||s[i]=='+'||s[i]=='-')) { ++i; any=true; }
        return any;
    }
    return false;
}
static bool valid(const std::string& s) {
    size_t i = 0; if (!value(s, i)) return false; ws(s, i); return i == s.size();
}
} // namespace jv

// self-test the validator so we don't trust a broken oracle
static void validator_selftest() {
    CHECK(jv::valid("[1,2,3]"), "jv: accepts [1,2,3]");
    CHECK(jv::valid("[  1 , 2  ]"), "jv: accepts spaced array");
    CHECK(jv::valid("[   ]"), "jv: accepts all-space array (empty)");
    CHECK(jv::valid("{\"a\":[{\"b\":\"x]}\"}]}"), "jv: accepts brackets inside string");
    CHECK(!jv::valid("[1,]"), "jv: REJECTS trailing comma");
    CHECK(!jv::valid("[,1]"), "jv: REJECTS leading comma");
    CHECK(!jv::valid("[1,,2]"), "jv: REJECTS empty element");
    CHECK(!jv::valid("[1,2"), "jv: REJECTS unclosed array");
    CHECK(!jv::valid("{\"a\":1,}"), "jv: REJECTS trailing comma in object");
}

// run blank_prs on a mutable copy; return result + the resulting bytes.
struct Run { pvblank::BlankResult r; std::string out; size_t in_len; };
static Run run(const std::string& in, bool gate = false) {
    std::vector<char> buf(in.begin(), in.end());
    Run rr;
    rr.in_len = in.size();
    rr.r = pvblank::blank_prs(buf.data(), buf.size(), gate);
    rr.out.assign(buf.data(), buf.size());
    return rr;
}
static bool has(const std::string& s, const std::string& sub) {
    return s.find(sub) != std::string::npos;
}

// A realistic-ish PRS wrapper around a given intraTitlePlaylist array body.
static std::string prs(const std::string& arrayBody) {
    return "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":" + arrayBody +
           ",\"nonLinearAds\":{\"x\":1},\"z\":true}";
}
static const std::string MAIN0 = "{\"type\":\"Main\",\"startMs\":0,\"urls\":[\"https://x/a.mpd\"]}";
static const std::string MAIN1 = "{\"type\":\"Main\",\"startMs\":100,\"urls\":[\"https://x/b.mpd\"]}";
static const std::string REM   = "{\"type\":\"Remote\",\"adId\":\"iad_1\",\"dur\":15000}";
static const std::string REM2  = "{\"type\":\"Remote\",\"adId\":\"iad_2\",\"dur\":30000}";

int main() {
    validator_selftest();

    // Every run must preserve buffer length. Checked inline per case AND here as
    // a blanket invariant across a sampling of shapes.
    {
        const char* shapes[] = {
            "[]", "[  ]", "not json at all, no marker here",
        };
        for (const char* sh : shapes) {
            Run x = run(sh);
            CHECK(x.out.size() == x.in_len, "invariant: length unchanged");
        }
    }

    // 1) marker absent -> no-op
    {
        Run x = run("{\"returnCode\":\"SUCCESS\",\"other\":[1,2,3]}");
        CHECK(!x.r.marker_found, "no-marker: not found");
        CHECK(!x.r.blanked && x.r.remote_blanked == 0, "no-marker: nothing blanked");
        CHECK(x.out == "{\"returnCode\":\"SUCCESS\",\"other\":[1,2,3]}", "no-marker: untouched");
    }

    // 2) clean playlist (no Remote) -> complete, untouched, byte-identical
    {
        std::string in = prs("[" + MAIN0 + "," + MAIN1 + "]");
        Run x = run(in);
        CHECK(x.r.marker_found, "clean: marker found");
        CHECK(x.r.complete, "clean: complete");
        CHECK(!x.r.blanked && x.r.remote_blanked == 0, "clean: nothing blanked");
        CHECK(x.out == in, "clean: byte-identical");
        CHECK(jv::valid(x.out), "clean: still valid JSON");
    }

    // 3) middle Remote -> blanked, both Mains kept, valid JSON, same length
    {
        std::string in = prs("[" + MAIN0 + "," + REM + "," + MAIN1 + "]");
        Run x = run(in);
        CHECK(x.r.complete, "middle: complete");
        CHECK(x.r.remote_blanked == 1, "middle: 1 Remote blanked");
        CHECK(x.out.size() == in.size(), "middle: same length");
        CHECK(!has(x.out, "\"Remote\""), "middle: no Remote text left");
        CHECK(has(x.out, "a.mpd") && has(x.out, "b.mpd"), "middle: both Mains survive");
        CHECK(jv::valid(x.out), "middle: valid JSON");
    }

    // 4) first Remote -> no leading comma
    {
        std::string in = prs("[" + REM + "," + MAIN0 + "," + MAIN1 + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 1, "first: 1 blanked");
        CHECK(jv::valid(x.out), "first: valid JSON (no leading comma)");
        CHECK(has(x.out, "a.mpd") && has(x.out, "b.mpd"), "first: Mains survive");
    }

    // 5) last (single) Remote -> no trailing comma
    {
        std::string in = prs("[" + MAIN0 + "," + MAIN1 + "," + REM + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 1, "last: 1 blanked");
        CHECK(jv::valid(x.out), "last: valid JSON (no trailing comma)");
        CHECK(has(x.out, "a.mpd") && has(x.out, "b.mpd"), "last: Mains survive");
    }

    // 6) *** the bench's latent bug ***: two consecutive Remotes at the TAIL.
    //    JS blankRanges would leave `[Main,Main, ]` (trailing comma). The
    //    corrected comma logic must produce valid JSON.
    {
        std::string in = prs("[" + MAIN0 + "," + MAIN1 + "," + REM + "," + REM2 + "]");
        Run x = run(in);
        CHECK(x.r.complete, "tail2: complete");
        CHECK(x.r.remote_blanked == 2, "tail2: 2 blanked");
        CHECK(jv::valid(x.out), "tail2: valid JSON (regression: no trailing comma)");
        CHECK(has(x.out, "a.mpd") && has(x.out, "b.mpd"), "tail2: both Mains survive");
        CHECK(!has(x.out, "\"Remote\""), "tail2: no Remote text left");
    }

    // 7) two consecutive Remotes at the HEAD -> no leading comma
    {
        std::string in = prs("[" + REM + "," + REM2 + "," + MAIN0 + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 2, "head2: 2 blanked");
        CHECK(jv::valid(x.out), "head2: valid JSON (no leading comma)");
        CHECK(has(x.out, "a.mpd"), "head2: Main survives");
    }

    // 8) all Remote -> valid empty array
    {
        std::string in = prs("[" + REM + "," + REM2 + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 2, "all-ad: 2 blanked");
        CHECK(jv::valid(x.out), "all-ad: valid JSON (empty array)");
        CHECK(!has(x.out, "\"Remote\""), "all-ad: no Remote text left");
    }

    // 9) *** SAFETY: truncated array (no closing ']') -> UNTOUCHED ***
    {
        // A real chunk-copy that ends mid-array: no closing ']' for the playlist.
        std::string in = "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":[" +
                         MAIN0 + "," + REM + "," + MAIN1;   // <- stops here, no ']'
        Run x = run(in);
        CHECK(x.r.marker_found, "trunc-array: marker still found");
        CHECK(!x.r.complete, "trunc-array: NOT complete");
        CHECK(!x.r.blanked && x.r.remote_blanked == 0, "trunc-array: nothing blanked");
        CHECK(x.out == in, "trunc-array: byte-identical (black-screen safety)");
    }

    // 10) *** SAFETY: truncated Main element (unterminated string) -> UNTOUCHED ***
    {
        // ...,{"type":"Main","urls":["https://x/really-long-  <chopped mid-string>
        std::string in = "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":[" +
                         REM + ",{\"type\":\"Main\",\"urls\":[\"https://x/really-long";
        Run x = run(in);
        CHECK(x.r.marker_found, "trunc-elem: marker found");
        CHECK(!x.r.complete, "trunc-elem: NOT complete");
        CHECK(!x.r.blanked, "trunc-elem: nothing blanked (even though a whole Remote precedes)");
        CHECK(x.out == in, "trunc-elem: byte-identical");
    }

    // 11) *** SAFETY: truncated INSIDE a Remote element -> UNTOUCHED ***
    {
        std::string in = "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":[" +
                         MAIN0 + ",{\"type\":\"Remote\",\"adId\":\"iad_";
        Run x = run(in);
        CHECK(!x.r.complete, "trunc-remote: NOT complete");
        CHECK(x.out == in, "trunc-remote: byte-identical");
    }

    // 12) whitespace around the type colon: `"type" : "Remote"` still detected
    {
        std::string rem_ws = "{\"type\" :  \"Remote\",\"adId\":\"iad_9\"}";
        std::string in = prs("[" + MAIN0 + "," + rem_ws + "," + MAIN1 + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 1, "ws-type: detected despite spaces");
        CHECK(jv::valid(x.out), "ws-type: valid JSON");
    }

    // 13) Main whose URL string contains ']' '}' ',' -> scan_val string-aware,
    //     element survives, not misparsed
    {
        std::string tricky = "{\"type\":\"Main\",\"u\":\"htt]p://x/a,b}.mpd\"}";
        std::string in = prs("[" + tricky + "," + REM + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 1, "tricky: 1 Remote blanked");
        CHECK(has(x.out, "htt]p://x/a,b}.mpd"), "tricky: Main string with brackets survives");
        CHECK(jv::valid(x.out), "tricky: valid JSON");
    }

    // 14) a value "RemoteX" / "Main" must NOT be treated as Remote
    {
        std::string notrem = "{\"type\":\"RemoteControl\",\"k\":1}";
        std::string in = prs("[" + MAIN0 + "," + notrem + "]");
        Run x = run(in);
        CHECK(x.r.remote_blanked == 0, "notRemote: RemoteControl not removed");
        CHECK(x.out == in, "notRemote: untouched");
    }

    // 15) idempotence: a second pass blanks nothing and stays valid
    {
        std::string in = prs("[" + MAIN0 + "," + REM + "," + MAIN1 + "," + REM2 + "]");
        Run x1 = run(in);
        CHECK(x1.r.remote_blanked == 2, "idem: first pass blanks 2");
        Run x2 = run(x1.out);
        CHECK(x2.r.remote_blanked == 0, "idem: second pass blanks 0");
        CHECK(x2.out == x1.out, "idem: second pass no-op");
        CHECK(jv::valid(x2.out), "idem: still valid JSON");
    }

    // 16) size gate: below kMinScan skipped when gate on; processed when off
    {
        std::string in = prs("[" + MAIN0 + "," + REM + "]");   // < 512 bytes
        CHECK(in.size() < pvblank::kMinScan, "gate: fixture is under kMinScan");
        Run on  = run(in, /*gate=*/true);
        CHECK(!on.r.marker_found && on.out == in, "gate-on: skipped, untouched");
        Run off = run(in, /*gate=*/false);
        CHECK(off.r.remote_blanked == 1, "gate-off: processed");
    }

    // 17) size gate upper bound: a buffer over kMaxScan is skipped
    {
        std::string body = "[" + MAIN0 + "," + REM + "," + MAIN1 + "]";
        std::string in = prs(body);
        in += std::string(pvblank::kMaxScan, ' ');            // pad past the ceiling
        Run x = run(in, /*gate=*/true);
        CHECK(x.in_len > pvblank::kMaxScan, "gate-hi: fixture over kMaxScan");
        CHECK(!x.r.marker_found, "gate-hi: skipped (over ceiling)");
        CHECK(x.out == in, "gate-hi: untouched");
    }

    // 18) large realistic-ish body still same length + valid after strip
    {
        std::string body = "[";
        for (int i = 0; i < 40; ++i) {
            if (i) body += ",";
            body += (i % 3 == 1) ? REM : MAIN0;               // ~13 ads sprinkled in
        }
        body += "]";
        std::string in = prs(body);
        Run x = run(in, /*gate=*/false);
        CHECK(x.r.complete, "big: complete");
        CHECK(x.r.remote_blanked == 13, "big: 13 ads blanked");
        CHECK(x.out.size() == in.size(), "big: same length");
        CHECK(!has(x.out, "\"Remote\""), "big: no Remote text left");
        CHECK(jv::valid(x.out), "big: valid JSON");
    }

    std::printf("\n%s (%d check(s), %d failure(s))\n",
                g_fail ? "TESTS FAILED" : "ALL TESTS PASSED", g_ok + g_fail, g_fail);
    return g_fail ? 1 : 0;
}
