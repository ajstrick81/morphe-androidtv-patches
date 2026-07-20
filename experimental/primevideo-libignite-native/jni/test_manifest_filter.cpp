// test_manifest_filter.cpp — host-buildable unit test for the ad-strip logic.
// No Android/NDK needed; validates pvfilter::filter() on the PC before device.
//
//   g++ -std=c++17 -D_GNU_SOURCE test_manifest_filter.cpp manifest_filter.cpp -o t && ./t
//
// _GNU_SOURCE is for memmem(). Exits non-zero on any failed assertion.
#include "manifest_filter.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>

static pvfilter::FilterResult run(std::string& s) {
    // filter edits in place and only shrinks; give it a writable buffer.
    std::string buf = s;
    pvfilter::FilterResult r = pvfilter::filter(&buf[0], buf.size());
    buf.resize(r.new_len);
    s = buf;
    return r;
}

int main() {
    int failures = 0;
    auto check = [&](bool cond, const char* what) {
        if (!cond) { printf("FAIL: %s\n", what); failures++; }
        else       { printf("ok:   %s\n", what); }
    };

    // ── HLS: two content segments around one /iad_ ad segment ────────────────
    {
        std::string hls =
            "#EXTM3U\n"
            "#EXT-X-VERSION:6\n"
            "#EXT-X-TARGETDURATION:6\n"
            "#EXTINF:6.0,\n"
            "https://cdn.pv-cdn.net/content/seg1.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn.pv-cdn.net/iad_9931/ad_seg1.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn.pv-cdn.net/content/seg2.ts\n"
            "#EXT-X-ENDLIST\n";
        auto r = run(hls);
        check(r.is_manifest, "HLS detected");
        check(r.modified, "HLS modified");
        check(r.ad_segments == 1, "HLS removed exactly 1 ad segment");
        check(hls.find("/iad_") == std::string::npos, "HLS no /iad_ remains");
        check(hls.find("content/seg1.ts") != std::string::npos, "HLS kept content seg1");
        check(hls.find("content/seg2.ts") != std::string::npos, "HLS kept content seg2");
        // doubled discontinuity left by the removed ad should be collapsed
        check(hls.find("#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY") == std::string::npos,
              "HLS collapsed doubled discontinuity");
    }

    // ── HLS: no ads → untouched ──────────────────────────────────────────────
    {
        std::string hls =
            "#EXTM3U\n#EXTINF:6.0,\nhttps://cdn/content/a.ts\n#EXT-X-ENDLIST\n";
        std::string before = hls;
        auto r = run(hls);
        check(r.is_manifest, "HLS(clean) detected");
        check(!r.modified, "HLS(clean) untouched");
        check(hls == before, "HLS(clean) byte-identical");
    }

    // ── DASH: drop an ad <Period> carrying /iad_ in BaseURL ──────────────────
    {
        std::string dash =
            "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n"
            "<Period id=\"content-1\"><BaseURL>https://cdn/content/</BaseURL></Period>\n"
            "<Period id=\"ad-break-1\"><BaseURL>https://cdn/iad_5521/</BaseURL></Period>\n"
            "<Period id=\"content-2\"><BaseURL>https://cdn/content2/</BaseURL></Period>\n"
            "</MPD>\n";
        auto r = run(dash);
        check(r.is_manifest, "DASH detected");
        check(r.modified, "DASH modified");
        check(r.ad_periods == 1, "DASH removed exactly 1 ad period");
        check(dash.find("/iad_") == std::string::npos, "DASH no /iad_ remains");
        check(dash.find("content-1") != std::string::npos, "DASH kept content-1");
        check(dash.find("content-2") != std::string::npos, "DASH kept content-2");
        check(dash.find("</MPD>") != std::string::npos, "DASH still well-terminated");
    }

    // ── DASH: multi-period — pre-roll + mid-rolls, both detection paths ──────
    // Interleaves 3 content periods with 4 ad periods (one flagged by an
    // id="ad-…" with no /iad_ URL, two of them consecutive) to cover: multiple
    // removals in one pass, both ad signals, consecutive-ad handling, content
    // false-positive avoidance, and preserved ordering.
    {
        std::string dash =
            "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n"
            "<Period id=\"p0-content\"><BaseURL>https://cdn/content0/</BaseURL></Period>\n"
            "<Period id=\"p1-preroll\"><BaseURL>https://cdn/iad_100/</BaseURL></Period>\n"   // /iad_ path
            "<Period id=\"p2-content\"><BaseURL>https://cdn/content2/</BaseURL></Period>\n"
            "<Period id=\"ad-promo3\"><BaseURL>https://cdn/promo3/</BaseURL></Period>\n"      // id path, no /iad_
            "<Period id=\"p4-midroll\"><BaseURL>https://cdn/iad_400/</BaseURL></Period>\n"    // consecutive ad 1
            "<Period id=\"p5-midroll\"><BaseURL>https://cdn/iad_500/</BaseURL></Period>\n"    // consecutive ad 2
            "<Period id=\"p6-content\"><BaseURL>https://cdn/content6/</BaseURL></Period>\n"
            "</MPD>\n";
        auto r = run(dash);
        check(r.is_manifest, "DASH(multi) detected");
        check(r.modified, "DASH(multi) modified");
        check(r.ad_periods == 4, "DASH(multi) removed exactly 4 ad periods");
        check(dash.find("/iad_") == std::string::npos, "DASH(multi) no /iad_ remains");
        // id-path ad (no /iad_) actually removed, not just URL-path ads
        check(dash.find("promo3") == std::string::npos, "DASH(multi) removed id-flagged ad period");
        // consecutive ads both gone
        check(dash.find("iad_400") == std::string::npos && dash.find("iad_500") == std::string::npos,
              "DASH(multi) removed both consecutive ads");
        // all three content periods survive
        check(dash.find("content0") != std::string::npos, "DASH(multi) kept content0");
        check(dash.find("content2") != std::string::npos, "DASH(multi) kept content2");
        check(dash.find("content6") != std::string::npos, "DASH(multi) kept content6");
        // original relative order of content preserved
        size_t c0 = dash.find("content0"), c2 = dash.find("content2"), c6 = dash.find("content6");
        check(c0 < c2 && c2 < c6, "DASH(multi) preserved content period order");
        check(dash.find("</MPD>") != std::string::npos, "DASH(multi) still well-terminated");
    }

    // ── Non-manifest buffer → ignored ────────────────────────────────────────
    {
        std::string blob = "\x00\x01\x02 this is a video segment, not a manifest";
        std::string before = blob;
        auto r = run(blob);
        check(!r.is_manifest, "binary not treated as manifest");
        check(blob == before, "binary untouched");
    }

    printf("\n%s (%d failure(s))\n", failures ? "TESTS FAILED" : "ALL TESTS PASSED", failures);
    return failures ? 1 : 0;
}
