package ajstrick81.morphe.patches.youtubetv.ads

import app.morphe.patcher.Fingerprint

// ===========================================================================
// YouTube TV (YouTube Unplugged) — ad architecture
// (confirmed by dex disassembly of base.apk, 10.33.2 / versionCode 210330210)
//
// YouTube TV delivers video ads via GOOGLE SERVER-STITCHED DAI (SSDAI / SSAI):
// ads are stitched into the same media segments as content, and an out-of-band
// CUEPOINT list tells the client where each ad break sits on the timeline.
//
// The ad-break decisioning is driven from the NATIVE C++ player core; cuepoints
// arrive in Java through a JNI callback:
//   CuePointDataProviderWrapper$NativeCallback.onCuepointList(byte[])
// which parses a CuepointList proto and, per CuepointContext, builds a concrete
// ad-break object (amxc extends amuu: id, type, startMs, endMs, ..., daiToken)
// and dispatches it (apow) to the player's ad-break consumer.
//
//   ServerStitchedDaiInfo ── daistate token (opaque)
//   CuepointList ─ repeated CuepointContext ─ { bsat trigger (type enum +
//                    start/end seconds + id/cpn), TimeRange, index, id, DAI token }
//
// The Zen Beach "Enjoy the zen, we'll be right back" slate is the UNSOLD-break
// filler (server-delivered interstitial video), played when a cuepoint break
// returns NO_DAI_CONFIG_FROM_GAB — it rides the same cuepoint path, so killing
// cuepoints removes both real ads (gambling/adult included) and the Zen filler.
//
// ---------------------------------------------------------------------------
// EXPERIMENTAL — this hook is a HYPOTHESIS pending on-device validation.
//
// onCuepointList is a callback FROM native code. It is UNPROVEN whether the
// native core (nativePtr) schedules/splices breaks on its own and this Java
// callback only feeds the client-side ad-break scheduler + UI/telemetry, OR
// whether the player relies on this dispatch to schedule breaks at all. If the
// native side is authoritative, no-oping this method will NOT stop the stitched
// ad video from playing (same class of problem as the Netflix native ad-strip
// work in experimental/). Validate on hardware against a live break BEFORE
// treating this as a working ad-suppression patch. See
// analysis/youtubetv/notes/recon.md.
// ---------------------------------------------------------------------------

// Hook — CuePointDataProviderWrapper$NativeCallback.onCuepointList(byte[])
//
// The single JNI->Java choke point for the cuepoint (ad-break) list. It parses
// CuepointListOuterClass$CuepointList, iterates its CuepointContext entries, and
// for each set entry constructs amxc(CuepointContext) wrapped in apow and hands
// it to the Consumer (a bumi). Neutering it (early return-void) drops every
// cuepoint-driven break the Java layer would schedule.
//
// Anchor stability: the defining class is a RETAINED (non-obfuscated) YouTube
// media-interfaces library class, so definingClass + name + params + returnType
// is a strong, R8-proof match. The method is package-private (no access
// modifier in smali), so accessFlags are intentionally left unconstrained.
// Confirmed present at:
//   Lcom/google/android/libraries/youtube/media/interfaces/
//       CuePointDataProviderWrapper$NativeCallback;->onCuepointList([B)V
//
// NOTE: definingClass uses \$ so Kotlin does not treat $NativeCallback as a
// string template — it is the literal inner-class name.
object OnCuepointListFingerprint : Fingerprint(
    definingClass =
        "Lcom/google/android/libraries/youtube/media/interfaces/CuePointDataProviderWrapper\$NativeCallback;",
    name = "onCuepointList",
    parameters = listOf("[B"),
    returnType = "V",
)
