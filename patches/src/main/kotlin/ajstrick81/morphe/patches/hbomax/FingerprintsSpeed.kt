package ajstrick81.morphe.patches.hbomax

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Injection points for "HBO Max - Playback Speed".
// Verified against com.wbd.hbomax 7.12.0.68 (decompiled) and compatible back to
// 7.9.0.61 (the stable AppTarget). Neither hook overlaps any ad-patch hook.
// ─────────────────────────────────────────────────────────────────────────────

private const val EXO_PLAYER_INTERFACE = "Landroidx/media3/exoplayer/ExoPlayer;"

/**
 * HBO Max (Android TV) Application — com.wbd.beam.BeamApplication. It extends
 * android.app.Application directly and declares its own onCreate(), which is the
 * earliest, safest place to hand the extension a Context (before any screen
 * exists). The class is named in the manifest, so it cannot be obfuscated.
 *
 * NOTE: this is the ATV class. The phone build (com.wbd.stream) uses
 * com.wbd.stream.MainApplication instead — different class, same shape.
 */
internal object BeamApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/wbd/beam/BeamApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

/**
 * Every constructor of every class implementing media3's ExoPlayer interface.
 *
 * HBO renames the implementation (androidx.media3.exoplayer.d = ExoPlayerImpl)
 * and makes it package-private final, but media3's own ProGuard rules keep the
 * ExoPlayer *interface* type — so the interface is what this matches on, which
 * survives version-to-version renaming. On 7.12.0.68 there is exactly one such
 * class; matching by interface also catches any delegating wrapper a future
 * build adds, since each implements ExoPlayer and accepts the speed change.
 */
internal object ExoPlayerConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    custom = { _, classDef -> EXO_PLAYER_INTERFACE in classDef.interfaces },
)
