package app.mliem.patches.instagram.misc

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.DoubleTapLikeFingerprint
import app.mliem.patches.instagram.Extension
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.scratchRegisterOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

/**
 * Lets a double tap stop liking a post, when the setting is on.
 *
 * Instagram fires both a double tap like and the heart button through the same method, so this
 * cannot just neutralize that method or the heart button would break. It injects a guard at the
 * top that asks the extension whether this particular call came from a double tap, and returns
 * without liking only then. The decision, and the on or off setting, live in the extension.
 *
 * On by default, matching the Settings.disableDoubleTapLike() default the extension side reads.
 * The toggle to turn it back off is on the settings screen.
 */
@Suppress("unused")
val disableDoubleTapLikePatch = bytecodePatch(
    name = "Disable double tap to like",
    description = "Stops a double tap on a post from liking it. The heart button still works. " +
        "Toggle it on the InstaSave settings screen.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch)

    execute {
        DoubleTapLikeFingerprint.method.apply {
            val scratch = scratchRegisterOrThrow()
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, ${Extension.DOUBLE_TAP_LIKE}->shouldBlock()Z
                    move-result $scratch
                    if-eqz $scratch, :instasave_allow_like
                    return-void
                    :instasave_allow_like
                    nop
                """
            )
        }
    }
}
