package app.mliem.patches.instagram.download

import app.mliem.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.mliem.patches.instagram.Extension
import app.mliem.patches.instagram.MobileConfigBooleanReadFingerprint
import app.mliem.patches.instagram.instaSaveExtensionPatch
import app.mliem.patches.util.invokeStaticRange
import app.mliem.patches.util.parameterRegisterOrThrow
import app.mliem.patches.util.scratchRegisterOrThrow
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * Unlocks the download row Instagram already ships.
 *
 * Instagram has a complete, working DOWNLOAD entry in the reel and feed post overflow menus, with
 * a click handler wired to its own save to camera roll flow. It is not missing, it is gated: two
 * boolean MobileConfig flags decide whether it appears, and for someone else's post the server
 * answers no to both. Forcing those two values is the whole feature. No menu row is constructed,
 * no media URL is resolved, and no bytes are written by us.
 *
 * The earlier approach in this repo, forcing MobileConfig flag `81702::5`, was aimed at exactly
 * this and failed for the reason `RESEARCH.md` records: a retail APK carries no id to name
 * mapping, so a flag cannot be discovered by decompiling. That remains true. What it does not
 * prevent is forcing an id you already know, because the id is a plain number and the method that
 * answers it is reachable.
 *
 * This hooks the single method every boolean flag read passes through rather than the individual
 * gates, which is why it survived Instagram consolidating those gates between 436 and 440. The
 * extension returns null for every id it does not recognise, so the only observable effect is on
 * the two ids it names.
 */
@Suppress("unused")
val unlockNativeDownloadPatch = bytecodePatch(
    name = "Unlock native download",
    description = "Unlocks the download option Instagram already ships for reels and feed posts " +
        "by forcing the two flags that hide it. Uses Instagram's own save flow.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    dependsOn(instaSaveExtensionPatch)

    execute {
        MobileConfigBooleanReadFingerprint.method.apply {
            val idRegister = parameterRegisterOrThrow("the long parameter id") { it == "J" }
            val scratch = scratchRegisterOrThrow()

            // A long occupies two consecutive registers, and the range form has to name both.
            val idSecondRegister = "p" + (idRegister.removePrefix("p").toIntOrNull()?.plus(1)
                ?: throw PatchException("could not derive the second half of $idRegister"))

            addInstructionsWithLabels(
                0,
                """
                    ${invokeStaticRange(idRegister, idSecondRegister,
                        "${Extension.MOBILE_CONFIG_OVERRIDES}->evaluate(J)Ljava/lang/Boolean;")}
                    move-result-object $scratch
                    if-eqz $scratch, :instasave_no_override
                    invoke-virtual { $scratch }, Ljava/lang/Boolean;->booleanValue()Z
                    move-result $scratch
                    return $scratch
                    :instasave_no_override
                    nop
                """
            )
        }
    }
}
