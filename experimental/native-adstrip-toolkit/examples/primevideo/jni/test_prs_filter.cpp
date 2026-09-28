// test_prs_filter.cpp — host unit test for the PRS (GetVodPlaybackResources)
// ad-strip. No Android/NDK/zlib needed; validates pvfilter::filter_prs (and the
// filter() dispatch to it) on the PC before device.
//
//   g++ -std=c++17 test_prs_filter.cpp prs_filter.cpp manifest_filter.cpp -o t && ./t
//
// Exits non-zero on any failed assertion.
#include "prs_filter.h"
#include "manifest_filter.h"

#include <cstdio>
#include <cstring>
#include <string>

static int g_fail = 0;
#define CHECK(cond, msg) do { if (!(cond)) { \
    std::printf("FAIL: %s  (%s:%d)\n", (msg), __FILE__, __LINE__); ++g_fail; } } while (0)

// Run the real hook entry point (filter) on a writable copy; return result + out.
static pvfilter::FilterResult run(const std::string& in, std::string& out) {
    std::string buf = in;
    pvfilter::FilterResult r = pvfilter::filter(buf.empty() ? nullptr : &buf[0], buf.size());
    buf.resize(r.new_len);
    out = buf;
    return r;
}

static bool has(const std::string& s, const std::string& sub) {
    return s.find(sub) != std::string::npos;
}

int main() {
    // ── 1. Basic: Main, Remote, Remote, Main -> 2 removed, 2 Mains kept ──────
    {
        std::string in =
          "{\"returnCode\":\"SUCCESS\",\"intraTitlePlaylist\":["
          "{\"type\":\"Main\",\"startMs\":0,\"urls\":[\"https://x/main0.mpd\"]},"
          "{\"type\":\"Remote\",\"adId\":\"ad1\",\"urls\":[\"https://y/iad_1.mpd\"]},"
          "{\"type\":\"Remote\",\"adId\":\"ad2\"},"
          "{\"type\":\"Main\",\"startMs\":100}"
          "],\"nonLinearAds\":{\"ads\":[{\"id\":\"nl1\"}]},\"z\":true}";
        std::string out;
        auto r = run(in, out);
        CHECK(r.is_prs, "basic: detected as PRS");
        CHECK(r.modified, "basic: modified");
        CHECK(r.remote_items == 2, "basic: removed 2 Remote items");
        CHECK(!has(out, "\"Remote\""), "basic: no Remote left");
        CHECK(has(out, "main0.mpd") && has(out, "\"startMs\":100"), "basic: both Mains kept");
        CHECK(has(out, "\"nonLinearAds\":{}"), "basic: nonLinearAds emptied");
        CHECK(has(out, "\"returnCode\":\"SUCCESS\"") && has(out, "\"z\":true"),
              "basic: surrounding fields intact");
        CHECK(out.size() < in.size(), "basic: shrank");
        // still-valid array shape: no leading/trailing/double commas
        CHECK(has(out, "[{\"type\":\"Main\""), "basic: array opens cleanly (no leading comma)");
        CHECK(has(out, "\"startMs\":100}]"), "basic: array closes cleanly (no trailing comma)");
        CHECK(!has(out, ",,"), "basic: no double comma");
    }

    // ── 2. Remote is the FIRST element ──────────────────────────────────────
    {
        std::string in =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Remote\",\"adId\":\"a\"},"
          "{\"type\":\"Main\",\"m\":1}"
          "]}";
        std::string out; auto r = run(in, out);
        CHECK(r.remote_items == 1, "first: removed 1");
        CHECK(has(out, "[{\"type\":\"Main\""), "first: no leading comma after [");
        CHECK(!has(out, "\"Remote\""), "first: remote gone");
    }

    // ── 3. Remote is the LAST element ───────────────────────────────────────
    {
        std::string in =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Main\",\"m\":1},"
          "{\"type\":\"Remote\",\"adId\":\"a\"}"
          "]}";
        std::string out; auto r = run(in, out);
        CHECK(r.remote_items == 1, "last: removed 1");
        CHECK(has(out, "\"m\":1}]"), "last: no trailing comma before ]");
    }

    // ── 4. No Remote items -> detected but NOT modified ─────────────────────
    {
        std::string in =
          "{\"intraTitlePlaylist\":[{\"type\":\"Main\",\"m\":1},{\"type\":\"Main\",\"m\":2}],"
          "\"nonLinearAds\":[]}";
        std::string out; auto r = run(in, out);
        CHECK(r.is_prs, "clean: is PRS");
        CHECK(!r.modified, "clean: not modified");
        CHECK(r.remote_items == 0, "clean: 0 removed");
        CHECK(out == in, "clean: untouched");
    }

    // ── 5. Not PRS JSON (no intraTitlePlaylist) -> untouched ────────────────
    {
        std::string in = "{\"hello\":\"world\",\"list\":[{\"type\":\"Remote\"}]}";
        std::string out; auto r = run(in, out);
        CHECK(!r.is_prs, "non-prs: not flagged PRS");
        CHECK(!r.modified, "non-prs: not modified");
        CHECK(out == in, "non-prs: untouched (Remote outside intraTitlePlaylist stays)");
    }

    // ── 6. Not JSON at all (plain text) -> untouched, not PRS ───────────────
    {
        std::string in = "just some text, not json, mentioning intraTitlePlaylist even";
        std::string out; auto r = run(in, out);
        CHECK(!r.is_prs, "text: not PRS (doesn't start with '{')");
        CHECK(out == in, "text: untouched");
    }

    // ── 7. Scanner robustness: strings containing ] } , inside a Remote elem ─
    {
        std::string in =
          "{\"intraTitlePlaylist\":["
          "{\"type\":\"Main\",\"note\":\"a]b}c,d\"},"
          "{\"type\":\"Remote\",\"note\":\"x]y}z\",\"nested\":{\"k\":[1,2]}},"
          "{\"type\":\"Main\",\"m\":9}"
          "]}";
        std::string out; auto r = run(in, out);
        CHECK(r.remote_items == 1, "robust: removed exactly the 1 Remote");
        CHECK(has(out, "a]b}c,d"), "robust: Main string with brackets survived");
        CHECK(has(out, "\"m\":9"), "robust: trailing Main survived");
        CHECK(!has(out, "x]y}z"), "robust: Remote (incl its bracket-y string) gone");
    }

    // ── 8. All elements Remote -> empty array, still valid ──────────────────
    {
        std::string in =
          "{\"intraTitlePlaylist\":[{\"type\":\"Remote\",\"a\":1},{\"type\":\"Remote\",\"a\":2}]}";
        std::string out; auto r = run(in, out);
        CHECK(r.remote_items == 2, "all-ad: removed both");
        CHECK(has(out, "\"intraTitlePlaylist\":[]"), "all-ad: empty array");
    }

    // ── 9. Whitespace between tokens (pretty-printed input) ─────────────────
    {
        std::string in =
          "{ \"intraTitlePlaylist\" : [ "
          "{ \"type\" : \"Main\" , \"m\":1 } , "
          "{ \"type\" : \"Remote\" , \"a\":2 } "
          "] }";
        std::string out; auto r = run(in, out);
        CHECK(r.is_prs, "ws: is PRS");
        CHECK(r.remote_items == 1, "ws: removed 1 despite spaces");
        CHECK(!has(out, "\"Remote\""), "ws: remote gone");
    }

    if (g_fail == 0) std::printf("ALL TESTS PASSED (0 failure(s))\n");
    else             std::printf("%d FAILURE(S)\n", g_fail);
    return g_fail ? 1 : 0;
}
