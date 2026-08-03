package app.mliem.patches.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Helpers for generating injected smali against a method whose exact signature is only known at
 * patch time.
 *
 * Xposed hands a hook `param.args` as an `Object[]`, so it never has to know which argument is
 * which. Injected bytecode has no such luxury: it must name a concrete register. These helpers
 * derive that register from the resolved method rather than hardcoding `p1`, which is what keeps
 * the injection correct when Instagram reorders or adds a parameter.
 */

/**
 * Builds an `invoke-static` over a contiguous register range.
 *
 * Always uses the `/range` form, which is not a stylistic choice. A plain `invoke-static`
 * assembles to format 35c, whose register fields are four bits wide, so it cannot address
 * anything above v15. Parameter registers sit at the TOP of the frame, so in a method declaring
 * twenty registers `p0` is v19 and a plain invoke silently fails to assemble: the instruction is
 * dropped, the synthetic method ends up empty, and the patcher reports the useless
 * "Collection is empty". `invoke-static/range` is format 3rc with a sixteen bit base register and
 * has no such limit.
 *
 * Instagram's methods are large enough that this is the common case rather than an edge case;
 * `InstagramAppShell.onCreate` alone declares twenty registers.
 *
 * @param first first register in the range, such as `p0` or `v3`
 * @param last  last register in the range, inclusive. Pass the same value as [first] for one
 *              argument. Registers in between are passed too, so the range must be exactly the
 *              arguments wanted, in order.
 */
internal fun invokeStaticRange(first: String, last: String, target: String) =
    "invoke-static/range { $first .. $last }, $target"

/** True when the method is static, meaning `p0` is the first argument rather than `this`. */
internal val Method.isStatic: Boolean
    get() = AccessFlags.STATIC.isSet(accessFlags)

/**
 * The register holding `this`, or null for a static method.
 */
internal val Method.thisRegister: String?
    get() = if (isStatic) null else "p0"

/**
 * Finds the parameter register whose declared type satisfies [matches].
 *
 * Wide types (`J`, `D`) occupy two registers, so the offset cannot be assumed to equal the
 * parameter index.
 *
 * @return a register name such as `p2`, or null when no parameter matches.
 */
internal fun Method.parameterRegister(matches: (String) -> Boolean): String? {
    var register = if (isStatic) 0 else 1
    for (parameter in parameters) {
        val type = parameter.type
        if (matches(type)) {
            return "p$register"
        }
        register += if (type == "J" || type == "D") 2 else 1
    }
    return null
}

/** As [parameterRegister], but fails the build with a diagnosable message instead of returning null. */
internal fun Method.parameterRegisterOrThrow(description: String, matches: (String) -> Boolean): String =
    parameterRegister(matches)
        ?: throw PatchException(
            "$definingClass->$name: no parameter matching $description. " +
                "Actual parameters: ${parameters.joinToString { it.type }}. " +
                "The fingerprint resolved but the signature changed; re-run tools/verify_anchors.py."
        )

/**
 * Asserts the method has at least one register that is not a parameter register.
 *
 * Injected code needs a scratch register for `move-result`. In Dalvik the parameter registers sit
 * at the top of the frame, so when a method declares no locals `v0` is an alias of `p0` and
 * writing to it silently corrupts an argument that the original code still reads on the fall
 * through path. That corruption produces a crash far from the injection site, so it is worth
 * failing the build loudly instead.
 *
 * @return the scratch register to use.
 */
internal fun Method.scratchRegisterOrThrow(): String {
    val implementation = implementation
        ?: throw PatchException("$definingClass->$name has no implementation to patch")

    var parameterRegisters = if (isStatic) 0 else 1
    for (parameter in parameters) {
        parameterRegisters += if (parameter.type == "J" || parameter.type == "D") 2 else 1
    }

    val locals = implementation.registerCount - parameterRegisters
    if (locals < 1) {
        throw PatchException(
            "$definingClass->$name declares no local registers, so v0 aliases a parameter. " +
                "This injection needs a scratch register; widen the method or pick another target."
        )
    }
    return "v0"
}

/**
 * Rewrites every object return in the method so the returned value passes through [smali] first.
 *
 * Patching only the first return is an easy way to ship a half working menu: these builders
 * routinely carry an early return for restricted or deleted media, and that path would keep the
 * original value. Iterating in reverse keeps the earlier indices valid as instructions are
 * inserted ahead of them.
 *
 * @param smali receives the register holding the value about to be returned (as `v3`, not a
 *              bare number, since it may exceed v15 and must be usable with [invokeStaticRange]),
 *              and returns the smali to insert immediately before the return.
 */
internal fun MutableMethod.injectAtEveryObjectReturn(smali: (String) -> String) {
    val returnIndices = implementation
        ?.instructions
        ?.withIndex()
        ?.filter { it.value.opcode == Opcode.RETURN_OBJECT }
        ?.map { it.index }
        ?: throw PatchException("$definingClass->$name has no implementation to patch")

    if (returnIndices.isEmpty()) {
        throw PatchException("$definingClass->$name has no object return to wrap")
    }

    returnIndices.asReversed().forEach { index ->
        val register = "v" + getInstruction<OneRegisterInstruction>(index).registerA
        addInstructions(index, smali(register))
    }
}
