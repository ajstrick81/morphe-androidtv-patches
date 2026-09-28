// ssl_reassembly.h — reassemble a manifest delivered across SSL_read chunks.
//
// SSL_read hands back one TLS record at a time, so a manifest arrives in
// several reads and an ad marker (`/iad_`, or an EXTINF/URI pair, or a
// <Period>) can straddle a chunk boundary. Filtering each chunk independently
// therefore misses boundary-split ads and can't recognize a manifest whose
// magic bytes and ad markers land in different reads.
//
// SslReassembler accumulates the response body, filters the COMPLETE body once,
// and re-serves the filtered bytes in whatever chunk sizes the caller requests.
// Non-manifest responses are detected from the leading bytes and marked
// Passthrough so video segments are never buffered.
//
// Pure and Android-free so the host test drives exactly this logic. One
// instance == one HTTP response body; keying instances by SSL* and resetting on
// response boundaries (HTTP framing) is the hook's job — see hooks.cpp — and is
// the only part not covered here.
#pragma once

#include "manifest_filter.h"

#include <cstddef>
#include <cstring>
#include <string>

namespace pvfilter {

class SslReassembler {
public:
    enum class Mode { Undecided, Buffering, Passthrough };

    Mode mode() const { return mode_; }

    // Accumulate bytes just read from the real SSL_read, and (re)classify.
    // While Undecided/Buffering the hook withholds the caller's bytes and pulls
    // via serve() after finish(). On Passthrough everything accumulated so far
    // is still available via serve() (unfiltered), so no bytes are ever lost.
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
                res_ = filter(&acc_[0], acc_.size());
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
    // Decide Buffering vs Passthrough from the accumulated prefix. Stays
    // Undecided while the prefix is still a viable manifest start and we haven't
    // yet seen enough bytes (or the classifying tag) to commit — unless forced.
    void classify(bool force) {
        const char* p = acc_.data();
        const char* end = p + acc_.size();
        if (acc_.size() >= 3 && (unsigned char)p[0] == 0xEF &&
            (unsigned char)p[1] == 0xBB && (unsigned char)p[2] == 0xBF) p += 3; // BOM
        while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) ++p;

        if (p == end) { if (force) mode_ = Mode::Passthrough; return; }

        // A manifest must start with '#' (HLS) or '<' (DASH). Anything else —
        // e.g. a binary media segment — is decided Passthrough on the first
        // bytes, so segments are never accumulated.
        if (*p != '#' && *p != '<') { mode_ = Mode::Passthrough; return; }

        Kind k = sniff(acc_.data(), acc_.size());
        if (k == Kind::Hls || k == Kind::Dash) { mode_ = Mode::Buffering; return; }

        // Viable prefix but not yet matched (partial "#EXTM3U", or "<?xml…"
        // before "<MPD" appears). Give it up to DECIDE_BYTES, then conclude it
        // isn't a manifest.
        if (force || acc_.size() >= DECIDE_BYTES) mode_ = Mode::Passthrough;
    }

    static const size_t DECIDE_BYTES = 1024;

    Mode         mode_ = Mode::Undecided;
    std::string  acc_;
    size_t       served_ = 0;
    bool         filtered_ = false;
    FilterResult res_;
};

} // namespace pvfilter
