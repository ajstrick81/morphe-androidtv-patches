// test_manifest_filter.cpp — host-buildable unit test for the ad-strip logic.
// No Android/NDK needed; validates pvfilter::filter() on the PC before device.
//
//   g++ -std=c++17 -D_GNU_SOURCE test_manifest_filter.cpp manifest_filter.cpp -lz -o t && ./t
//
// _GNU_SOURCE is for memmem(); -lz is for the gzip round-trip in the inflate-
// path test (host convenience only — the shipped .so hooks the app's zlib and
// links none of its own). Exits non-zero on any failed assertion.
#include "manifest_filter.h"
#include "inflate_filter.h"
#include "ssl_reassembly.h"

#include <zlib.h>

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>

// gzip-compress a string (Content-Encoding: gzip is how manifests arrive
// compressed on the wire). windowBits 15|16 selects the gzip wrapper.
static std::string gzip_compress(const std::string& in) {
    z_stream zs; memset(&zs, 0, sizeof(zs));
    if (deflateInit2(&zs, Z_BEST_COMPRESSION, Z_DEFLATED, 15 | 16, 8,
                     Z_DEFAULT_STRATEGY) != Z_OK) return {};
    zs.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(in.data()));
    zs.avail_in = static_cast<uInt>(in.size());
    std::string out;
    char buf[16384];
    int rc;
    do {
        zs.next_out = reinterpret_cast<Bytef*>(buf);
        zs.avail_out = sizeof(buf);
        rc = deflate(&zs, Z_FINISH);
        out.append(buf, sizeof(buf) - zs.avail_out);
    } while (rc != Z_STREAM_END);
    deflateEnd(&zs);
    return out;
}

// Feed `input` through an SslReassembler in fixed-size chunks, finish, then
// serve the result back in fixed-size chunks. Models the SSL_read hook draining
// TLS records and re-serving the filtered body. Returns the served bytes.
static std::string reassemble_chunked(const std::string& input,
                                      size_t feed_chunk, size_t serve_chunk) {
    pvfilter::SslReassembler ra;
    for (size_t i = 0; i < input.size(); i += feed_chunk) {
        size_t n = input.size() - i;
        if (n > feed_chunk) n = feed_chunk;
        ra.feed(input.data() + i, n);
    }
    ra.finish();
    std::string out;
    char buf[8192];
    size_t cap = serve_chunk < sizeof(buf) ? serve_chunk : sizeof(buf);
    size_t n;
    while ((n = ra.serve(buf, cap)) > 0) out.append(buf, n);
    return out;
}

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

    // ── inflate path — gzip'd manifest, via real zlib + the shipped helper ───
    // Reproduces exactly what hook_inflate does: the app hands zlib a compressed
    // manifest; inflate() decompresses it into an output buffer; the hook then
    // calls apply_after_inflate() to strip ads from the just-produced region and
    // rewind the z_stream. We drive real inflate() and the SAME helper the hook
    // ships, then assert (a) content is stripped and (b) the stream's output
    // bookkeeping (next_out / avail_out / total_out) stays self-consistent.
    {
        const std::string manifest =
            "#EXTM3U\n"
            "#EXT-X-VERSION:6\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c1.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_88/ad1.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c2.ts\n"
            "#EXT-X-ENDLIST\n";

        // What the direct (SSL_read) path would produce — the equivalence oracle.
        std::string direct = manifest;
        run(direct);

        std::string gz = gzip_compress(manifest);
        check(!gz.empty() && gz.size() < manifest.size(), "inflate: manifest gzip-compressed");

        // Decompress through real zlib into an output buffer, exactly like the app.
        z_stream zs; memset(&zs, 0, sizeof(zs));
        check(inflateInit2(&zs, 15 | 32) == Z_OK, "inflate: inflateInit2 ok");  // 15|32 = auto gzip/zlib
        const uInt OUTCAP = 8192;
        unsigned char outbuf[OUTCAP];
        zs.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(gz.data()));
        zs.avail_in = static_cast<uInt>(gz.size());
        zs.next_out = outbuf;
        zs.avail_out = OUTCAP;

        unsigned char* out_before = zs.next_out;          // captured pre-inflate, as the hook does
        int ret = inflate(&zs, Z_FINISH);
        check(ret == Z_STREAM_END, "inflate: decompressed to stream end");

        // Drive the SHIPPED helper (reinterpret the real z_stream as ZStreamHead,
        // exactly as hook_inflate casts the app's z_streamp).
        auto* head = reinterpret_cast<pvfilter::ZStreamHead*>(&zs);
        pvfilter::FilterResult r = pvfilter::apply_after_inflate(head, out_before);

        check(r.is_manifest, "inflate: recognized decompressed manifest");
        check(r.modified, "inflate: stripped ad from decompressed body");
        check(r.ad_segments == 1, "inflate: removed exactly 1 ad segment");

        std::string produced(reinterpret_cast<char*>(outbuf), r.new_len);
        check(produced.find("/iad_") == std::string::npos, "inflate: no /iad_ in output");
        check(produced.find("content/c1.ts") != std::string::npos &&
              produced.find("content/c2.ts") != std::string::npos, "inflate: kept content segments");

        // Equivalence: inflate path == direct SSL_read path, byte for byte.
        check(produced == direct, "inflate: output identical to direct-path filter");

        // z_stream bookkeeping self-consistency after the rewind:
        check(zs.total_out == r.new_len, "inflate: total_out == stripped length");
        check(head->next_out == out_before + r.new_len, "inflate: next_out rewound to end of stripped body");
        check(zs.avail_out == OUTCAP - r.new_len, "inflate: avail_out reflects freed space");

        inflateEnd(&zs);
    }

    // ── chunked SSL_read reassembly ──────────────────────────────────────────
    // A manifest split across many TLS records: reassemble, filter the complete
    // body, re-serve. The key guarantee is chunk-boundary invariance — no matter
    // where the boundaries fall (including inside "/iad_", inside an EXTINF, or
    // inside a URI), the served result equals the single-shot direct filter.
    {
        const std::string manifest =
            "#EXTM3U\n"
            "#EXT-X-VERSION:6\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c1.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/iad_88/ad1.ts\n"
            "#EXT-X-DISCONTINUITY\n"
            "#EXTINF:6.0,\n"
            "https://cdn/content/c2.ts\n"
            "#EXT-X-ENDLIST\n";
        std::string direct = manifest;
        run(direct);

        // Exhaustive feed-boundary invariance: EVERY feed-chunk size 1..len
        // places a boundary at every offset, so this splits "/iad_", EXTINF
        // lines, and URIs at all positions.
        bool feed_ok = true;
        size_t bad_fc = 0;
        for (size_t fc = 1; fc <= manifest.size(); ++fc) {
            if (reassemble_chunked(manifest, fc, 7) != direct) { feed_ok = false; bad_fc = fc; break; }
        }
        check(feed_ok, "SSL(chunk): every feed-chunk size reassembles to direct-filter output");
        if (!feed_ok) printf("      first failing feed_chunk=%zu\n", bad_fc);

        // Serve-side chunking invariance: dole the filtered body out in every
        // size 1..32 — all must concatenate to the same output.
        bool serve_ok = true;
        for (size_t sc = 1; sc <= 32; ++sc) {
            if (reassemble_chunked(manifest, 5, sc) != direct) { serve_ok = false; break; }
        }
        check(serve_ok, "SSL(chunk): every serve-chunk size yields identical output");

        // Byte-for-byte spot check on a tiny (3-byte) feed that guarantees the
        // "/iad_" token and the EXTINF/URI pair are split across boundaries.
        std::string got = reassemble_chunked(manifest, 3, 4);
        check(got == direct, "SSL(chunk): 3-byte feed (splits /iad_) matches direct filter");
        check(got.find("/iad_") == std::string::npos, "SSL(chunk): ad removed despite split marker");
    }

    // ── chunked reassembly: DASH classified across a chunk boundary ──────────
    // The "<MPD" that commits classification arrives in a later chunk than the
    // "<?xml" prolog — the reassembler must stay Undecided then latch Buffering.
    {
        std::string dash =
            "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n"
            "<Period id=\"break\"><BaseURL>https://cdn/iad_9/</BaseURL></Period>\n"
            "<Period id=\"main\"><BaseURL>https://cdn/content/</BaseURL></Period>\n"
            "</MPD>\n";
        std::string direct = dash;
        run(direct);
        // 8-byte first chunk = "<?xml ve" — '<' viable but no "<MPD" yet.
        std::string got = reassemble_chunked(dash, 8, 5);
        check(got == direct, "SSL(chunk): DASH classified across boundary, filtered");
        check(got.find("/iad_") == std::string::npos, "SSL(chunk): DASH ad period removed");
    }

    // ── chunked reassembly: non-manifest decided Passthrough, never buffered ─
    // A media segment (leading byte not '#'/'<') must be marked Passthrough on
    // the very first chunk and served back byte-identical.
    {
        const unsigned char raw[] = { 0x47, 0x40, 0x00, 0x10, 'v','i','d','e','o','-','t','s' }; // TS sync 0x47
        std::string seg(reinterpret_cast<const char*>(raw), sizeof(raw));

        pvfilter::SslReassembler ra;
        auto m = ra.feed(seg.data(), 4);   // just the first 4 bytes
        check(m == pvfilter::SslReassembler::Mode::Passthrough,
              "SSL(chunk): non-manifest detected Passthrough on first bytes");
        ra.feed(seg.data() + 4, seg.size() - 4);
        ra.finish();
        std::string got;
        char b[256]; size_t n;
        while ((n = ra.serve(b, sizeof(b))) > 0) got.append(b, n);
        check(got == seg, "SSL(chunk): passthrough serves original bytes unmodified");
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
