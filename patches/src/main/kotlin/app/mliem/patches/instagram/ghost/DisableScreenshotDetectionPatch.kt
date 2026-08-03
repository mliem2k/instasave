package app.mliem.patches.instagram.ghost

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.ScreenshotDetectionReportFingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

/**
 * Stops Instagram reporting when you screenshot a story, reel or disappearing DM.
 *
 * Note this is separate from InstaSave's own download, which cannot trigger screenshot detection
 * at all: saving pulls the media straight from the CDN and writes it with our own MediaStore
 * call, so no screen is ever captured and no Instagram reporting method is called. This patch
 * covers the other case, an actual manual screenshot, so that stays silent too.
 *
 * The observer callback that decides whether to send the report is a void method taking the media
 * id as a long. Replacing its body with a bare return is the static equivalent of the reference
 * module's runtime "cancel the callback": the report is never sent, whatever the caller passes.
 * A bare return needs no scratch register and no extension code, so this patch depends on nothing.
 */
@Suppress("unused")
val disableScreenshotDetectionPatch = bytecodePatch(
    name = "Disable screenshot detection",
    description = "Prevents Instagram from telling others when you screenshot their story, reel " +
        "or disappearing message.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        ScreenshotDetectionReportFingerprint.method.addInstruction(0, "return-void")
    }
}
