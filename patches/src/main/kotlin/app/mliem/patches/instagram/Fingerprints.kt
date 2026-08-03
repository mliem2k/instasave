package app.mliem.patches.instagram

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Every fingerprint in this bundle.
 *
 * None of them names an obfuscated class, method or field, because those are rewritten on every
 * Instagram release. They match on the three things the obfuscator leaves alone:
 *
 *  1. String literals inside a method body. R8 renames symbols; it does not rewrite constants.
 *  2. Type names Instagram does not obfuscate. A large part of `com.instagram.*` survives intact;
 *     only the classes under `LX` are renamed. `MediaOption$Option`, `ImageUrl`, `UserSession` and
 *     `Media` are all readable in a decompiled retail APK.
 *  3. Hardcoded numeric MobileConfig parameter ids, which are stable identities by design.
 *
 * Declaring each one as a named object is not required, but a failing fingerprint then names
 * itself in the stack trace, which is the difference between a one minute fix and an afternoon.
 *
 * `tools/verify_anchors.py` reads the string anchors straight out of this file, so the anchors
 * and the verifier cannot drift apart.
 */

private const val MEDIA_OPTION_ENUM = "/MediaOption\$Option;"

/**
 * `InstagramAppShell.onCreate`, the app's Application subclass.
 *
 * Used to hand the extension a Context at the earliest point one exists. The shape is copied
 * from `instagram-morphe-patches-library`'s own extension hook, which is known to resolve.
 */
internal object InstagramAppShellOnCreateFingerprint : Fingerprint(
    definingClass = "/InstagramAppShell;",
    name = "onCreate"
)

/**
 * The single method every boolean MobileConfig read passes through.
 *
 * Instagram 436 evaluated the two flags that hide the download row inside dedicated gate methods
 * shaped `(UserSession, Media) -> boolean`, each carrying its parameter id as a literal, so a
 * fingerprint could match the literal directly. By 440 those reads had been consolidated into one
 * generated dispatcher that switches on a discriminator field, loads the relevant id, and calls
 * `MobileConfigUnsafeContext.BTq(context, id)`. The literals moved out of the gates and into that
 * dispatcher, so matching them no longer finds anything patchable.
 *
 * Hooking the shared read instead is both simpler and more durable: it is where every flag is
 * answered, on any version, and `MobileConfigUnsafeContext` is a Facebook type Instagram does not
 * obfuscate. The interface itself is abstract, so this matches the class implementing it.
 *
 * Hooking a method this hot is safe here only because the extension returns null for every id it
 * does not recognise, leaving the original lookup untouched.
 */
internal object MobileConfigBooleanReadFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "J"),
    custom = { _, classDef ->
        classDef.interfaces.any { it.endsWith("/MobileConfigUnsafeContext;") }
    }
)

/**
 * The method producing the allowlist of options the feed post overflow menu may show.
 *
 * Identified by the option constants it reads. `MediaOption$Option` is not obfuscated, so its
 * static field references survive as readable names in the bytecode, and a method reading both
 * REPORT and HIDE_OPTIONS while returning a List is the allowlist and nothing else.
 *
 * This is the fallback path for builds where the two gates above are absent or insufficient.
 */
internal object MediaOptionAllowlistFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("Z"),
    custom = { method, _ ->
        fun readsOption(constant: String) = method.indexOfFirstInstruction {
            val reference = getReference<FieldReference>()
            reference != null &&
                reference.name == constant &&
                reference.definingClass.endsWith(MEDIA_OPTION_ENUM)
        } >= 0

        readsOption("REPORT") && readsOption("HIDE_OPTIONS")
    }
)

/**
 * The reel variant of the allowlist, which returns a concrete ArrayList rather than a List.
 *
 * The distinction matters to the verifier: writing a `List` back into a register a method
 * declares as returning `ArrayList` is rejected, so the two need separate injections.
 */
internal object ReelOptionListFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    custom = { method, _ ->
        fun readsOption(constant: String) = method.indexOfFirstInstruction {
            val reference = getReference<FieldReference>()
            reference != null &&
                reference.name == constant &&
                reference.definingClass.endsWith(MEDIA_OPTION_ENUM)
        } >= 0

        readsOption("PLAYBACK_CONTROLS") && readsOption("UNSAVE")
    }
)

/**
 * The handler invoked when an overflow menu option is tapped.
 *
 * A void method whose only parameter is the unobfuscated option enum. Because the parameter type
 * is not obfuscated, this needs no string anchor at all.
 */
internal object MediaOptionClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(MEDIA_OPTION_ENUM)
)

/**
 * The story viewer's options sheet builder.
 *
 * Returns the sheet's labels as a plain `CharSequence[]`, which is why appending an entry is an
 * array copy rather than constructing a menu row. Anchored on an internal debug label that ships
 * in retail builds and has no reason to be translated or renamed.
 */
internal object StoryOptionsBuilderFingerprint : Fingerprint(
    returnType = "[Ljava/lang/CharSequence;",
    parameters = listOf("L"),
    strings = listOf("[INTERNAL] Pause Playback")
)

/**
 * The story options tap handler, which dispatches on the tapped label.
 *
 * Anchored on three literals that co-occur only here: the surface name, the mute endpoint
 * template, and the same internal debug label the builder carries.
 */
internal object StoryOptionClickFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "explore_viewer",
        "friendships/mute_friend_reel/%s/",
        "[INTERNAL] Pause Playback"
    )
)

/**
 * `IgImageView`'s URL setter, called for every image the app renders.
 *
 * Both the view class and the `ImageUrl` interface keep their real names, so this resolves
 * without a string anchor. It is the seam that gives long press to save its URL, and it feeds
 * the recently bound URL fallback used when a click handler captured nothing reachable.
 */
internal object ImageUrlBindFingerprint : Fingerprint(
    definingClass = "/IgImageView;",
    returnType = "V",
    parameters = listOf("/ImageUrl;", "L")
)
