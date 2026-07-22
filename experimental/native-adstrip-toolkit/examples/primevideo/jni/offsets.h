// offsets.h — the ONLY file you edit after the Ghidra pass (see ../OFFSETS.md).
//
// Everything else consumes these. Offsets/signatures are valid ONLY for the
// libignite.so build whose SHA-256 is recorded below; if the app updates,
// re-derive them.
//
// Recovered 2026-07-20 NOT with Ghidra (its headless OSGi framework was broken
// in this environment) but with a custom PIC-xref resolver over an llvm-objdump
// disassembly: the target strings are reached via Thumb `ldr [pc,#k]; add rX,pc`
// deltas that no off-the-shelf tool resolved here. See ghidra_work/resolve_xrefs.py
// + find_func_entry.py + build_sigs.py. Both functions are Thumb (call addr | 1).
#pragma once

// ── Provenance ───────────────────────────────────────────────────────────────
#define LIBIGNITE_SONAME        "libignite.so"
#define LIBIGNITE_SHA256        "0742379155072cf76d271b3ee955d364cdc561fa78a3b5c5fd4ffe9472d99ade"
#define LIBIGNITE_APP_VERSION   "6.23.23+v15.5.0.70-armv7a"

// ── Feature toggles ──────────────────────────────────────────────────────────
// PRS route (the proven MITM transform, ported in-process): the PRS
// GetVodPlaybackResources body is gzip'd, so the plaintext JSON we strip appears
// at the inflate hook. SSL_read only sees gzip'd + HTTP/2-framed bytes there, so
// its hook is disabled for now (kept resolved for the manifest route later).
#define ENABLE_SSL_READ_HOOK    0
#define ENABLE_INFLATE_HOOK     1

// ── SSL_read (app's OpenSSL read wrapper) @ 0xc4fe3c (Thumb) ──────────────────
// Signature: prologue bytes + mask ('x' must-match, '?' wildcard), equal length.
// Byte 12 (0x1a) is the ldr [pc,#imm] pool offset -> wildcard (may shift builds).
// Fallback: image-relative offset (used only if the signature misses).
static const unsigned char SSL_READ_SIG[]  = {
    0xf0,0xb5,0x03,0xaf,0x4d,0xf8,0x04,0x8d,0x82,0xb0,0x04,0x46,0x1a,0x48,0x90,0x46
};
static const char*         SSL_READ_MASK   = "xxxxxxxxxxxx?xxx";
static const unsigned long SSL_READ_FALLBACK_OFFSET = 0xc4fe3c;

// ── inflate (zlib) @ 0xd32f7a (Thumb) ────────────────────────────────────────
// Confirmed inflate(z_streamp,int): prologue reads strm->state (ldr r4,[r0,#0x1c])
// then null-checks it, and the function references "incorrect header check".
// Byte 12 (0x6f) is the beq branch offset -> wildcard.
static const unsigned char INFLATE_SIG[]   = {
    0xf0,0xb5,0x03,0xaf,0x4d,0xf8,0x04,0x8d,0xc4,0x69,0x00,0x2c,0x6f,0xd0,0x22,0x68
};
static const char*         INFLATE_MASK    = "xxxxxxxxxxxx?xxx";
static const unsigned long INFLATE_FALLBACK_OFFSET  = 0xd32f7a;

// A signature is considered "unset" while its mask is the single "?" placeholder.
// hooks.cpp checks this and refuses to install an unconfigured hook (fail loud,
// never silently hook address 0).
#define SIG_IS_UNSET(mask) ((mask)[0] == '?' && (mask)[1] == '\0')
