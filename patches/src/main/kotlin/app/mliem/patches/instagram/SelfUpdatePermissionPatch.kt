package app.mliem.patches.instagram

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.resourcePatch

/**
 * Adds {@code REQUEST_INSTALL_PACKAGES} so the in-app updater can drive PackageInstaller.
 *
 * Instagram does not declare this permission (verified against the 440 manifest), and a
 * dex-merged extension contributes classes only, never manifest entries, so it has to be injected
 * into the host manifest here. This edits {@code AndroidManifest.xml} through the same W3C
 * document API the clone patch already uses.
 *
 * Idempotent: if the permission is somehow already present, nothing is added. The updater patch
 * depends on this one, so enabling the updater cannot ship without the permission.
 *
 * POST_NOTIFICATIONS, which the updater's notification also needs, is already declared by
 * Instagram, so it is not added here.
 */
internal val selfUpdatePermissionPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        val permission = "android.permission.REQUEST_INSTALL_PACKAGES"

        document("AndroidManifest.xml").use { document ->
            val existing = document.getElementsByTagName("uses-permission")
            for (index in 0 until existing.length) {
                val name = existing.item(index).attributes
                    ?.getNamedItem("android:name")?.nodeValue
                if (name == permission) {
                    return@use
                }
            }

            val node = document.createElement("uses-permission")
            node.setAttribute("android:name", permission)
            document.documentElement.appendChild(node)
        }
    }
}
