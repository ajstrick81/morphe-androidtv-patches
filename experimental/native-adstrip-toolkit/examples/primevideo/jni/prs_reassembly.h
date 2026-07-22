// prs_reassembly.h — reassemble a PRS (GetVodPlaybackResources) JSON body across
// read chunks, strip type:"Remote" once on the COMPLETE body, re-serve.
//
// WHY THIS EXISTS (the movies-vs-TV story).
// The off-device MITM rig stripped the PRS on the *whole* response body, which
// is why it cleaned movies AND TV shows. In-process, a single read (SSL_read /
// inflate / the libignite copy the bench found) frequently carries only a CHUNK
// of a large playlist. The same-length `prs_blank` path can only act on a
// COMPLETE array in one buffer (blanking a truncated chunk smashes a partial
// `Main` -> black screen), so for the denser/larger TV playlists it safely
// SKIPS every chunk and the ads survive. This class restores the rig's
// whole-body discipline: accumulate the body, filter the COMPLETE body once
// (via prs_filter, which is free to SHRINK because we re-serve), and hand the
// filtered bytes back in whatever chunk sizes the caller requests. Size is a
// non-issue — there is no 256KB gate here, unlike the push-seam blanker.
//
// SEAM NOTE. Reassemble-and-re-serve fits a PULL hook — one where we control
// what the app reads back (SSL_read / inflate / a read wrapper). It does NOT fit
// the one-shot memcpy PUSH seam the bench used for movies. Finding the correct
// whole-body PRS pull seam inside libignite is device-side (Ghidra) work; this
// header is the transform-side logic, ready to wire to it, and is validated
// here independent of any seam.
//
// FRAMING CAVEAT. filter_prs SHRINKS the body, so the re-served body is shorter
// than the original response. The pull-seam hook MUST reconcile HTTP framing —
// rewrite Content-Length to the served length, or switch the response to
// chunked transfer-encoding — or the app's HTTP layer will wait for bytes that
// never come. Detecting body start/end + adjusting the length is the framing
// driver's job (not built yet; the next offline, testable piece).
//
// Mirrors SslReassembler (manifest path); the only differences are that it
// BUFFERS JSON-object bodies (leading '{') instead of manifests and delegates to
// filter_prs(). Pure and Android-free so the host test drives exactly this
// logic. One instance == one HTTP response body; keying instances by the read
// handle and resetting on response boundaries (HTTP framing) is the hook's job.
#pragma once

#include "prs_filter.h"       // filter_prs, looks_like_prs
#include "manifest_filter.h"  // FilterResult

#include <cstddef>
#include <cstring>
#include <string>

namespace pvfilter {

class PrsReassembler {
public:
    enum class Mode { Undecided, Buffering, Passthrough };

    Mode mode() const { return mode_; }

    // Accumulate bytes from one read and (re)classify. Contract mirrors
    // SslReassembler: the hook feeds while Undecided/Buffering and withholds the
    // caller's bytes, pulling via serve() after finish(). The moment feed()
    // returns Passthrough the hook STOPS feeding and passes bytes through
    // directly — so a media segment (first byte != '{') is decided on the first
    // read and never accumulated.
    Mode feed(const char* data, size_t n) {
        acc_.append(data, n);
        if (mode_ == Mode::Undecided) classify(/*force=*/false);
        return mode_;
    }

    // Body complete: decide any still-Undecided stream, and filter if Buffering.
    void finish() {
        if (mode_ == Mode::Undecided) classify(/*force=*/true);
        if (mode_ == Mode::Buffering && !filtered_) {
            if (!acc_.empty()) {
                res_ = filter_prs(&acc_[0], acc_.size());
                acc_.resize(res_.new_len);
            }
            filtered_ = true;
        }
    }

    // Copy up to `cap` bytes of the served body into `out`; returns bytes
    // copied, 0 once fully drained. Call only after finish().
    size_t serve(char* out, size_t cap) {
        size_t remain = acc_.size() - served_;
        size_t n = remain < cap ? remain : cap;
        if (n) memcpy(out, acc_.data() + served_, n);
        served_ += n;
        return n;
    }

    // Whole served body (filtered if Buffering) — for tests / hook fast paths.
    const std::string& body() const { return acc_; }
    FilterResult result() const { return res_; }

private:
    // Decide Buffering vs Passthrough from the accumulated prefix. A PRS body is
    // a JSON object ('{') carrying the intraTitlePlaylist key. Anything not
    // starting with '{' (media segment, manifest — handled elsewhere) is
    // Passthrough on the first bytes. A '{' body without the key within
    // DECIDE_BYTES is some other JSON API response -> Passthrough (don't buffer
    // it whole). The key sits near the top of a real PRS, so DECIDE_BYTES is set
    // generously above any plausible pre-key prefix.
    void classify(bool force) {
        const char* p = acc_.data();
        const char* end = p + acc_.size();
        if (acc_.size() >= 3 && (unsigned char)p[0] == 0xEF &&
            (unsigned char)p[1] == 0xBB && (unsigned char)p[2] == 0xBF) p += 3; // BOM
        while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) ++p;

        if (p == end) { if (force) mode_ = Mode::Passthrough; return; }

        if (*p != '{') { mode_ = Mode::Passthrough; return; }   // not a JSON object

        if (looks_like_prs(acc_.data(), acc_.size())) { mode_ = Mode::Buffering; return; }

        // JSON object, key not seen yet: keep buffering until DECIDE_BYTES, then
        // conclude it isn't a PRS response.
        if (force || acc_.size() >= DECIDE_BYTES) mode_ = Mode::Passthrough;
    }

    // Generous: real PRS carries "intraTitlePlaylist" well within this; bounds
    // how much of a non-PRS JSON body we buffer before giving up.
    static const size_t DECIDE_BYTES = 65536;

    Mode         mode_ = Mode::Undecided;
    std::string  acc_;
    size_t       served_ = 0;
    bool         filtered_ = false;
    FilterResult res_;
};

} // namespace pvfilter
