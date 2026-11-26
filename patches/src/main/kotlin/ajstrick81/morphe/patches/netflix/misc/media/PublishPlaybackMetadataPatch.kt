package ajstrick81.morphe.patches.netflix.misc.media

import ajstrick81.morphe.patches.netflix.shared.Constants
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val MANAGER = "Lcom/netflix/mediaclient/media/MediaSessionManager;"
private const val ARTWORK_CALLBACK =
    "Lcom/netflix/mediaclient/media/MediaSessionManager\$1;"
private const val MEDIA_SESSION = "Landroid/support/v4/media/session/MediaSessionCompat;"
private const val MEDIA_CONTROLLER =
    "Landroid/support/v4/media/session/MediaControllerCompat;"
private const val MEDIA_METADATA = "Landroid/support/v4/media/MediaMetadataCompat;"
private const val METADATA_BUILDER =
    "Landroid/support/v4/media/MediaMetadataCompat\$Builder;"

private object NetflixPlaybackIdFingerprint : Fingerprint(
    definingClass = MANAGER,
    name = "setPlaybackId",
    parameters = listOf("Ljava/lang/String;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
)

private object NetflixPlaybackMetadataFingerprint : Fingerprint(
    definingClass = MANAGER,
    name = "setPlaybackMetadata",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

private object NetflixArtworkCallbackFingerprint : Fingerprint(
    definingClass = ARTWORK_CALLBACK,
    name = "onResponse",
    parameters = listOf("Landroid/graphics/Bitmap;", "Ljava/lang/String;"),
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    strings = listOf("updateMetadata boxart image fetched, setting mediaSession metadata"),
)

private fun ClassDef.hasMethod(name: String, returnType: String) =
    methods.any { method ->
        method.name == name &&
            method.returnType == returnType &&
            method.parameterTypes.isEmpty()
    }

// Netflix already owns the MediaSession and publishes playback state, position
// and artwork. This opt-in patch fills in the standard text and duration fields
// from metadata Netflix already holds. The stock setters, artwork loader and
// MediaSession lifecycle remain intact.
//
// The patch adds no component, permission, log output, network call or
// app-specific receiver. Any authorized Android media controller can consume
// the resulting standard MediaSession metadata.
@Suppress("unused")
val publishPlaybackMetadataPatch = bytecodePatch(
    name = "Publish Netflix playback metadata",
    description = "Publishes the active title, episode label, duration and existing artwork " +
        "through Android's MediaSession for local media controllers. Adds no service, " +
        "permission, network call, logging or telemetry. Opt-in; targets Netflix Android TV " +
        "13.0.1 build 25028.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        val manager = mutableClassDefBy(MANAGER)
        val setPlaybackId = NetflixPlaybackIdFingerprint.method
        val setPlaybackMetadata = NetflixPlaybackMetadataFingerprint.method
        val artworkCallback = NetflixArtworkCallbackFingerprint.method
        val metadataType = setPlaybackMetadata.parameterTypes.single().toString()

        require(manager.fields.any { it.name == "mMediaSession" && it.type == MEDIA_SESSION }) {
            "Publish playback metadata: MediaSession field changed"
        }
        require(manager.fields.any { it.name == "mMetadata" && it.type == metadataType }) {
            "Publish playback metadata: metadata field changed"
        }
        require(manager.methods.none { it.name == "publishMorpheMetadata" }) {
            "Publish playback metadata: publisher method already exists"
        }
        require(classDefBy(ARTWORK_CALLBACK).fields.any {
            it.name == "this\$0" && it.type == MANAGER
        }) {
            "Publish playback metadata: artwork callback owner changed"
        }

        val metadataClass = classDefBy(metadataType)
        require(metadataClass.hasMethod("getTitle", "Ljava/lang/String;")) {
            "Publish playback metadata: metadata title getter changed"
        }
        require(metadataClass.hasMethod("getEpisode", "Ljava/lang/String;")) {
            "Publish playback metadata: metadata episode getter changed"
        }
        require(metadataClass.hasMethod("getRuntime", "I")) {
            "Publish playback metadata: metadata runtime getter changed"
        }

        listOf(setPlaybackId, setPlaybackMetadata, artworkCallback).forEach { method ->
            require(method.instructions.lastOrNull()?.opcode == Opcode.RETURN_VOID) {
                "Publish playback metadata: ${method.name} no longer has a terminal return"
            }
        }

        val publisher = ImmutableMethod(
            MANAGER,
            "publishMorpheMetadata",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(12),
        ).toMutable()
        publisher.addInstructions(
            0,
            """
                iget-object v0, p0, $MANAGER->mMediaSession:$MEDIA_SESSION
                if-eqz v0, :done

                iget-object v1, p0, $MANAGER->mMetadata:$metadataType
                if-eqz v1, :done

                invoke-virtual {v0}, $MEDIA_SESSION->getController()$MEDIA_CONTROLLER
                move-result-object v2
                if-eqz v2, :empty_builder
                invoke-virtual {v2}, $MEDIA_CONTROLLER->getMetadata()$MEDIA_METADATA
                move-result-object v3
                if-eqz v3, :empty_builder
                new-instance v4, $METADATA_BUILDER
                invoke-direct {v4, v3}, $METADATA_BUILDER-><init>($MEDIA_METADATA)V
                goto :title

                :empty_builder
                new-instance v4, $METADATA_BUILDER
                invoke-direct {v4}, $METADATA_BUILDER-><init>()V

                :title
                invoke-virtual {v1}, $metadataType->getTitle()Ljava/lang/String;
                move-result-object v5
                if-eqz v5, :episode
                invoke-virtual {v5}, Ljava/lang/String;->isEmpty()Z
                move-result v6
                if-nez v6, :episode
                const-string v6, "android.media.metadata.TITLE"
                invoke-virtual {v4, v6, v5}, $METADATA_BUILDER->putString(Ljava/lang/String;Ljava/lang/String;)$METADATA_BUILDER
                const-string v6, "android.media.metadata.DISPLAY_TITLE"
                invoke-virtual {v4, v6, v5}, $METADATA_BUILDER->putString(Ljava/lang/String;Ljava/lang/String;)$METADATA_BUILDER

                :episode
                invoke-virtual {v1}, $metadataType->getEpisode()Ljava/lang/String;
                move-result-object v7
                if-eqz v7, :duration
                invoke-virtual {v7}, Ljava/lang/String;->isEmpty()Z
                move-result v6
                if-nez v6, :duration
                const-string v6, "android.media.metadata.DISPLAY_SUBTITLE"
                invoke-virtual {v4, v6, v7}, $METADATA_BUILDER->putString(Ljava/lang/String;Ljava/lang/String;)$METADATA_BUILDER

                :duration
                invoke-virtual {v1}, $metadataType->getRuntime()I
                move-result v8
                if-lez v8, :publish
                int-to-long v9, v8
                const v6, 0x186a0
                if-ge v8, v6, :duration_ready
                const-wide/16 v5, 0x3e8
                mul-long/2addr v9, v5

                :duration_ready
                const-string v6, "android.media.metadata.DURATION"
                invoke-virtual {v4, v6, v9, v10}, $METADATA_BUILDER->putLong(Ljava/lang/String;J)$METADATA_BUILDER

                :publish
                invoke-virtual {v4}, $METADATA_BUILDER->build()$MEDIA_METADATA
                move-result-object v4
                invoke-virtual {v0, v4}, $MEDIA_SESSION->setMetadata($MEDIA_METADATA)V

                :done
                return-void
            """.trimIndent(),
        )
        manager.methods.add(publisher)

        setPlaybackId.addInstructions(
            setPlaybackId.instructions.lastIndex,
            "invoke-virtual {p0}, $MANAGER->publishMorpheMetadata()V",
        )
        setPlaybackMetadata.addInstructions(
            setPlaybackMetadata.instructions.lastIndex,
            "invoke-virtual {p0}, $MANAGER->publishMorpheMetadata()V",
        )
        artworkCallback.addInstructions(
            artworkCallback.instructions.lastIndex,
            """
                iget-object v0, p0, $ARTWORK_CALLBACK->this${'$'}0:$MANAGER
                invoke-virtual {v0}, $MANAGER->publishMorpheMetadata()V
            """.trimIndent(),
        )
    }
}
