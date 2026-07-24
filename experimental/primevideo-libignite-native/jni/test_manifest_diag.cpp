// Host test for the read-only manifest scanner. Compile:
//   g++ -std=c++17 test_manifest_diag.cpp manifest_diag.cpp -o t_md && ./t_md
#include "manifest_diag.h"
#include <cassert>
#include <cstdio>
#include <string>

using pvfilter::scan_manifest;
using pvfilter::looks_like_dash;

int main() {
    // Synthetic SSAI-style manifest mirroring the PeriodTailor names seen on-device.
    std::string m =
        "<?xml version=\"1.0\"?>\n"
        "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" type=\"static\">\n"
        "  <Period id=\"Adbreak0_Ad0\" start=\"PT0S\" duration=\"PT32.032S\">\n"
        "    <AdaptationSet><Representation><SegmentTemplate/></Representation></AdaptationSet>\n"
        "  </Period>\n"
        "  <Period id=\"Adbreak0_Ad1\" start=\"PT32.032S\" duration=\"PT32.032S\"></Period>\n"
        "  <Period id=\"Card_1\" start=\"PT64.064S\" duration=\"PT6.006S\"></Period>\n"
        "  <Period id=\"Main_2\" start=\"PT70.070S\" duration=\"PT1596.761S\"></Period>\n"
        "</MPD>\n";

    assert(looks_like_dash(m.data(), m.size()));
    auto s = scan_manifest(m.data(), m.size());
    printf("looks_like_manifest=%d period_count=%d ad_period_count=%d first_ad_idx=%d\n",
           s.looks_like_manifest, s.period_count, s.ad_period_count, s.first_ad_idx);
    assert(s.looks_like_manifest);
    assert(s.period_count == 4);
    assert(s.ad_period_count == 3);        // Adbreak0_Ad0, Adbreak0_Ad1, Card_1
    assert(s.first_ad_idx == 0);
    assert(s.is_ad[0] && s.is_ad[1] && s.is_ad[2] && !s.is_ad[3]);

    // Verify tag boundaries land on the actual "<Period" ... ">".
    for (int i = 0; i < s.period_count; ++i) {
        assert(m.compare(s.tag_start[i], 7, "<Period") == 0);
        assert(m[s.tag_open_end[i] - 1] == '>');
    }

    // Negatives: JSON must not be mistaken for a manifest.
    std::string json = "{\"intraTitlePlaylist\":[{\"type\":\"Remote\"}]}";
    assert(!looks_like_dash(json.data(), json.size()));
    auto sj = scan_manifest(json.data(), json.size());
    assert(sj.period_count == 0);

    // Mid-chunk start (no leading <?xml) but DASH namespace present in head.
    std::string mid = "duration=\"PT4S\"><Period id=\"Main_0\"></Period> urn:mpeg:dash blah";
    assert(looks_like_dash(mid.data(), mid.size()));

    // find_bytes
    assert(pvfilter::find_bytes(m.data(), m.size(), "Adbreak0_Ad1") != (size_t)-1);
    assert(pvfilter::find_bytes(json.data(), json.size(), "cuepointPlaylist") == (size_t)-1);
    assert(pvfilter::find_bytes(json.data(), json.size(), "intraTitlePlaylist") == 2);

    // rfind_bytes: last occurrence strictly before a limit.
    std::string rr = "AxxAyyA";  // 'A' at 0,3,6
    assert(pvfilter::rfind_bytes(rr.data(), rr.size(), "A") == 6);
    assert(pvfilter::rfind_bytes(rr.data(), 6, "A") == 3);
    assert(pvfilter::rfind_bytes(rr.data(), 6, "Z") == (size_t)-1);

    printf("ALL OK\n");
    return 0;
}
