// test_remote_strip.cpp — host unit test for the memcpy-seam ad-strip
// (remote_strip.h/.cpp), the C port of the verified Frida bench
// (frida/cmod-strip2.js). No Android/NDK dependency; validates on the PC.
//
//   g++ -std=c++17 -Wall test_remote_strip.cpp remote_strip.cpp -o t && ./t
//
// Exits non-zero on any failed assertion.
#include "remote_strip.h"

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

    std::printf(g_fail == 0 ? "ALL TESTS PASSED (0 failure(s))\n" : "%d FAILURE(S)\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
