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
 * rather than deferring to Instagram.
 *
 * This is the fallback for builds where `unlockNativeDownloadPatch` does not light up Instagram's
 * own row. Prefer that patch: it needs no extension code, resolves no URLs, and writes no files,
 * so there is far less of it to break. Enable this one only if the native row does not appear.
 *
 * Instagram already models the row as a constant of the unobfuscated enum
 * `com.instagram.feed.media.mediaoption.MediaOption$Option`; it is simply filtered out of the
 * allowlist the menu is built from. So the injection appends a value to a list rather than
 * constructing a menu row, and Instagram renders it with its own styling.
 *
 * Off by default, because enabling both this and the native unlock would show two download rows.
 */
@Suppress("unused")
val mediaOptionDownloadPatch = bytecodePatch(
    name = "Save posts and reels (fallback)",
    description = "Adds a download entry to the post and reel menus and saves the media with " +
        "InstaSave's own downloader. Only needed when \"Unlock native download\" does not work; " +
        "enabling both shows two entries.",
    default = false
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
