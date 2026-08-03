package app.mliem.patches.instagram.download

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.Extension
import app.mliem.patches.instagram.ImageUrlBindFingerprint
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.invokeStaticRange
import app.mliem.patches.util.isStatic
import app.mliem.patches.util.parameterRegisterOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * Long press any image to save it, and record what each image view is showing.
 *
 * This is how profile pictures get a save action: they have no options menu to add an entry to,
 * so there is nothing to unhide or append. Instagram's own image view is hooked where it binds a
 * URL, which is a single seam covering every image the app renders.
 *
 * Two things come out of that seam:
 *
 *  1. A long press handler on views that nothing else claims. `isLongClickable()` is true only
 *     when something already registered a long press (post previews, reorder handles), and those
 *     surfaces have a menu anyway, so skipping them costs nothing and avoids breaking gestures
 *     Instagram relies on.
 *  2. A short history of recently bound URLs, which the media resolver falls back to when a tap
 *     handler turns out to be a synthetic class that captured nothing reachable.
 *
 * Both the view class and the `ImageUrl` interface keep their real names through obfuscation, so
 * this fingerprint needs no string anchor at all.
 */
@Suppress("unused")
val imageLongPressDownloadPatch = bytecodePatch(
    name = "Long press to save images",
    description = "Saves any image, including profile pictures, on a long press. Views that " +
        "already handle long press, such as feed post previews, are left alone.",
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
            if (urlRegister != "p1") {
                throw PatchException(
                    "$definingClass->$name takes ImageUrl at $urlRegister, not p1. " +
                        "invoke-static/range needs the receiver and the URL to be contiguous."
                )
            }

            // p0 is the image view itself and p1 the URL. Injecting at index 0 means the registry
            // sees the URL before the load starts, so a long press during loading still resolves.
            addInstruction(
                0,
                invokeStaticRange(
                    "p0", urlRegister,
                    "${Extension.IMAGE_VIEW_REGISTRY}->onImageUrlBound(Ljava/lang/Object;Ljava/lang/Object;)V"
                )
            )
        }
    }
}
