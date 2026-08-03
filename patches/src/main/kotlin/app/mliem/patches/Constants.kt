package app.mliem.patches

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Deliberately not pinned to a version code.
     *
     * Every fingerprint in this bundle locates its target by things Instagram's obfuscator does
     * not rewrite: string literals inside the method body, unobfuscated type names such as
     * `MediaOption$Option` and `ImageUrl`, and hardcoded MobileConfig parameter ids. None of
     * that is tied to a release, so a new Instagram build normally needs a rebuild and nothing
     * else, which is the point of the rebase model this bundle is built around.
     *
     * Pinning `versionCodes` per ABI, as this previously did, actively defeats that: it forces
     * an edit for a release that would otherwise have patched unchanged.
     *
     * Before shipping a build, run `tools/verify_anchors.py` against the target APK. It reports
     * which anchors resolve and prints the enclosing method signature for each, which is
     * everything needed to repair a fingerprint whose target moved. Once a version is confirmed
     * working end to end on a device, add it here as a non experimental target.
     */
    val COMPATIBILITY_INSTAGRAM = Compatibility(
        packageName = "com.instagram.android",
        name = "Instagram",
        apkFileType = ApkFileType.APK,
        targets = listOf(
            AppTarget(
                version = null,
                isExperimental = true,
                description = "Any version. Targets are located by string literals and " +
                    "unobfuscated type names rather than by version."
            )
        )
    )
}
