// test_remote_strip.cpp — host unit test for the memcpy-seam ad-strip
// (remote_strip.h/.cpp), the C port of the verified Frida bench
// (frida/cmod-strip2.js). No Android/NDK dependency; validates on the PC.
//
//   g++ -std=c++17 -Wall test_remote_strip.cpp remote_strip.cpp -o t && ./t
//
// Exits non-zero on any failed assertion.
#include "remote_strip.h"
#include "rego_filter.h"

#include <cstdio>
#include <cstring>
#include <string>

static int g_fail = 0;
#define CHECK(cond, msg) do { if (!(cond)) { \
    std::printf("FAIL: %s  (%s:%d)\n", (msg), __FILE__, __LINE__); ++g_fail; } } while (0)

static bool all_spaces(const std::string& s, size_t start, size_t end) {
    for (size_t i = start; i < end; ++i) if (s[i] != ' ') return false;
    return true;
}

int main() {
    // ── 1. Basic: Main, Remote, Remote, Main -> both Remotes blanked, same length ──
    {
        std::string in =
          "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":["
          "{\"type\":\"Main\",\"startMs\":0,\"urls\":[\"https://x/main0.mpd\"]},"
          "{\"type\":\"Remote\",\"adId\":\"ad1\",\"urls\":[\"https://y/iad_1.mpd\"]},"
          "{\"type\":\"Remote\",\"adId\":\"ad2\"},"
          "{\"type\":\"Main\",\"startMs\":100}"
          "],\"nonLinearAds\":{\"ads\":[{\"id\":\"nl1\"}]},\"z\":true}";
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.found_marker, "basic: marker found");
        CHECK(r.complete, "basic: array complete");
        CHECK(r.modified, "basic: modified");
        CHECK(r.remote_items == 2, "basic: 2 Remote items blanked");
        CHECK(r.total_items == 4, "basic: 4 total items parsed");
        CHECK(buf.size() == in.size(), "basic: length unchanged");
        CHECK(buf.find("\"Remote\"") == std::string::npos, "basic: no Remote literal left");
        CHECK(buf.find("main0.mpd") != std::string::npos, "basic: Main content untouched");
        CHECK(buf.find("\"startMs\":100") != std::string::npos, "basic: trailing Main untouched");
        CHECK(buf.find("\"nonLinearAds\":{\"ads\":[{\"id\":\"nl1\"}]}") != std::string::npos,
              "basic: strip does not touch nonLinearAds (memcpy seam has no allocation-based second pass)");
        // still-valid array shape after blanking: no stray commas next to '[' or ']'
        size_t lb = buf.find("\"intraTitlePlaylist\":[") + strlen("\"intraTitlePlaylist\":[");
        size_t rb = buf.find(']', lb);
        // skip leading spaces (blanked Remote), first non-space char after '[' should be '{' (kept Main) or ' '
        CHECK(buf[lb] != ',', "basic: no leading comma right after '['");
        CHECK(buf[rb - 1] != ',', "basic: no trailing comma right before ']'");
    }

    // ── 2. Remote is the FIRST element ──────────────────────────────────────
    {
        std::string buf =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Remote\",\"adId\":\"ad1\"},"
          "{\"type\":\"Main\",\"startMs\":0}"
          "]}";
        size_t orig_len = buf.size();
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.modified && r.remote_items == 1, "first-elem: 1 Remote blanked");
        CHECK(buf.size() == orig_len, "first-elem: length unchanged");
        CHECK(buf.find("\"Remote\"") == std::string::npos, "first-elem: Remote gone");
        CHECK(buf.find("{\"type\":\"Main\"") != std::string::npos, "first-elem: Main kept");
        size_t lb = buf.find('[') + 1;
        CHECK(buf[lb] != ',', "first-elem: no leading comma");
    }

    // ── 3. Remote is the LAST element (no trailing comma of its own) ───────
    {
        std::string buf =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Main\",\"startMs\":0},"
          "{\"type\":\"Remote\",\"adId\":\"ad1\"}"
          "]}";
        size_t orig_len = buf.size();
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.modified && r.remote_items == 1, "last-elem: 1 Remote blanked");
        CHECK(buf.size() == orig_len, "last-elem: length unchanged");
        CHECK(buf.find("\"Remote\"") == std::string::npos, "last-elem: Remote gone");
        size_t rb = buf.rfind(']');
        CHECK(buf[rb - 1] != ',', "last-elem: no trailing comma before ']' (leading comma of "
                                  "the blanked element was consumed too)");
    }

    // ── 4. Single-element array, that element IS Remote (no commas at all) ──
    {
        std::string buf = "{\"intraTitlePlaylist\":[{\"type\":\"Remote\"}]}";
        size_t orig_len = buf.size();
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.modified && r.remote_items == 1, "single: 1 Remote blanked");
        CHECK(buf.size() == orig_len, "single: length unchanged");
        size_t lb = buf.find('[') + 1;
        size_t rb = buf.find(']');
        CHECK(all_spaces(buf, lb, rb), "single: entire element blanked to spaces");
    }

    // ── 5. No Remote items at all -> untouched, not even a marker-only touch ─
    {
        std::string in = "{\"intraTitlePlaylist\":[{\"type\":\"Main\",\"startMs\":0}]}";
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.found_marker && r.complete, "no-remote: marker found + complete");
        CHECK(!r.modified, "no-remote: not modified");
        CHECK(r.remote_items == 0, "no-remote: 0 blanked");
        CHECK(buf == in, "no-remote: buffer byte-for-byte unchanged");
    }

    // ── 6. TRUNCATED ARRAY (no closing ']' in the buffer) -> must NOT touch ──
    // This is the exact failure mode the original bench hit (v1): blanking a
    // truncated chunk mid-element caused black screens. The safety invariant
    // is "never touch an incomplete array."
    {
        std::string in =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Remote\",\"adId\":\"ad1\"},"
          "{\"type\":\"Main\",\"startMs\":0,\"urls\":[\"https://x/very-long-url-that-got-cut-off";
          // no closing quote, brace, or bracket -- simulates a truncated libignite chunk copy
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.found_marker, "truncated: marker still found");
        CHECK(!r.complete, "truncated: NOT flagged complete");
        CHECK(!r.modified, "truncated: NOT modified");
        CHECK(buf == in, "truncated: buffer byte-for-byte unchanged (safety invariant)");
    }

    // ── 7. TRUNCATED right at the closing bracket (array never closes) ──────
    {
        std::string in =
          "{\"intraTitlePlaylist\":[{\"type\":\"Remote\"},{\"type\":\"Main\"}";
          // buffer just ends here, no ']'
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.found_marker, "truncated-no-bracket: marker found");
        CHECK(!r.complete, "truncated-no-bracket: NOT complete");
        CHECK(!r.modified, "truncated-no-bracket: NOT modified");
        CHECK(buf == in, "truncated-no-bracket: buffer unchanged");
    }

    // ── 8. Truncated string value (quote never closes) -> must NOT touch ────
    {
        std::string in =
          "{\"intraTitlePlaylist\":[{\"type\":\"Remote\",\"note\":\"unterminated";
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(!r.complete, "truncated-string: NOT complete");
        CHECK(!r.modified, "truncated-string: NOT modified");
        CHECK(buf == in, "truncated-string: buffer unchanged");
    }

    // ── 9. No marker at all (unrelated JSON / non-PRS buffer) ───────────────
    {
        std::string in = "{\"foo\":\"bar\",\"baz\":[1,2,3]}";
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(!r.found_marker, "no-marker: not found");
        CHECK(!r.complete && !r.modified, "no-marker: not complete/modified");
        CHECK(buf == in, "no-marker: buffer unchanged");
    }

    // ── 10. Known limitation, asserted explicitly: the marker is the literal
    // "intraTitlePlaylist":[ with NO space before '[' (matches cmod-strip2.js's
    // CModule find_marker exactly). Real on-device PRS JSON is always
    // compact/minified, so this hasn't been an issue in any capture to date --
    // but a pretty-printed response would slip past undetected (safe: it just
    // means untouched, not corrupted). Documented here so it doesn't surprise
    // a future reader as a "bug".
    {
        std::string in =
          "{\"intraTitlePlaylist\" : [ { \"type\" : \"Remote\" } , { \"type\":\"Main\" } ] }";
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(!r.found_marker, "whitespace-before-bracket: marker (correctly) not matched");
        CHECK(buf == in, "whitespace-before-bracket: buffer unchanged (safe, just a no-op)");
    }

    // ── 11. Whitespace INSIDE elements (after the marker's own '[') is fully
    // supported -- only the exact "intraTitlePlaylist":[ marker is strict.
    {
        std::string in =
          "{\"intraTitlePlaylist\":[ { \"type\" : \"Remote\" } , { \"type\" : \"Main\" } ]}";
        size_t orig_len = in.size();
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r.found_marker && r.complete, "whitespace-inside: marker found, array complete");
        CHECK(r.modified && r.remote_items == 1, "whitespace-inside: 1 Remote blanked");
        CHECK(buf.size() == orig_len, "whitespace-inside: length unchanged");
        CHECK(buf.find("\"Remote\"") == std::string::npos, "whitespace-inside: Remote gone");
        CHECK(buf.find("\"Main\"") != std::string::npos, "whitespace-inside: Main kept");
    }

    // ── TRUNCATED-BLANK feature (blank_truncated_complete=true) ─────────────
    // A complete Remote element precedes the truncation point; the trailing
    // element is cut mid-body. The complete Remote must be blanked; the cut
    // element must be left byte-for-byte untouched; length unchanged.
    {
        std::string in =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Remote\",\"adId\":\"ad1\"},"
          "{\"type\":\"Main\",\"urls\":[\"https://y/main_verylong_url_that_gets_cut";  // truncated mid-body
        std::string buf = in;
        // Default (false): nothing touched, but the complete Remote is counted.
        auto r0 = pvfilter::strip_remote_items(&buf[0], buf.size());
        CHECK(r0.found_marker && !r0.complete, "trunc: marker found, not complete");
        CHECK(r0.trunc_complete_remotes == 1, "trunc: 1 complete Remote counted before cut");
        CHECK(!r0.trunc_modified && r0.trunc_remote_blanked == 0, "trunc default: nothing blanked");
        CHECK(buf == in, "trunc default: buffer byte-identical (untouched)");

        // Enabled (true): the complete Remote is blanked, the cut Main is intact.
        std::string buf2 = in;
        auto r = pvfilter::strip_remote_items(&buf2[0], buf2.size(), /*blank_truncated_complete=*/true);
        CHECK(!r.complete, "trunc-blank: still not complete");
        CHECK(r.trunc_modified && r.trunc_remote_blanked == 1, "trunc-blank: 1 Remote blanked");
        CHECK(buf2.size() == in.size(), "trunc-blank: length unchanged");
        CHECK(buf2.find("\"Remote\"") == std::string::npos, "trunc-blank: Remote literal gone");
        // The truncated Main element (everything from its '{') must be untouched.
        size_t main_pos = in.find("{\"type\":\"Main\"");
        CHECK(buf2.compare(main_pos, std::string::npos, in, main_pos, std::string::npos) == 0,
              "trunc-blank: truncated trailing element byte-identical (mid-body cut never touched)");
        CHECK(buf2.find("main_verylong_url_that_gets_cut") != std::string::npos,
              "trunc-blank: cut Main URL preserved");
    }

    // ── TRUNCATED-BLANK: the truncated trailing element is ITSELF a Remote ──
    // cut mid-body. It must NOT be blanked (we never touch a partial element);
    // the complete Main before it stays; no complete Remote exists to blank.
    {
        std::string in =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Main\",\"startMs\":0},"
          "{\"type\":\"Remote\",\"adId\":\"ad9\",\"urls\":[\"https://y/iad_cut";  // Remote cut mid-body
        std::string buf = in;
        auto r = pvfilter::strip_remote_items(&buf[0], buf.size(), /*blank_truncated_complete=*/true);
        CHECK(!r.complete, "trunc-remote-cut: not complete");
        CHECK(r.trunc_complete_remotes == 0, "trunc-remote-cut: no COMPLETE Remote before cut");
        CHECK(!r.trunc_modified && r.trunc_remote_blanked == 0, "trunc-remote-cut: nothing blanked");
        CHECK(buf == in, "trunc-remote-cut: buffer untouched (partial Remote never blanked)");
    }

    // ── REGOLITH PATH 2: TRUNCATED pre-roll buffer, salvage media.urls ──────
    // Real-world repro (tester logcat, id=1000): the getVideoAds response is
    // copied as a prefix chunk whose top "playlist":[ never closes, but the
    // ad's media.urls array closes before the cut. The playlist matcher must
    // report truncation (string-aware: the [CONSUMPTIONID] literal bracket
    // inside a JSON string must NOT be miscounted as an array element), and
    // blank_complete_media_urls must empty the complete urls array in place.
    {
        std::string in =
          "{\"description\":{\"adDeliverySessionId\":\"x_PBP_EXPL_y_1\",\"adMarkerId\":\"PRE_ROLL\"},"
          "\"measurement\":{\"a\":1},"
          "\"playlist\":[{\"type\":\"Ad\",\"duration\":\"00:00:22.022\","
          "\"measurement\":{\"impressions\":[\"https://ters/re?consumptionId=[CONSUMPTIONID]\"]},"
          "\"media\":{\"urls\":["
          "{\"cdn\":\"fastly\",\"url\":\"https://vod-dash-pv.amazon.fastly-edge.com/x/ww_bom/interstitial/5/122621085/manifest.mpd\"},"
          "{\"cdn\":\"akamai\",\"url\":\"https://avoddashs3ww-a.akamaihd.net/x/iad_2/interstitial/5/122621085/manifest.mpd\"}"
          "]},"
          "\"uiFeatures\":{\"actionable\":{\"metadata\":{\"adParameters\":\"cut";  // truncated mid-body
        std::string buf = in;
        size_t n = buf.size();
        size_t open = buf.find("\"playlist\":[") + 11;
        CHECK(pvfilter::json_match_bracket(buf.data(), n, open) == (size_t)-1,
              "rego-trunc: playlist reported truncated (string-aware)");
        int nb = pvfilter::blank_complete_media_urls(&buf[0], n, open);
        CHECK(nb == 1, "rego-trunc: exactly one complete media.urls array blanked");
        CHECK(buf.size() == n, "rego-trunc: length unchanged");
        CHECK(buf.find("/interstitial/") == std::string::npos, "rego-trunc: interstitial URL gone");
        CHECK(buf.find("vod-dash-pv") == std::string::npos, "rego-trunc: fastly ad host gone");
        CHECK(buf.find("avoddashs3ww") == std::string::npos, "rego-trunc: akamai ad host gone");
        size_t ui = in.find("\"uiFeatures\"");
        CHECK(buf.compare(ui, std::string::npos, in, ui, std::string::npos) == 0,
              "rego-trunc: truncated tail (uiFeatures...) untouched");
    }

    // ── REGOLITH PATH 2: media.urls itself cut mid-body -> blank NOTHING ─────
    // Black-screen invariant: a urls array with no closing ']' must be untouched.
    {
        std::string in =
          "{\"adDeliverySessionId\":\"z\",\"measurement\":{},\"playlist\":[{\"type\":\"Ad\","
          "\"media\":{\"urls\":[{\"cdn\":\"fastly\",\"url\":\"https://vod-dash-pv/interstitial/man";  // cut inside urls
        std::string buf = in;
        size_t open = buf.find("\"playlist\":[") + 11;
        int nb = pvfilter::blank_complete_media_urls(&buf[0], buf.size(), open);
        CHECK(nb == 0, "rego-urls-cut: nothing blanked (urls truncated)");
        CHECK(buf == in, "rego-urls-cut: buffer byte-for-byte unchanged");
    }

    // ── REGOLITH PATH 2: multi-ad prefix, two COMPLETE urls arrays -> both ──
    {
        std::string in =
          "{\"measurement\":{},\"playlist\":[{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"a/interstitial/1.mpd\"}]}},"
          "{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"b/interstitial/2.mpd\"}]}},{\"type\":\"Ad\",\"media\":{\"urls\":[\"cut";
        std::string buf = in;
        size_t open = buf.find("\"playlist\":[") + 11;
        int nb = pvfilter::blank_complete_media_urls(&buf[0], buf.size(), open);
        CHECK(nb == 2, "rego-multi: two complete urls arrays blanked, third (cut) skipped");
        CHECK(buf.find("/interstitial/1.mpd") == std::string::npos &&
              buf.find("/interstitial/2.mpd") == std::string::npos, "rego-multi: both ad URLs gone");
        CHECK(buf.find("\"cut") != std::string::npos, "rego-multi: truncated third urls left intact");
    }

    // ── #260 REASSEMBLY: whole-array empty once the playlist closes ─────────
    // blank_array_interior_if_complete() is the shared primitive the cross-chunk
    // reassembler (hooks.cpp rego_try_reassemble) runs over the reassembled
    // span. On a truncated prefix it must write NOTHING and report truncation;
    // on the complete (reassembled) buffer it must blank the ENTIRE interior so
    // the playlist is genuinely empty (length 0) — the only thing that kills the
    // ghost tail period that hangs the pre-roll.
    {
        // The complete response, exactly as it looks after all chunks land.
        std::string full =
          "{\"description\":{\"adDeliverySessionId\":\"x_PBP_EXPL_y_1\",\"adMarkerId\":\"PRE_ROLL\"},"
          "\"playlist\":[{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"a/interstitial/1.mpd\"}]}},"
          "{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"b/interstitial/2.mpd\"}]}},"
          "{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"c/interstitial/3.mpd\"}]}}],"
          "\"measurement\":{\"a\":1}}";
        size_t open = full.find("\"playlist\":[") + 11;

        // (a) The first chunk is a truncated prefix: cut inside the 3rd ad object.
        std::string prefix = full.substr(0, full.find("c/interstitial/3.mpd"));
        std::string pbuf = prefix;
        CHECK(pvfilter::blank_array_interior_if_complete(&pbuf[0], pbuf.size(), open)
                  == (size_t)-1,
              "reasm-prefix: truncated prefix reports -1 (no close)");
        CHECK(pbuf == prefix, "reasm-prefix: truncated prefix written byte-for-byte unchanged");

        // (b) Reassembled: the same open offset, now over the full buffer. The
        //     whole interior must blank; the array becomes a true empty list.
        std::string fbuf = full;
        size_t close = pvfilter::blank_array_interior_if_complete(&fbuf[0], fbuf.size(), open);
        CHECK(close != (size_t)-1, "reasm-full: playlist now closes");
        CHECK(fbuf.size() == full.size(), "reasm-full: length unchanged");
        CHECK(all_spaces(fbuf, open + 1, close), "reasm-full: entire interior blanked to spaces");
        CHECK(fbuf.find("/interstitial/") == std::string::npos,
              "reasm-full: every ad URL gone (no ghost tail object survives)");
        CHECK(fbuf.find("\"measurement\":{\"a\":1}") != std::string::npos,
              "reasm-full: content after the playlist untouched");
        // The tail object literal that caused #260 must be gone, not merely media-stripped.
        CHECK(fbuf.find("\"type\":\"Ad\"") == std::string::npos,
              "reasm-full: no residual ad object -> playlist.length === 0");
    }

    // ── #260 REASSEMBLY: dry-run (apply=false) parses but writes nothing ─────
    {
        std::string full =
          "{\"playlist\":[{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"a/interstitial/1.mpd\"}]}}],"
          "\"measurement\":{}}";
        std::string buf = full;
        size_t open = full.find("\"playlist\":[") + 11;
        size_t close = pvfilter::blank_array_interior_if_complete(&buf[0], buf.size(), open,
                                                                  /*apply=*/false);
        CHECK(close != (size_t)-1, "reasm-dryrun: close still located");
        CHECK(buf == full, "reasm-dryrun: apply=false writes nothing");
    }

    // ── #260 SPLIT-TAIL (primary fix): close the playlist in front of the ──────
    // unclosed tail object using only the prefix bytes, independent of where the
    // rest of the body is later written. split_off_truncated_tail() runs after the
    // caller has blanked the complete leading ad objects to spaces, so the gap
    // between '[' and the tail '{' is whitespace.
    {
        // Precondition met: leading complete objects already blanked to spaces.
        std::string s = "{\"playlist\":[                      {\"type\":\"Ad\",\"url\":\"t.mpd\"";
        size_t open = s.find("\"playlist\":[") + 11;      // '['
        size_t tail = s.find('{', open + 1);              // tail object '{'
        std::string b = s;
        CHECK(pvfilter::split_off_truncated_tail(&b[0], b.size(), open, tail),
              "split: valid gap -> spliced");
        CHECK(b.size() == s.size(), "split: length unchanged");
        CHECK(b.find("\"playlist\":[],\"_\":[") != std::string::npos,
              "split: playlist closed as [], dummy \"_\" array opened in front of tail");
        CHECK(b.find("{\"type\":\"Ad\"") != std::string::npos,
              "split: tail object preserved (now the first element of \"_\")");
    }
    {
        // Tail is the FIRST item: no blanked gap -> must refuse, write nothing.
        std::string s = "{\"playlist\":[{\"type\":\"Ad\",\"url\":\"t.mpd\"";
        size_t open = s.find("\"playlist\":[") + 11;
        size_t tail = open + 1;                           // '{' immediately after '['
        std::string b = s;
        CHECK(!pvfilter::split_off_truncated_tail(&b[0], b.size(), open, tail),
              "split-firstitem: refuses (no blanked gap)");
        CHECK(b == s, "split-firstitem: buffer unchanged");
    }
    {
        // Gap is NOT all whitespace (caller precondition violated) -> refuse.
        std::string s = "{\"playlist\":[{\"old\":1}   {\"type\":\"Ad\"";
        size_t open = s.find("\"playlist\":[") + 11;
        size_t tail = s.rfind('{');
        std::string b = s;
        CHECK(!pvfilter::split_off_truncated_tail(&b[0], b.size(), open, tail),
              "split-nonws: refuses when gap has non-whitespace");
        CHECK(b == s, "split-nonws: buffer unchanged");
    }
    {
        // Dry-run: apply=false reports the splice point but writes nothing.
        std::string s = "{\"playlist\":[        {\"type\":\"Ad\"";
        size_t open = s.find("\"playlist\":[") + 11;
        size_t tail = s.find('{', open + 1);
        std::string b = s;
        CHECK(pvfilter::split_off_truncated_tail(&b[0], b.size(), open, tail, /*apply=*/false),
              "split-dryrun: reports valid splice");
        CHECK(b == s, "split-dryrun: apply=false writes nothing");
    }
    {
        // End-to-end, buffer-location-independent: for EVERY cut inside the tail
        // ad object, apply steps (1)+(3) to the prefix, append the UNMODIFIED
        // remainder (wherever it lands), and the reassembled body must be a valid
        // balanced object with a genuinely empty playlist and no leading ad URLs.
        std::string full =
          "{\"playlist\":["
          "{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"a/interstitial/1.mpd\"}]}},"
          "{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"b/interstitial/2.mpd\"}]}},"
          "{\"type\":\"Ad\",\"media\":{\"urls\":[{\"url\":\"c/interstitial/3.mpd\"}]}}"
          "],\"measurement\":{\"m\":1}}";
        size_t open = full.find("\"playlist\":[") + 11;        // '['
        size_t tail = full.rfind("{\"type\":\"Ad\"");           // 3rd ad = tail object
        size_t tail_end = pvfilter::json_match_bracket(&full[0], full.size(), tail) + 1; // past tail '}'
        int cases = 0, ok = 0;
        for (size_t cut = tail + 1; cut < tail_end; ++cut) {
            ++cases;
            std::string prefix = full.substr(0, cut);
            // step (1): blank the complete leading ad objects (open+1 .. tail) to spaces
            for (size_t k = open + 1; k < tail; ++k) prefix[k] = ' ';
            // step (3): splice the playlist closed in front of the tail
            if (!pvfilter::split_off_truncated_tail(&prefix[0], prefix.size(), open, tail))
                continue;
            std::string reasm = prefix + full.substr(cut);     // remainder lands verbatim
            bool pass = true;
            pass &= reasm.size() == full.size();
            pass &= reasm.find("\"playlist\":[]") != std::string::npos;   // empty playlist
            pass &= reasm.find("interstitial/1.mpd") == std::string::npos; // leading ads gone
            pass &= reasm.find("interstitial/2.mpd") == std::string::npos;
            pass &= reasm.find("\"measurement\":{\"m\":1}") != std::string::npos;
            // outer object must still balance (valid JSON structure)
            pass &= pvfilter::json_match_bracket(&reasm[0], reasm.size(), 0) == reasm.size() - 1;
            if (pass) ++ok;
        }
        CHECK(cases > 0 && ok == cases, "split-e2e: every cut inside the tail reassembles to valid empty-playlist JSON");
    }

    std::printf(g_fail == 0 ? "ALL TESTS PASSED (0 failure(s))\n" : "%d FAILURE(S)\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
