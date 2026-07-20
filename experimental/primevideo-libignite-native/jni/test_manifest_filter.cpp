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

    // ── HLS: multiple ad runs — pre-roll(2) + mid-roll(3) interspersed ───────
    // A pre-roll run of 2 ad segments and a mid-roll run of 3, bracketed by
    // discontinuities around content. Covers: many segments removed across
    // multiple runs, and collapse of the doubled discontinuity each removed run
    // leaves behind — leaving exactly one boundary marker per former ad break.
    {
        std::string hls =
            "#EXTM3U\n"
            "#EXT-X-VERSION:6\n"
            "#EXT-X-TARGETDURATION:6\n"
            "#EXT-X-MEDIA-SEQUENCE:0\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_1/ad_a1.ts\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_1/ad_a2.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c1.ts\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c2.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_2/ad_b1.ts\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_2/ad_b2.ts\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_2/ad_b3.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c3.ts\n"
            "#EXT-X-ENDLIST\n";
        auto r = run(hls);

        // count non-overlapping occurrences of a token
        auto count = [](const std::string& s, const std::string& tok) {
            int n = 0; size_t p = 0;
            while ((p = s.find(tok, p)) != std::string::npos) { n++; p += tok.size(); }
            return n;
        };

        check(r.is_manifest, "HLS(multi) detected");
        check(r.modified, "HLS(multi) modified");
        check(r.ad_segments == 5, "HLS(multi) removed all 5 ad segments (2+3)");
        check(hls.find("/iad_") == std::string::npos, "HLS(multi) no /iad_ remains");
        check(hls.find("ad_a1") == std::string::npos && hls.find("ad_b3") == std::string::npos,
              "HLS(multi) removed ad URIs from both runs");
        check(count(hls, "content/c") == 3, "HLS(multi) kept all 3 content segments");
        size_t c1 = hls.find("c1.ts"), c2 = hls.find("c2.ts"), c3 = hls.find("c3.ts");
        check(c1 < c2 && c2 < c3, "HLS(multi) preserved content order");
        check(hls.find("#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY") == std::string::npos,
              "HLS(multi) collapsed all doubled discontinuities");
        // 4 original discontinuities minus one collapsed per removed run (2 runs) = 2
        check(count(hls, "#EXT-X-DISCONTINUITY") == 2, "HLS(multi) one boundary per former ad break");
        check(hls.find("#EXT-X-ENDLIST") != std::string::npos, "HLS(multi) kept ENDLIST");
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

    // ── DASH: nested child elements — marker buried deep in an ad period ─────
    // DASH <Period>s are siblings, never nested in each other; "nested" here
    // means each period wraps a deep AdaptationSet > Representation > BaseURL/
    // SegmentTemplate tree. Verifies the /iad_ marker is found several levels
    // down (whole-period text search, not just a top-level attr) and that
    // structurally rich content periods survive intact — including their own
    // deep children. Also guards a false positive: "AdaptationSet" contains
    // "ad" but not the "ad-"/"_ad_" id markers, so content must NOT be dropped.
    {
        std::string dash =
            "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n"
            "<Period id=\"content-a\">\n"
            "  <AdaptationSet mimeType=\"video/mp4\">\n"
            "    <Representation id=\"v0\" bandwidth=\"3000000\">\n"
            "      <BaseURL>https://cdn/content/a/</BaseURL>\n"
            "      <SegmentTemplate media=\"cseg-a-$Number$.m4s\"/>\n"
            "    </Representation>\n"
            "  </AdaptationSet>\n"
            "</Period>\n"
            "<Period id=\"break-1\">\n"
            "  <AdaptationSet mimeType=\"video/mp4\">\n"
            "    <Representation id=\"v0\" bandwidth=\"3000000\">\n"
            "      <BaseURL>https://cdn/iad_777/creative/</BaseURL>\n"   // marker 3 levels deep
            "      <SegmentTemplate media=\"advert-$Number$.m4s\"/>\n"
            "    </Representation>\n"
            "  </AdaptationSet>\n"
            "</Period>\n"
            "<Period id=\"content-b\">\n"
            "  <AdaptationSet mimeType=\"video/mp4\">\n"
            "    <Representation id=\"v0\" bandwidth=\"3000000\">\n"
            "      <BaseURL>https://cdn/content/b/</BaseURL>\n"
            "      <SegmentTemplate media=\"cseg-b-$Number$.m4s\"/>\n"
            "    </Representation>\n"
            "  </AdaptationSet>\n"
            "</Period>\n"
            "</MPD>\n";
        auto r = run(dash);
        check(r.is_manifest, "DASH(nested) detected");
        check(r.modified, "DASH(nested) modified");
        check(r.ad_periods == 1, "DASH(nested) removed exactly 1 ad period");
        check(dash.find("/iad_") == std::string::npos, "DASH(nested) marker buried deep still removed");
        // the removed ad period's own deep children are gone too
        check(dash.find("advert-") == std::string::npos, "DASH(nested) ad period's nested children removed");
        // both content periods and their deep children survive
        check(dash.find("content/a/") != std::string::npos, "DASH(nested) kept content-a BaseURL");
        check(dash.find("content/b/") != std::string::npos, "DASH(nested) kept content-b BaseURL");
        check(dash.find("cseg-a-") != std::string::npos && dash.find("cseg-b-") != std::string::npos,
              "DASH(nested) kept content periods' nested SegmentTemplates");
        // "AdaptationSet" ("ad" substring) must not trip the id ad-marker
        check(dash.find("content-a") != std::string::npos && dash.find("content-b") != std::string::npos,
              "DASH(nested) no false positive from AdaptationSet");
        // XML stays balanced: 2 periods in, 2 periods out
        auto count = [](const std::string& s, const std::string& tok) {
            int n = 0; size_t p = 0;
            while ((p = s.find(tok, p)) != std::string::npos) { n++; p += tok.size(); }
            return n;
        };
        check(count(dash, "<Period ") == 2 && count(dash, "</Period>") == 2,
              "DASH(nested) balanced <Period> open/close after strip");
        check(dash.find("</MPD>") != std::string::npos, "DASH(nested) still well-terminated");
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
