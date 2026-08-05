package app.mliem.patches.instagram.download

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.Extension
import app.mliem.patches.instagram.MediaOptionAllowlistFingerprint
import app.mliem.patches.instagram.MediaOptionClickFingerprint
import app.mliem.patches.instagram.ReelOptionListFingerprint
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.injectAtEveryObjectReturn
import app.mliem.patches.util.invokeStaticRange
import app.mliem.patches.util.isStatic
import app.mliem.patches.util.parameterRegisterOrThrow
import app.mliem.patches.util.scratchRegisterOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

/**
 * Adds a download entry to the feed post and reel overflow menus, and handles taps on it here
 * rather than deferring to Instagram, as a second, independent layer under `unlockNativeDownloadPatch`.
 *
 * The actual reason a multi image carousel or a reel could come back without a download row turned
 * out to live one level below the allowlist this patch injects into: a separate eligibility check,
 * shared by the feed and carousel candidate list builder and the reel menu's own row builder,
 * decides per item whether DOWNLOAD is a candidate at all before this allowlist is ever consulted.
 * `unlockNativeDownloadPatch` now hooks that eligibility check directly, which is the fix that
 * actually matters for carousels and reels; see its own doc comment for the full trace. This patch
 * remains as a second, independent layer: it appends the download entry to the SAME allowlist
 * Instagram's own native flow renders from, wherever it finds it missing, in case some other, still
 * untraced path excludes it from that allowlist even once it is a genuine candidate.
 *
 * Enabling this alongside `unlockNativeDownloadPatch` is deliberate, not a duplicate risk: the
 * injected code only appends the constant when the list does not already contain it
 * (`MediaOptions.addDownloadOption`), and `MediaOption$Option.DOWNLOAD` is a single enum instance
 * with default, reference equality `equals()`, so `List.contains` correctly detects Instagram's
 * own entry when the native unlock already put it there. Where the native row already appears,
 * this is a no-op; where it does not, this is what actually gives the post a download option.
 */
@Suppress("unused")
val mediaOptionDownloadPatch = bytecodePatch(
    name = "Save posts and reels (fallback)",
    description = "Adds a download entry to the post and reel menus wherever Instagram's own row " +
        "does not appear, most notably on multi image posts. Safe to run alongside " +
        "\"Unlock native download\"; it does not duplicate an entry that is already there.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch)

    execute {
        // The allowlist that decides which options a feed post menu may contain.
        MediaOptionAllowlistFingerprint.method.injectAtEveryObjectReturn { register ->
            """
                ${invokeStaticRange(register, register,
                    "${Extension.MEDIA_OPTIONS}->addDownloadOption(Ljava/util/List;)Ljava/util/List;")}
                move-result-object $register
            """
        }

        // The reel variant returns a concrete ArrayList. Writing a List back into a register the
        // method declares as ArrayList would fail dex verification, so it needs its own call.
        // Optional: some builds route reels through the List allowlist above instead.
        ReelOptionListFingerprint.matchOrNull()?.method?.injectAtEveryObjectReturn { register ->
            """
                ${invokeStaticRange(register, register,
                    "${Extension.MEDIA_OPTIONS}->addDownloadOptionToArrayList(Ljava/util/ArrayList;)Ljava/util/ArrayList;")}
                move-result-object $register
            """
        }

        // The tap handler. Instagram has no branch for a row it did not intend to show, so ours
        // consumes the event and returns before its dispatch runs.
        MediaOptionClickFingerprint.method.apply {
            val optionRegister = parameterRegisterOrThrow("MediaOption\$Option") {
                it.endsWith("/MediaOption\$Option;")
            }
            val scratch = scratchRegisterOrThrow()

            // invoke-static/range needs a contiguous ascending range. On an instance handler the
            // receiver and the sole option parameter are exactly p0 and p1, so both can be passed.
            // A static handler has no receiver, so it calls the one argument form and the
            // extension falls back to the most recently bound image URL.
            val call = if (isStatic) {
                invokeStaticRange(
                    optionRegister, optionRegister,
                    "${Extension.MEDIA_OPTIONS}->onOptionClick(Ljava/lang/Object;)Z"
                )
            } else {
                invokeStaticRange(
                    "p0", optionRegister,
                    "${Extension.MEDIA_OPTIONS}->onOptionClick(Ljava/lang/Object;Ljava/lang/Object;)Z"
                )
            }

            addInstructionsWithLabels(
                0,
                """
                    $call
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
