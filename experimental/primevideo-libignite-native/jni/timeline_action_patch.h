// timeline_action_patch.h — v16 deep ad suppression: intercept the WAMR host
// boundary where the downloaded Rust ad-client creates ad-break timeline actions.
//
// The regolith/getVideoAds ad-decision JSON is parsed INSIDE the downloaded
// Megablast Rust AoT module (libamzn_av_living_room_rust_client-*.so.br). The
// complete assembled response never passes through memcpy where the GOT hook
// can intercept it (the #120 truncation ceiling). But every ad break the Rust
// client creates must call back into libignite via Native_StartTimelineAction*
// host functions — this is the WAMR VM/host boundary, and it's the narrowest
// chokepoint downstream of all ad parsing.
//
// We find and inline-patch these host function implementations at runtime:
//   Native_StartTimelineAction       — starts an ad-break action (binary params)
//   Native_StartTimelineActionStr    — starts with a string param
//   Native_StartTimelineActionStrClr — starts with string + clear
//
// Each patched function becomes a no-op that returns immediately, so the player
// timeline never receives ad-break actions regardless of how the JSON was parsed.
//
// Discovery: we scan libignite's .data.rel.ro for the NativeSymbol registration
// table entries (name string pointer + impl function pointer), using the known
// rodata string offsets as anchors. This avoids hardcoding impl addresses that
// drift across versions — only the string content is stable.

#pragma once

namespace pvtimeline {

// Scan libignite for the Native_*TimelineAction* host function implementations
// and inline-patch the Start variants to no-ops. Returns the number of functions
// successfully patched (0–3). Safe to call before libignite is mapped (returns 0).
// Idempotent — re-calling after a successful patch is a no-op.
int apply_timeline_action_patch();

}  // namespace pvtimeline
