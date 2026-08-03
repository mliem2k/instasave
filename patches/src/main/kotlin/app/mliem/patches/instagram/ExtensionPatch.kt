package app.mliem.patches.instagram

import app.mliem.patches.util.invokeStaticRange
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

/** Smali descriptors for the classes compiled from `extensions/instasave`. */
internal object Extension {
    const val INSTA_SAVE = "Lapp/mliem/extension/instasave/InstaSave;"
    const val STORY_OPTIONS = "Lapp/mliem/extension/instasave/StoryOptions;"
    const val MEDIA_OPTIONS = "Lapp/mliem/extension/instasave/MediaOptions;"
    const val IMAGE_VIEW_REGISTRY = "Lapp/mliem/extension/instasave/ImageViewRegistry;"
    const val MOBILE_CONFIG_OVERRIDES = "Lapp/mliem/extension/instasave/MobileConfigOverrides;"
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
    }
}
