package app.mliem.patches.instagram

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.util.invokeStaticRange
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

/**
 * Kicks off a debounced background update check at app start.
 *
 * One injected instruction, shaped exactly like the setApplication hook: pass the Application
 * (p0 in the no-arg onCreate) to Updater.start. The Context is passed explicitly, so this does
 * not depend on the setApplication injection running first. Depends on the extension merge (for
 * the Updater class) and on the permission patch, so the build cannot ship the updater without
 * REQUEST_INSTALL_PACKAGES.
 *
 * The check is silent unless a newer release exists and is reachable. With the repository private,
 * GitHub's releases API answers 404 to an unauthenticated caller and the feature is simply inert,
 * so this being on by default costs nothing until releases are public.
 */
@Suppress("unused")
val selfUpdatePatch = bytecodePatch(
    name = "In-app updater",
    description = "Checks GitHub Releases once a day and offers to download and install a newer " +
        "InstaSave build via a notification. Needs the releases to be publicly reachable.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch, selfUpdatePermissionPatch)

    execute {
        // onCreate takes no arguments, so p0 is `this`, the Application, which is a Context.
        // invoke-static/range for the same reason the other hooks use it: onCreate declares
        // enough registers that p0 sits above v15 and a plain invoke would not assemble.
        InstagramAppShellOnCreateFingerprint.method.addInstruction(
            0,
            invokeStaticRange(
                "p0", "p0",
                "${Extension.UPDATER}->start(Landroid/content/Context;)V"
            )
        )
    }
}
