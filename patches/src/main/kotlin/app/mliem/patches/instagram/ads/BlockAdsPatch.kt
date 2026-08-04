package app.mliem.patches.instagram.ads

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.AdPodInsertionFingerprint
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.scratchRegisterOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

/**
 * Removes sponsored posts from feeds and reels, when the setting is on.
 *
 * The insertion decision is made by one method, so this consults the setting at its entry and,
 * when blocking is on, forces it to answer that the item is not a qualifying ad pod. Nothing is
 * inserted, so nothing renders, and the impression tracking that goes with rendering an ad never
 * fires either. That is the extent of what this touches: Instagram's own telemetry pipeline
 * beyond ad impressions has no comparable single choke point in this app, so it is not something
 * a bytecode patch can neutralize wholesale, and this feature does not claim to.
 */
@Suppress("unused")
val blockAdsPatch = bytecodePatch(
    name = "Block ads",
    description = "Removes sponsored posts from feeds and reels. Toggle it on the InstaSave " +
        "settings screen.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch)

    execute {
        AdPodInsertionFingerprint.method.apply {
            val scratch = scratchRegisterOrThrow()
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Lapp/mliem/extension/instasave/Settings;->blockAds()Z
                    move-result $scratch
                    if-eqz $scratch, :instasave_allow_ad
                    const/4 $scratch, 0x0
                    return $scratch
                    :instasave_allow_ad
                    nop
                """
            )
        }
    }
}
