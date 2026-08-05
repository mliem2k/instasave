package app.mliem.patches.instagram.download

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.Extension
import app.mliem.patches.instagram.ImageUrlBindFingerprint
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.isStatic
import app.mliem.patches.util.parameterRegisterOrThrow
import app.mliem.patches.util.scratchRegistersOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * Record what every image view is showing, which is what puts a save button on the post and what
 * makes long press to save work.
 *
 * Instagram's own image view is hooked where it binds a URL, a single seam covering every image
 * the app renders. Two things come out of it:
 *
 *  1. The bound view and its URL, so a save button can be attached to the post that image belongs
 *     to, and so a long press on any image (a profile picture especially, which has no options
 *     menu to add an entry to) knows what to save.
 *  2. A short history of recent binds, which the media resolver falls back to when a tap handler
 *     turns out to be a synthetic class that captured nothing reachable.
 *
 * The hook target moved here from what looked like the obvious URL setter. See
 * [ImageUrlBindFingerprint] for why that one was the wrong method and how it stayed wrong through
 * three releases: it resolved, so the patch reported applied, but it was an analytics setter with
 * two call sites and the hook never ran on a feed image.
 *
 * The injection is not a plain `invoke-static/range` because `this` and the `ImageUrl` are not
 * adjacent on the funnel method: a loader argument sits between them, and a range cannot skip a
 * register. Both are moved down into scratch registers first, which is safe at index 0 and only
 * there, since Dalvik's definite assignment rule means the original body cannot read a local
 * before writing it.
 */
@Suppress("unused")
val imageLongPressDownloadPatch = bytecodePatch(
    name = "Long press to save images",
    description = "Puts a save button on posts and saves any image, including profile pictures, " +
        "on a long press.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch)

    execute {
        ImageUrlBindFingerprint.method.apply {
            if (isStatic) {
                throw PatchException(
                    "$definingClass->$name is static, so there is no view to attach to. " +
                        "The fingerprint matched the wrong overload; re-run tools/verify_anchors.py."
                )
            }

            val urlRegister = parameterRegisterOrThrow("ImageUrl") { it.endsWith("/ImageUrl;") }
            val (viewScratch, urlScratch) = scratchRegistersOrThrow(2)

            // Injected at index 0 so the registry sees the URL before the load starts, which is
            // what lets a long press during loading still resolve.
            addInstructions(
                0,
                """
                    move-object/from16 $viewScratch, p0
                    move-object/from16 $urlScratch, $urlRegister
                    invoke-static { $viewScratch, $urlScratch }, ${Extension.IMAGE_VIEW_REGISTRY}->onImageUrlBound(Ljava/lang/Object;Ljava/lang/Object;)V
                """.trimIndent()
            )
        }
    }
}
