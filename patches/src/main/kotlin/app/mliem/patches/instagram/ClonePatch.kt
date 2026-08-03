package app.mliem.patches.instagram

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.library.instagram.patches.bypassSignatureCheckPatch
import app.morphe.library.instagram.patches.clonePatch

/**
 * Renames the package and app label so the patched build installs alongside the official app.
 *
 * This is why the whole bundle is built on Morphe rather than on LSPatch plus an Xposed module.
 * LSPatch cannot rename a package: the request was closed by its maintainer as too difficult
 * (LSPosed/LSPatch issue 179), so a rootless clone through that route would still need a Morphe
 * pass first, and then two toolchains and two signing steps for no capability gained.
 */
@Suppress("unused")
val clonePatch = clonePatch(
    defaultPackageName = "com.instagram.android.instasave",
    defaultAppName = "InstaSave",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
}

/**
 * Stops Instagram rejecting its own content providers after the APK is re-signed.
 *
 * Patching necessarily replaces the signing key, and Instagram verifies the caller signature on
 * provider access. Without this the clone installs and launches but fails in ways that look
 * unrelated to signing, so it is on by default and should stay that way for any patched build.
 */
@Suppress("unused")
val signatureCheckPatch = bypassSignatureCheckPatch(
    description = "Required for any patched build: patching re-signs the APK, and Instagram " +
        "rejects its own providers when the signature does not match the one it expects.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
}
