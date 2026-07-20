// offsets.h — the ONLY file you edit after the Ghidra pass (see ../OFFSETS.md).
//
// Everything else consumes these. Offsets/signatures are valid ONLY for the
// libignite.so build whose SHA-256 is recorded below; if the app updates,
// re-derive them.
#pragma once

// ── Provenance ───────────────────────────────────────────────────────────────
// Paste the sha256sum of the exact lib/armeabi-v7a/libignite.so you analyzed.
#define LIBIGNITE_SONAME        "libignite.so"
#define LIBIGNITE_SHA256        "TODO_PASTE_SHA256_HERE"
#define LIBIGNITE_APP_VERSION   "6.23.23+v15.5.0.70-armv7a"

// ── Feature toggles ──────────────────────────────────────────────────────────
// Leave inflate off until you confirm on-device that SSL_read output is gzip'd
// (i.e. you do NOT already see "#EXTM3U" / "<MPD" plaintext in the SSL_read tap).
#define ENABLE_SSL_READ_HOOK    1
#define ENABLE_INFLATE_HOOK     0

// ── SSL_read ─────────────────────────────────────────────────────────────────
// Signature: prologue bytes + mask ('x' must-match, '?' wildcard), equal length.
// Fallback: file offset from image base (used only if the signature misses).
//
// TODO(ghidra): replace the placeholders below with recovered values.
static const unsigned char SSL_READ_SIG[]  = { 0x00 };          // TODO
static const char*         SSL_READ_MASK   = "?";               // TODO (same length as SIG)
static const unsigned long SSL_READ_FALLBACK_OFFSET = 0x0;      // TODO

// ── inflate (zlib) ───────────────────────────────────────────────────────────
static const unsigned char INFLATE_SIG[]   = { 0x00 };          // TODO
static const char*         INFLATE_MASK    = "?";               // TODO (same length as SIG)
static const unsigned long INFLATE_FALLBACK_OFFSET  = 0x0;      // TODO

// A signature is considered "unset" while its mask is the single "?" placeholder.
// hooks.cpp checks this and refuses to install an unconfigured hook (fail loud,
// never silently hook address 0).
#define SIG_IS_UNSET(mask) ((mask)[0] == '?' && (mask)[1] == '\0')
