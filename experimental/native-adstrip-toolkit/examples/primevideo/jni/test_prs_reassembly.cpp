// Host tests for PrsReassembler — the whole-body PRS strip that reproduces the
// MITM rig's result for TV shows in-process.
//
//   g++ -std=c++17 -D_GNU_SOURCE -Wall test_prs_reassembly.cpp prs_filter.cpp manifest_filter.cpp prs_blank.cpp -lz -o t && ./t
//
// Exits non-zero on any failed assertion; one `ok:` per passing check.
//
// The load-bearing tests:
//   * chunk-boundary invariance — for ANY split of the body, the served bytes
//     equal filtering the whole body once (this is the correctness guarantee);
//   * the TV fix demonstration — a playlist large enough that NO single chunk
//     holds the complete array: per-chunk `prs_blank` strips 0 (the bench's
//     TV regression), the reassembler strips every ad (the rig's whole-body
//     result).

#include "prs_reassembly.h"
#include "prs_blank.h"

#include <cstdio>
#include <string>
#include <vector>

static int g_fail = 0, g_ok = 0;
#define CHECK(cond, what) do { \
    if (!(cond)) { std::printf("FAIL: %s  (%s:%d)\n", (what), __FILE__, __LINE__); ++g_fail; } \
    else { std::printf("ok:   %s\n", (what)); ++g_ok; } \
} while (0)

using pvfilter::PrsReassembler;

// Drive the reassembler exactly as the hook contract says: feed while
// Undecided/Buffering; the moment it decides Passthrough, stop feeding and let
// the remaining bytes pass through directly. Return the fully re-served body.
static std::string drive(const std::string& body, size_t chunk) {
    PrsReassembler r;
    size_t pos = 0, n = body.size();
    while (pos < n && r.mode() != PrsReassembler::Mode::Passthrough) {
        size_t len = std::min(chunk, n - pos);
        r.feed(body.data() + pos, len);
        pos += len;
    }
    std::string tail = body.substr(pos);   // bytes never fed (post-Passthrough)
    r.finish();
    std::string out;
    char tmp[4096];
    for (;;) { size_t g = r.serve(tmp, sizeof tmp); if (!g) break; out.append(tmp, g); }
    out += tail;
    return out;
}

// Reference: filter the WHOLE body once (what the rig did off-device).
static std::string filter_whole(const std::string& body) {
    std::vector<char> b(body.begin(), body.end());
    pvfilter::FilterResult r = pvfilter::filter_prs(b.data(), b.size());
    return std::string(b.data(), r.new_len);
}
static bool has(const std::string& s, const std::string& sub) {
    return s.find(sub) != std::string::npos;
}

static std::string prs(const std::string& arrayBody) {
    return "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":" + arrayBody +
           ",\"nonLinearAds\":{\"x\":1},\"z\":true}";
}
static const std::string MAIN0 = "{\"type\":\"Main\",\"startMs\":0,\"urls\":[\"https://x/a.mpd\"]}";
static const std::string MAIN1 = "{\"type\":\"Main\",\"startMs\":100,\"urls\":[\"https://x/b.mpd\"]}";
// A Remote whose payload carries ']' '}' ',' and an escaped quote inside a
// string, so chunk splits land inside escapes/strings/brackets.
static const std::string REM  = "{\"type\":\"Remote\",\"u\":\"htt]p://ad,x}\\\"q\",\"dur\":15000}";
static const std::string REM2 = "{\"type\":\"Remote\",\"adId\":\"iad_2\",\"dur\":30000}";

int main() {
    // 1) single read == whole-body filter; ad gone
    {
        std::string in = prs("[" + MAIN0 + "," + REM + "," + MAIN1 + "]");
        std::string out = drive(in, in.size());
        CHECK(out == filter_whole(in), "single: == whole-body filter");
        CHECK(!has(out, "\"Remote\""), "single: no Remote left");
        CHECK(has(out, "a.mpd") && has(out, "b.mpd"), "single: both Mains survive");
    }

    // 2) *** chunk-boundary invariance: EVERY split size on a tricky body ***
    {
        std::string in = prs("[" + MAIN0 + "," + REM + "," + MAIN1 + "," + REM2 + "]");
        std::string ref = filter_whole(in);
        bool allEqual = true;
        size_t firstBad = 0;
        for (size_t k = 1; k <= in.size(); ++k) {
            if (drive(in, k) != ref) { allEqual = false; firstBad = k; break; }
        }
        if (!allEqual) std::printf("   first divergent chunk size: %zu\n", firstBad);
        CHECK(allEqual, "invariance: every chunk size == whole-body filter");
        CHECK(!has(ref, "\"Remote\""), "invariance: reference has no Remote");
    }

    // 3) clean PRS (no Remote) -> served == whole-body filter (filter_prs still
    //    empties nonLinearAds, so it is not byte-identical to input — that is
    //    filter_prs's job, faithfully carried through the reassembler).
    {
        std::string in = prs("[" + MAIN0 + "," + MAIN1 + "]");
        std::string ref = filter_whole(in);
        for (size_t k : {1u, 7u, 64u, (unsigned)in.size()}) {
            CHECK(drive(in, k) == ref, "clean: == whole-body filter");
        }
        CHECK(has(ref, "\"nonLinearAds\":{}"), "clean: nonLinearAds emptied end-to-end");
        CHECK(has(ref, "a.mpd") && has(ref, "b.mpd"), "clean: Mains survive");
    }

    // 3b) true no-op PRS (no Remote, nonLinearAds already {}) -> byte-identical
    {
        std::string in = "{\"intraTitlePlaylist\":[" + MAIN0 + "," + MAIN1 +
                         "],\"nonLinearAds\":{}}";
        for (size_t k : {1u, 9u, (unsigned)in.size()}) {
            CHECK(drive(in, k) == in, "noop: served byte-identical");
        }
    }

    // 4) media segment (not '{') -> Passthrough, byte-identical, never buffered/filtered
    {
        std::string seg = std::string("\x00\x00\x00\x18" "ftyp", 8) + std::string(500, 'M');
        std::string out = drive(seg, 4);
        CHECK(out == seg, "media: passthrough byte-identical");
        PrsReassembler r; r.feed(seg.data(), 4);
        CHECK(r.mode() == PrsReassembler::Mode::Passthrough, "media: decided Passthrough on first bytes");
    }

    // 5) non-PRS JSON ('{' but no intraTitlePlaylist) -> served unchanged
    {
        std::string j = "{\"returnCode\":\"SUCCESS\",\"other\":[1,2,3],\"k\":\"v\"}";
        for (size_t k : {1u, 5u, (unsigned)j.size()}) {
            std::string out = drive(j, k);
            CHECK(out == j, "nonprs: served byte-identical");
        }
    }

    // 6) empty body -> no crash, empty out
    {
        std::string out = drive("", 4);
        CHECK(out.empty(), "empty: empty out, no crash");
    }

    // ── build a TV-sized playlist: large enough that no 4KB chunk holds the
    //    whole array, and large enough to exceed prs_blank's 256KB gate. ──
    std::string bigMain =
        "{\"type\":\"Main\",\"startMs\":0,\"urls\":[\"https://cdn/seg-" +
        std::string(2000, 'x') + ".mpd\"]}";          // ~2KB each
    int adsExpected = 0, elems = 0;
    std::string body = "[";
    for (int i = 0; i < 300; ++i) {                    // ~225 * ~2KB > 400KB
        if (i) body += ",";
        if (i % 4 == 2) { body += REM2; ++adsExpected; }   // ~75 ads sprinkled
        else            { body += bigMain; }
        ++elems;
    }
    body += "]";
    std::string bigPrs = prs(body);

    // 7) TV-sized invariance across representative chunk sizes
    {
        std::string ref = filter_whole(bigPrs);
        bool ok = true;
        for (size_t k : {1u, 2u, 3u, 13u, 64u, 1000u, 4096u, 65536u, (unsigned)bigPrs.size()}) {
            if (drive(bigPrs, k) != ref) { ok = false; std::printf("   divergent at k=%zu\n", k); break; }
        }
        CHECK(bigPrs.size() > 300000, "tv: fixture over 300KB");
        CHECK(ok, "tv: reassembled == whole-body filter across chunk sizes");
        CHECK(!has(ref, "\"Remote\""), "tv: no Remote left after strip");
    }

    // 8) *** THE FIX ***: no single 4KB chunk can be stripped by the push-seam
    //    blanker (that's the bench's TV regression), yet the reassembler strips
    //    every ad.
    {
        int perChunkBlanked = 0;
        for (size_t pos = 0; pos < bigPrs.size(); pos += 4096) {
            size_t len = std::min<size_t>(4096, bigPrs.size() - pos);
            std::vector<char> c(bigPrs.begin() + pos, bigPrs.begin() + pos + len);
            // gate off so size isn't the reason it skips — truncation is.
            perChunkBlanked += pvblank::blank_prs(c.data(), c.size(), false).remote_blanked;
        }
        CHECK(perChunkBlanked == 0, "fix: per-4KB-chunk blanker strips 0 (the TV regression)");

        // And confirm the size gate alone would also have skipped the whole copy.
        CHECK(bigPrs.size() > pvblank::kMaxScan, "fix: whole body exceeds the 256KB push-seam gate");

        // The reassembler recovers every ad.
        PrsReassembler r;
        for (size_t pos = 0; pos < bigPrs.size(); pos += 4096) {
            size_t len = std::min<size_t>(4096, bigPrs.size() - pos);
            r.feed(bigPrs.data() + pos, len);
        }
        r.finish();
        CHECK(r.result().remote_items == adsExpected, "fix: reassembler strips ALL ads (whole-body)");
        std::string out; char tmp[8192];
        for (;;) { size_t g = r.serve(tmp, sizeof tmp); if (!g) break; out.append(tmp, g); }
        CHECK(!has(out, "\"Remote\""), "fix: served body has no Remote");
        CHECK(has(out, "seg-"), "fix: content segments survive");
    }

    std::printf("\n%s (%d check(s), %d failure(s))\n",
                g_fail ? "TESTS FAILED" : "ALL TESTS PASSED", g_ok + g_fail, g_fail);
    return g_fail ? 1 : 0;
}
