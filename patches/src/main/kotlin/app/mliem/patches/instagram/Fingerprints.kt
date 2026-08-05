package app.mliem.patches.instagram

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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

private const val THIRD_PARTY_DOWNLOAD_ELIGIBILITY_MARKER =
    "android_purge_26_q3_ClipsDownloadUtil_isMediaEligibleForThirdPartyDownloads"

/**
 * Whether one piece of media is eligible for a third party download row at all.
 *
 * A feed post, every item inside a carousel, and a reel's own "redesigned" menu builder all reach
 * this one method before the option is ever added as a menu candidate; forcing the two MobileConfig
 * ids it happens to read is not enough on its own, since the method also branches on an organic,
 * non remix clip check, a media type comparison, a separate kill switch, and an account level
 * bypass that has nothing to do with MobileConfig. A carousel item or a reel that fails any one of
 * those still comes back ineligible no matter which flag ids are forced. Hooking this method
 * directly, so it always answers eligible, is what actually reaches every caller uniformly, the
 * same reasoning that already justifies hooking the shared MobileConfig reader above instead of
 * chasing every gate that calls it.
 *
 * Anchored on its own internal trace label, unique in the whole app.
 */
internal object ThirdPartyDownloadEligibilityFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "/Media;"),
    strings = listOf(THIRD_PARTY_DOWNLOAD_ELIGIBILITY_MARKER)
)

/**
 * The sibling restriction check: answering true hides the row regardless of eligibility above.
 *
 * Carries no string of its own, so it is identified by shape, a boolean parameter alongside the
 * boolean return, plus a sibling method in the same class carrying the eligibility marker. That
 * pair is unique: only the eligibility check's own class declares a `(L, Z) -> Z` method at all.
 */
internal object ThirdPartyDownloadRestrictionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "Z"),
    custom = { _, classDef ->
        classDef.methods.any { sibling ->
            sibling.indexOfFirstInstruction {
                getReference<StringReference>()?.string == THIRD_PARTY_DOWNLOAD_ELIGIBILITY_MARKER
            } >= 0
        }
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
 * `IgImageView.setUrlInternal`, the single funnel every image bind passes through.
 *
 * Both the view class and the `ImageUrl` interface keep their real names, so this resolves
 * without a string anchor. It is the seam that gives long press to save its URL, that puts the
 * save button on the right post, and that feeds the recently bound URL fallback used when a
 * click handler captured nothing reachable.
 *
 * The shape here is deliberate and load bearing. This used to be declared as two parameters,
 * `("/ImageUrl;", "L")`, which reads like the obvious URL setter and is not: on 440 the only
 * method on this class with that shape is `setTrackingUrl`, an analytics only setter with two
 * call sites in the entire app, both inside one unrelated class. So the injection resolved, the
 * patch reported applied, the build was signed, and the hook then never fired on a single feed
 * image. Everything downstream of it was dead: long press to save recorded no URL for any view,
 * so it silently did nothing, and the save button had no bound view to attach itself to, so it
 * never appeared. Three releases treated the symptoms of that.
 *
 * The real path is `setUrl` (339 call sites) into `setUrlInternal`, which every other entry
 * (`setUrlWithFallback`, `A0F`, and the Vito backed variant it delegates to internally) also
 * funnels into, so hooking it once covers every image the app renders on either loader. It is
 * identified by shape rather than by name: seven parameters with the `ImageUrl` second is unique
 * on this class, where the name is not guaranteed to survive a future build's obfuscation.
 *
 * The lesson worth keeping: a fingerprint that resolves is not a fingerprint that is correct.
 * Count the call sites of whatever it matched before trusting it.
 */
internal object ImageUrlBindFingerprint : Fingerprint(
    definingClass = "/IgImageView;",
    returnType = "V",
    custom = { method, _ ->
        method.parameters.size == 7 &&
            method.parameters[1].type.endsWith("/ImageUrl;")
    }
)

/**
 * The accessor that returns a user's handle, so a saved file can be named after the account.
 *
 * Instagram exposes no `getUsername()` anywhere in the app; on 440 the getter is an obfuscated
 * name on `com.instagram.user.model.User` (`A05`), so it cannot be called by name at runtime.
 * What is stable is the GraphQL field id compiled into it as a literal, which is what this
 * matches. The patch then hands the resolved name to the extension, so the obfuscated name is
 * discovered per build instead of being hardcoded.
 *
 * The defining class is spelled in full because several unrelated `User` classes exist (direct
 * messaging protobuf models, a Meta credentials one), and only this one is the media author.
 */
internal object UserUsernameGetterFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/user/model/User;",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(
        // The username field id. Negative, and written -0xfd6772a in smali.
        literal(-265713450)
    )
)

/**
 * The like action reached by both a double tap and the heart button.
 *
 * Instagram routes both through this one method, so it is anchored by the two analytics strings
 * that only its double-tap-aware like path carries. The patch does not neutralize it (that would
 * kill the heart button too); it injects a guard that returns only when the like came from a
 * double tap, which the extension tells from the call stack.
 */
internal object DoubleTapLikeFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("double_tap_on_liked", "used_double_tap")
)

/**
 * The method deciding whether a sponsored item is inserted into a feed or reel.
 *
 * Anchored on its own trace label, which is unique to it in the whole app. Forcing it to answer
 * false is the entire feature: no ad is ever inserted, and since the ad never renders, the
 * impression tracking that would accompany showing one never fires either.
 */
internal object AdPodInsertionFingerprint : Fingerprint(
    returnType = "Z",
    strings = listOf("Is ad pod")
)

/**
 * The screenshot-detection observer callback for stories, reels and disappearing DMs.
 *
 * When Android reports a screenshot, Instagram's detector posts a callback to each registered
 * observer; the observer for these surfaces decides whether to send the "X screenshotted your
 * story" report. That callback is a void method taking a single long (the media id) and carries
 * no string of its own, so it is anchored by shape plus the "ScreenshotNotificationManager"
 * literal that a sibling method in the same class registers with. That pair is unique in the app:
 * only this class has both a (long) -> void method and that string. Neutralizing the method
 * suppresses the report. This is the static equivalent of the reference module's runtime hook,
 * which finds the same void(long) by the same string and cancels it.
 *
 * Not a MobileConfig flag: the gate is per-conversation runtime state, so there is no id to
 * force, and returning early from the emit method is the correct lever.
 *
 * The callback carries no string of its own, so the parameters plus the class-level string are
 * what select it. On Instagram 440 that class declares exactly one `(J)V` method, so the match is
 * unambiguous. The custom block also asserts that uniqueness: if a future release adds a second
 * `(J)V` to the same class, the fingerprint fails to resolve and the build stops, rather than
 * silently neutralizing the wrong method.
 */
internal object ScreenshotDetectionReportFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("J"),
    custom = { _, classDef ->
        val referencesString = classDef.methods.any { sibling ->
            sibling.indexOfFirstInstruction {
                getReference<StringReference>()?.string == "ScreenshotNotificationManager"
            } >= 0
        }
        val voidLongMethods = classDef.methods.count { candidate ->
            candidate.returnType == "V" && candidate.parameterTypes.singleOrNull()?.toString() == "J"
        }
        referencesString && voidLongMethods == 1
    }
)
