package ajstrick81.morphe.patches.youtubetv.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ===========================================================================
// YouTube TV (YouTube Unplugged) — ad architecture
// (confirmed by dex disassembly of base.apk + native disassembly of
//  libgoogle3.so from split_config.armeabi_v7a.apk — the Onn device's ABI;
//  10.33.2 / versionCode 210330210)
//
// YouTube TV delivers video ads via GOOGLE SERVER-STITCHED DAI (SSDAI / SSAI):
// ads are stitched into the same media segments as content, and a CUEPOINT list
// tells the client where each ad break sits. The ad-break scheduling is owned by
// the NATIVE C++ player core (youtube::media::CuePointDataProviderImpl in
// libgoogle3.so); cuepoints arrive in Java via a JNI callback
// (CuePointDataProviderWrapper$NativeCallback.onCuepointList) but NATIVE is
// authoritative (it creates and holds the cuepoints).
//
// THE LEVER (native-verified):
//   CuePointDataProviderWrapper.nativeSetDaiDisabledByNoConfig(nativePtr)
//   -> JNI shim @0x574c9e -> tail-calls CuePointDataProviderImpl vtable slot #11
//      @0x5768d4, which sets a single bool member (obj+0x71) = "DAI disabled by
//      no config" = true. This is the app's OWN sanctioned disable path;
//      normally reached only when server hotconfig flag 45731777 is enabled, via
//      a runnable scheduled after setCuePointDataProvider.
//
// WHY THIS LEVER (vs. no-oping onCuepointList):
//   libgoogle3.so ships NATIVE AD-BLOCKER ENFORCEMENT — an SSDAI response-action
//   enum incl. FAIL_PLAYBACK_SHOW_AD_BLOCKER_ENFORCEMENT, driven SERVER-SIDE via
//   video_streaming::StreamProtectionStatus + a Proof-of-Origin (PoToken)
//   attestation. Suppression that makes the client merely LOOK like it skipped
//   ads (dropping cuepoints, blocking hosts) risks tripping the wall (black
//   screen / "ad blocker" enforcement). Driving the app's own "no DAI config"
//   state is a LEGITIMATE state the server already models, so it is the
//   suppression least likely to trip enforcement.
//
// ---------------------------------------------------------------------------
// EXPERIMENTAL / UNVALIDATED — server reaction can only be confirmed on-device.
// Whether the native core (a) fully stops stitched breaks when obj+0x71 is set
// this early, and (b) avoids server enforcement, is UNKNOWN until tested on the
// Onn device against a live break. See analysis/youtubetv/notes/native-core.md.
// ---------------------------------------------------------------------------

// Hook — CuePointDataProviderWrapper.<init>(Executor, Consumer)
//
// The wrapper's constructor calls the native createNative(...) and stores the
// result in the `nativePtr:J` field. We inject immediately AFTER that store,
// while nativePtr is still live in registers, an unconditional
//   this.nativeSetDaiDisabledByNoConfig(this.nativePtr)
// so every provider, at creation (before any cuepoint can arrive), puts the
// native core into its sanctioned "DAI disabled by no config" state.
//
// Anchor stability: the defining class is a RETAINED (non-obfuscated) YouTube
// media-interfaces library class; constructor signature (Executor, Consumer) is
// stable. The createNative call inside it is matched at patch time by opcode
// scan (invoke-static ...->createNative...), so we don't hard-code an offset.
//
// NOTE: definingClass uses \$ only inside string refs; the class itself has no $.
object CuePointDataProviderWrapperInitFingerprint : Fingerprint(
    definingClass =
        "Lcom/google/android/libraries/youtube/media/interfaces/CuePointDataProviderWrapper;",
    name = "<init>",
    parameters = listOf(
        "Lcom/google/android/libraries/youtube/media/interfaces/Executor;",
        "Ljava/util/function/Consumer;",
    ),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
)
