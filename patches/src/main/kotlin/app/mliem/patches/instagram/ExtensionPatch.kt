package app.mliem.patches.instagram

import app.mliem.patches.util.invokeStaticRange
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/** Smali descriptors for the classes compiled from `extensions/instasave`. */
internal object Extension {
    const val INSTA_SAVE = "Lapp/mliem/extension/instasave/InstaSave;"
    const val STORY_OPTIONS = "Lapp/mliem/extension/instasave/StoryOptions;"
    const val MEDIA_OPTIONS = "Lapp/mliem/extension/instasave/MediaOptions;"
    const val IMAGE_VIEW_REGISTRY = "Lapp/mliem/extension/instasave/ImageViewRegistry;"
    const val MOBILE_CONFIG_OVERRIDES = "Lapp/mliem/extension/instasave/MobileConfigOverrides;"
    const val MEDIA_URL_RESOLVER = "Lapp/mliem/extension/instasave/MediaUrlResolver;"
    const val UPDATER = "Lapp/mliem/extension/instasave/Updater;"
}

/**
 * Merges the InstaSave extension dex and hands it an application Context.
 *
 * Internal, so it never appears in the Morphe manager. Every feature patch depends on it, and
 * because Morphe resolves each dependency once, the dex is merged once no matter how many
 * features the user enables.
 *
 * The Context comes from Instagram's own Application subclass rather than from
 * `ActivityThread.currentApplication()`, which is subject to hidden API restrictions, or from
 * walking view hierarchies, which is not available before the first screen is drawn.
 */
internal val instaSaveExtensionPatch = bytecodePatch {
    extendWith("extensions/instasave.mpe")

    execute {
        // Application.onCreate takes no arguments, so p0 is `this`, which is the Application.
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            invokeStaticRange(
                "p0", "p0",
                "${Extension.INSTA_SAVE}->setApplication(Landroid/content/Context;)V"
            )
        )

        // Tell the extension the obfuscated name of the username getter, so saved files can be
        // named after the account. The name changes every Instagram release, so it is resolved
        // here by a stable field id rather than hardcoded, and passed across as a plain string.
        //
        // Optional on purpose: if a future release moves the literal this fingerprint would stop
        // matching, and failing the whole build over a file naming detail would take every save
        // feature down with it. Without it the extension just falls back to an unnamed file.
        UserUsernameGetterFingerprint.matchOrNull()?.originalMethod?.let { usernameGetter ->
            // v0 is a local register here (onCreate declares many), and dex verification
            // guarantees the original code assigns it before reading it, so clobbering it at
            // index 0 is safe.
            InstagramAppShellOnCreateFingerprint.method.addInstructions(
                0,
                """
                    const-string v0, "${usernameGetter.name}"
                    ${invokeStaticRange(
                        "v0", "v0",
                        "${Extension.MEDIA_URL_RESOLVER}->setUsernameAccessor(Ljava/lang/String;)V"
                    )}
                """
            )
        }
    }
}
