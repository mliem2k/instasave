package app.mliem.patches.instagram.download

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.Extension
import app.mliem.patches.instagram.StoryOptionClickFingerprint
import app.mliem.patches.instagram.StoryOptionsBuilderFingerprint
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.injectAtEveryObjectReturn
import app.mliem.patches.util.invokeStaticRange
import app.mliem.patches.util.parameterRegisterOrThrow
import app.mliem.patches.util.scratchRegisterOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

/**
 * Adds a save entry to the story viewer's options sheet.
 *
 * Stories are not part of the media option system that posts and reels use, so
 * `unlockNativeDownloadPatch` does not reach them and there is no existing row to unhide. This
 * patch adds one.
 *
 * The sheet is a plain `CharSequence[]` and taps are dispatched by comparing the tapped label, so
 * both halves are cheap: append one element, and intercept one string comparison.
 *
 * The object the media is resolved from is captured when the sheet is *built*, not when it is
 * tapped. The builder is an instance method on the controller that owns the current reel, so
 * `this` there is a dependable root. The tap handler is usually a synthetic lambda class whose
 * captured state moves between releases, and reaching into it is the fragile part of doing this
 * with a runtime hook. Injected bytecode can capture at the better of the two call sites.
 */
@Suppress("unused")
val storyDownloadPatch = bytecodePatch(
    name = "Save stories",
    description = "Adds a save entry to the story viewer's options sheet.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch)

    execute {
        StoryOptionsBuilderFingerprint.method.apply {
            // Capture the owner at entry, not at the return. p0 is the sheet's owner (`this` for
            // an instance builder, or the sole object argument for a static one), but Dalvik lets
            // a method reuse a parameter register as scratch once the argument is dead, so by the
            // return p0 may hold something else entirely. At index 0 it is always the receiver.
            addInstruction(
                0,
                invokeStaticRange(
                    "p0", "p0",
                    "${Extension.STORY_OPTIONS}->rememberOwner(Ljava/lang/Object;)V"
                )
            )

            injectAtEveryObjectReturn { register ->
                """
                    ${invokeStaticRange(register, register,
                        "${Extension.STORY_OPTIONS}->addOption([Ljava/lang/CharSequence;)[Ljava/lang/CharSequence;")}
                    move-result-object $register
                """
            }
        }

        StoryOptionClickFingerprint.method.apply {
            val labelRegister = parameterRegisterOrThrow("CharSequence") {
                it == "Ljava/lang/CharSequence;" || it == "Ljava/lang/String;"
            }
            val scratch = scratchRegisterOrThrow()

            addInstructionsWithLabels(
                0,
                """
                    ${invokeStaticRange(labelRegister, labelRegister,
                        "${Extension.STORY_OPTIONS}->onOptionClick(Ljava/lang/CharSequence;)Z")}
                    move-result $scratch
                    if-eqz $scratch, :instasave_not_handled
                    return-void
                    :instasave_not_handled
                    nop
                """
            )
        }
    }
}
