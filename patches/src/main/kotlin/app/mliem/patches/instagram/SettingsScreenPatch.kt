package app.mliem.patches.instagram

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.resourcePatch

private const val SETTINGS_ACTIVITY = "app.mliem.extension.instasave.SettingsActivity"

/**
 * Adds the InstaSave settings screen as a standalone launcher activity.
 *
 * A patched Instagram offers no place to add a settings row without fingerprinting its own
 * obfuscated UI, so the settings live in a small Activity of our own, given its own icon in the
 * launcher ("InstaSave settings") rather than reached from inside the app. The Activity class
 * ships in the extension dex; this patch is the manifest declaration that makes it launchable.
 *
 * Depends on the extension merge (for the Activity class) and on the install-permission patch, so
 * the "check now" button can drive an install. The class name is fully qualified, so the package
 * rename the clone patch performs does not affect it.
 *
 * Idempotent: a manifest that already declares this activity is left untouched.
 */
internal val settingsScreenPatch = resourcePatch(
    name = "Settings screen",
    description = "Adds an InstaSave settings launcher icon with the update controls and the " +
        "video quality choice.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch, selfUpdatePermissionPatch)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
                ?: return@use

            val existing = document.getElementsByTagName("activity")
            for (index in 0 until existing.length) {
                val name = existing.item(index).attributes
                    ?.getNamedItem("android:name")?.nodeValue
                if (name == SETTINGS_ACTIVITY) {
                    return@use
                }
            }

            val activity = document.createElement("activity")
            activity.setAttribute("android:name", SETTINGS_ACTIVITY)
            activity.setAttribute("android:label", "InstaSave settings")
            // A launcher activity must be explicitly exported on modern Android.
            activity.setAttribute("android:exported", "true")
            // The dark system theme, so the screen does not inherit Instagram's own styling and
            // matches the explicit dark colors SettingsActivity sets on its own views.
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault")

            val filter = document.createElement("intent-filter")
            val action = document.createElement("action")
            action.setAttribute("android:name", "android.intent.action.MAIN")
            val category = document.createElement("category")
            category.setAttribute("android:name", "android.intent.category.LAUNCHER")
            filter.appendChild(action)
            filter.appendChild(category)
            activity.appendChild(filter)

            application.appendChild(activity)
        }
    }
}
