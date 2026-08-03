# Research notes: finding a save/download hook in Instagram 430.0.0.53.80

Working copy: Instagram 430.0.0.53.80 (arm64-v8a, versionCode 383611248), downloaded from
APKMirror and hash-verified (SHA-256 `38ae9861b9ca89f60f41767324e1c3d54a4e3a00ed5555b92660a08e6db14754`,
matches APKMirror's published signature). Decompiled locally with JADX (`--no-res`, 156k classes,
412 decompile errors out of ~106k, normal for an app this size/obfuscated) and separately with
`apktool d` for resources + smali (needed because JADX applies its own renaming to classes with
invalid Java identifiers, e.g. names starting with a digit, which makes JADX's Java view
inconsistent with the real bytecode-level class names - smali is ground truth).

Neither the APK, nor any decompiled output, nor smali dumps are committed to this repo - none of
that is redistributable. Anyone continuing this needs to repeat the download + decompile steps
locally.

## Dead end: MobileConfig numeric flags have no local name mapping

The existing community patch `Hide Reels save button` (`brosssh/morphe-patches`) works by
overriding MobileConfig flag `81702::5` (name: `ig_channels_4th_ufi::android_hide_save_button`).
Exhaustively grepping the decompiled APK for that flag's name string, or `MobileConfig`/
`QuickExperiment` name tables in general, turned up **no local id-to-name mapping** - only opaque
numeric ids at runtime (`getMobileConfigFlagId()` bit-shifts a `long` into an int, no strings
involved). Whoever originally identified `81702::5` and its name must have used something outside
this retail APK (an internal/dogfood build, a leak, or documentation) - it isn't something we can
reproduce by decompiling this build alone. **This whole approach doesn't scale to finding new
flags for feed/story downloads.**

## Better lead, also a dead end: `can_viewer_save` JSON field

Instagram's GraphQL/JSON *response field names* (unlike UI text or MobileConfig flag names) are
compiled in as literal strings, because they're used for JSON (de)serialization. Found:

- `"can_viewer_save"` (media JSON field) - hash `-283088485`, read via
  `LiveTreeMediaDict.smali` (`com/instagram/feed/media/LiveTreeMediaDict.smali`) from a native
  Pando/LiveTree tree (`LiveTreeJNI.getOptionalBooleanValueNative(-283088485)`)
- Stored on the core media model class, real smali name `LX/08tu;` (JADX renames this to
  `C2256308tu.java` because `08tu` is an invalid Java identifier - **always check smali for the
  real class name before searching further; JADX's synthetic names don't round-trip**), field
  `LX/08tu;->A3C:Ljava/lang/Boolean;`.
- Exhaustively grepped every smali file (all `smali*/` dirs) for the exact fully-qualified field
  reference `LX/08tu;->A3C:Ljava/lang/Boolean;`. Only 4 hits, all in serialization/model-merge
  code (`X/08tu.smali`, `X/08fq.smali`, `X/09iu.smali`,
  `com/instagram/feed/media/LiveTreeMediaDict.smali`). **Nothing in the entire app reads this
  field to gate a UI element.** It's set on the model and then never consulted.

Conclusion: for regular feed posts, there is likely no "can this user download this" boolean
being checked client-side at all. The save/download option may simply not exist as a UI branch
for normal posts (as opposed to Reels/Channels, which do have such a check per the `Hide Reels
save button` patch) - the server just never asked the client to render one.

## Recommended pivot: don't chase a UI-gating flag, tap the media cache directly

The Instagram app has to fully download a post/story/reel's image or video bytes to *display*
it, regardless of any save permission. Confirmed present in this build as identifiable,
**unobfuscated, publicly-documented open-source libraries** (much more stable targets than
Instagram's own obfuscated classes, and far less likely to need re-patching every version):

- `com.facebook.imagepipeline` (Fresco) - 10 classes present, handles image loading/disk cache
- `androidx.media3` - 14 classes present (video playback; this build uses Media3, not legacy
  ExoPlayer)

Instead of finding/forcing a "show save button" flag, the more robust design is:
1. Add a "Save" entry to the existing triple-dot bottom sheet for posts/stories (still need to
   locate that specific menu-builder call site - not yet done).
2. Wire it to pull the already-fetched bytes for the currently-displayed media straight out of
   Fresco's disk cache (images) or Media3's cache (video), using their public APIs
   (`ImagePipeline`/`CacheKey` for Fresco, `Cache`/`DataSource` for Media3), and write them to
   `MediaStore`.

This avoids relying on any Instagram-specific permission flag entirely - it just copies bytes the
app already downloaded for its own rendering, which is unconditional. It's also more
version-resilient: Fresco/Media3 APIs change far less often than Instagram's internal obfuscated
bytecode.

## Why static tracing keeps dead-ending: Pando/LiveTree

Followed a second lead to see if grabbing direct CDN media URLs (bypassing the save-button
question entirely - just fetch `image_versions2`/`video_versions` URLs the same way
Instaloader/gallery-dl do from the public API) would be easier. It resolves the same way:

- `image_versions2` is stored as `LX/08tu;->A1S:LX/0ODE;` - not a plain list, an interface
  (`LX/0ODE`) that extends `LX/0TaG` (the same native-tree marker interface seen on
  `LiveTreeMediaDict`) and exposes `AXj()Lcom/facebook/pando/...` /
  `CcN()LX/0ODH;` accessors into a native tree object, not a Java field read.
- `video_versions` resolves through `LX/0TaG;->H4p()Lcom/facebook/pando/TreeUpdaterJNI;` -
  again the native Pando tree, not a plain field.

So all three independent leads (`can_viewer_save`, `image_versions2`, `video_versions`) bottom
out in the same place: Meta's apps now store GraphQL-shaped data as a compact binary tree
("Pando"/"LiveTree"), materialized through JNI, not as plain Java fields/lists you can trace by
grepping smali field references. This is a deliberate architectural choice (also used by
Facebook/WhatsApp), not incidental obfuscation, and it's the real reason pure static
grep-and-trace analysis has hit a wall on three separate attempts now rather than just needing
"more grepping."

**Recommendation: switch to dynamic analysis.** A live device or emulator with Frida attached,
hooking `LiveTreeJNI.getOptionalBooleanValueNative` / the `TreeUpdaterJNI`/`TreeWithGraphQL`
accessors (or simpler: hooking Fresco's `ImagePipeline.fetchDecodedImage` and Media3's
`DataSource.open`, both plain Java/Kotlin, not tree-backed) while manually using the real app
would surface the actual runtime values and call stacks in minutes, versus more hours of blind
static tracing through native tree indirection.

## Dynamic analysis environment (set up, blocked on login)

Set up a full local dynamic-analysis rig:

- Android SDK cmdline-tools (Homebrew `android-commandlinetools`), an API 34 `google_apis`
  arm64-v8a AVD (`instasave-test`, in `~/.android/avd/`), booted headless
  (`emulator -avd instasave-test -no-window -no-audio -no-boot-anim`).
- `adb root` works (emulator image is rootable/debuggable).
- Installed the same hash-verified Instagram 430.0.0.53.80 APK from the static analysis section
  above.
- `frida-server` 17.15.3 (arm64, matched to the `frida`/`frida-tools` 17.15.3 pip install on the
  host) pushed to `/data/local/tmp` and running as root.
- Frida 17.x note: the classic `session.create_script("... Java.perform ...")` API **no longer
  auto-injects the Java bridge** (`typeof Java === 'undefined'`, and there's no `require()` in
  the bare script context either). Fix: `npm install frida-java-bridge` in a scratch dir, write
  the agent as an ES module (`import Java from 'frida-java-bridge'`), and run it through
  `frida-compile` to bundle before loading with `session.create_script()`. Bare unbundled scripts
  will silently fail to see `Java` on this Frida version.

**Confirmed blocked without login**: this Instagram build has no logged-out browsing mode at
all. A deep link to a public profile (`https://www.instagram.com/instagram/`) redirects straight
to a "Join Instagram" signup wall - there is no preview. Per the project owner's choice, we are
not logging into any account (real or test) from this instrumented/rooted emulator, so
post/story/feed UI is currently unreachable.

Confirmed empirically via Frida: at the pre-login screen, **zero** `com.facebook.imagepipeline.*`
or `androidx.media3.*` classes are loaded yet (`Java.enumerateLoadedClassesSync()` returns 0
matches) - these are lazy-loaded only once a real content screen is reached, which requires
authentication. So there is currently no dynamic-analysis path that doesn't require logging in.

The environment (emulator + frida-server + APK) is left set up and running for whenever login
becomes acceptable (e.g. a disposable test account, entered by the project owner directly rather
than by the assistant).

### Update: logged in, but Frida's Java bridge crashes natively on this image

Project owner logged into the app directly on the emulator (real account, real feed reachable).
Attempted the planned "snapshot loaded classes, tap the post's triple-dot menu, snapshot again,
diff" technique to find the menu-builder class without needing to know its name in advance.

Blocked by a reproducible crash: **any** `Java.perform(...)` call - not anything specific to our
own hook code - crashes with `Error: illegal instruction` inside frida-java-bridge's
`tryGetEnvJvmti()` (`android.js`), which calls ART's
`art::Runtime::EnsurePluginLoaded("libopenjdkjvmti.so", ...)` to get a JVMTI environment. That
native call is what's crashing. Tried:

- `frida-java-bridge` 7.0.13 (current) - crashes as above
- `frida-java-bridge` 7.0.0 - crashes earlier, differently (`TypeError: not a function` in
  `_getApi2`)
- `frida-java-bridge` 6.3.9 - fails to even load in the `frida-compile` bundle (`global` is not
  defined - this version predates compatibility with current `frida-compile`'s bundling target)
- Both `Java.enumerateLoadedClassesSync()` and the callback-based `Java.enumerateLoadedClasses()`
  trigger it identically - it's not about which API we call, `Java.perform()`'s own internal
  bootstrap does this via `class-factory.js: use()` on some internal helper class before our code
  ever runs.

This looks like a genuine Frida-core/frida-java-bridge/ART incompatibility specific to this
`google_apis` API 34 arm64-v8a emulator image, not something fixable from script level.

### Update: API 33 "fix" only worked transiently - looks like emulator resource pressure, not a version bug

Created a second AVD on API 33 (`instasave-test-api33`, same Pixel 6 device profile, same
arm64-v8a `google_apis` image family, same frida-server 17.15.3 + frida-java-bridge 7.0.13). The
very first `Java.perform()`-based snapshot call succeeded (31,228 classes enumerated, no crash) -
looked like confirmation that API 34 specifically was the problem.

It wasn't. After logging in (real account, project owner entered credentials directly) and using
the app briefly, the emulator's System UI hit an ANR ("system memory pressure" warnings were
already present in the emulator's boot log - only 2.5GB RAM allocated to the AVD, and it fell
back to software GL rendering because of it). After recovering from the ANR and relaunching
Instagram, the *exact same* `tryGetEnvJvmti` illegal-instruction crash came back, reproducibly,
on this API 33 AVD too - on a process that had successfully done `Java.perform()` shortly before.

So the API-34-vs-33 result was likely a coincidence of process state (first attach on a
freshly-launched, idle, pre-login process), not an actual API-level fix. The real cause looks
like general emulator resource pressure/instability under this Android-13/14 arm64 config on this
host, which intermittently corrupts something the JVMTI plugin loader depends on. This is not
something worth continuing to chase with more Frida version combinations - it's environmental,
and a resource-constrained software-rendered emulator is a bad fit for this specific technique
regardless of API level.

**Not pursued further given time already spent.** If dynamic analysis is picked back up later,
a physical device (real GPU, real RAM, no ANR-prone software rendering) is a much better bet than
continuing to fight emulator resource pressure.

## Resolution: both open questions were answered by reading a working implementation

The two questions left open above (the menu builder, and getting from a tap to a media URL) were
both answered by reading [InstaEclipse](https://github.com/ReSo7200/InstaEclipse), a GPLv3
LSPosed module that already implements story, post, reel and profile picture download and is
tested against Instagram 436.x. Everything in this section came from its source rather than from
further analysis of this APK, and none of it is copied into this repo. See NOTICE.

### The Pando wall is an artifact of static analysis, not a property of the app

The conclusion above, that `can_viewer_save`, `image_versions2` and `video_versions` all bottom
out in JNI tree accessors, is correct and also irrelevant to the goal. The tree is materialized
into ordinary Java objects before any UI callback runs, so a bounded reflective walk over the
live graph sees plain values. Recognising them needs only the fact that Instagram leaves two
interfaces unobfuscated:

- `com.instagram.model.mediasize.VideoVersionIntf`, with `getUrl()`
- `com.instagram.common.typedurl.ImageUrl`, with `getUrl()`, `getWidth()`, `getHeight()`

That is what `extensions/instasave/.../MediaUrlResolver.java` does. No field path is traced, so
there is nothing for obfuscation to invalidate.

The broader mistake worth recording: this APK is not uniformly obfuscated. Only the `LX/*`
classes are renamed. A large `com.instagram.*` surface survives intact, including
`MediaOption$Option`, `ImageUrl`, `VideoVersionIntf`, `UserSession`, `Media`, `IgImageView` and
`InstagramAppShell`. Several dead ends above were spent tracing `LX/08tu;` while unobfuscated
types sat one level away.

### Instagram already has a working download row, gated by two booleans

The single most useful finding. Instagram ships a complete DOWNLOAD entry for reels and, since
the post and reel overflow menus were merged onto one shared row builder, for feed posts, with a
click handler wired to its own save to camera roll flow. Two boolean methods hide it:

| Gate | Signature | Literal | Forced to |
|---|---|---|---|
| Can this media be downloaded | `(UserSession, Media) -> boolean` | `36313978552585585` (`0x81035f00020d71`) | `true` |
| Is the viewer restricted | `(UserSession, boolean) -> boolean` | `36313978552847731` (`0x81035f00060d73`) | `false` |

These are MobileConfig parameter ids compiled into each method as literals. Note what that does
to the dead end recorded at the top of this file. The id to name mapping really is absent from a
retail APK, so new flags cannot be *discovered* by decompiling, but a known id can still be
*matched*, because the literal is sitting in the bytecode. Matching the literal sidesteps the
naming problem completely and needs no `overrideMobileConfigBooleanFlag` call at all.

`unlockNativeDownloadPatch` is those two lines and nothing else.

### Anchors for the remaining surfaces

All checkable with `tools/verify_anchors.py`.

| Target | Anchor |
|---|---|
| Story options builder | string `[INTERNAL] Pause Playback`, 1 param, returns `CharSequence[]` |
| Story options tap handler | strings `explore_viewer` + `friendships/mute_friend_reel/%s/` + `[INTERNAL] Pause Playback`, returns void |
| Feed post option allowlist | returns `List`, takes `boolean`, reads `MediaOption$Option.REPORT` and `.HIDE_OPTIONS` |
| Reel option list builder | returns `ArrayList`, reads `MediaOption$Option.PLAYBACK_CONTROLS` and `.UNSAVE` |
| Option tap handler | returns void, sole parameter is `MediaOption$Option` |
| Image URL bind | on `IgImageView`, returns void, first parameter is `ImageUrl` |
| Application context | `InstagramAppShell.onCreate` |

The story sheet turned out to be a plain `CharSequence[]` dispatched by comparing the tapped
label, so "trace the bottom sheet menu builder" was a much smaller task than expected once the
anchor was known.

### Dynamic analysis was never needed

The recommendation above to switch to Frida stands as sound advice this project did not have to
take. Every anchor here is a string literal or an unobfuscated type name, all findable with grep
over apktool output. The emulator instability documented above stopped being a blocker rather
than being solved.

### Deployment: LSPatch cannot clone

Worth recording since it is the obvious alternative architecture. Shipping this as an Xposed
module deployed with LSPatch would give rootless installation but not a side by side install:
renaming the package was requested in LSPosed/LSPatch issue 179 and closed by the maintainer as
too difficult. A rootless clone needs a Morphe pass regardless, which is why this stayed a pure
Morphe bundle.

## Verified against Instagram 440.1.0.46.86

Everything below was run against a real APK (versionCode 384611456, arm64-v8a, 20 dex, 132 MB)
and a real patcher, not reasoned about.

### Every string and type anchor survived; the two literals did not

Scanning the raw dex is enough for a presence check and takes seconds, because DEX stores string
constants contiguously in the string table and a `const-wide` payload is eight little endian
bytes in the instruction stream. No decompilation needed.

All three string anchors and all six type anchors were found. `MediaOption$Option`, `ImageUrl`,
`VideoVersionIntf`, `UserSession`, `IgImageView` and `InstagramAppShell` are all still
unobfuscated in 440.

Both MobileConfig ids taken from Instagram 436 were absent. Scanning for the category prefix
instead of the exact ids found the category alive with two different members, which is what
`tools/verify_anchors.py` now checks for directly.

| Flag | Instagram 436 | Instagram 440 |
|---|---|---|
| Can this media be downloaded | `0x81035f00020d71` | `0x81035f00020d62` |
| Is the viewer restricted | `0x81035f00060d73` | `0x81035f00030d63` |

### 440 consolidated every boolean flag read into one call

The standalone gate methods shaped `(UserSession, Media) -> boolean` are gone. Both ids now sit
in one generated dispatcher, `X.0Buu.GIO(LX/00A1;)Ljava/lang/Object;`, which switches on a
discriminator field, loads the relevant id, and falls through to a single shared tail:

```
invoke-interface {v2, v5, v0, v1}, Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;.BTq:(LX/00A1;J)Z
```

That is a better patch target than the gates ever were: it is where every boolean flag on any
version is answered, and `MobileConfigUnsafeContext` is a Facebook type Instagram does not
obfuscate. The interface itself is abstract, so the fingerprint matches the class implementing
it, which resolved to `X.09Qa.BTq`.

Hooking something that hot is only acceptable because the override returns null for every id it
does not recognise, so nothing but the two named ids can be affected.

### The bug that cost the most: injected calls cannot use high registers

Every patch failed at first with `NoSuchElementException: Collection is empty` out of the inline
smali compiler, which is about as unhelpful as an error gets. The cause is not Instagram:

`invoke-static` assembles to format 35c, whose register fields are four bits wide, so it cannot
address anything above v15. Parameter registers live at the TOP of the frame, so in
`InstagramAppShell.onCreate`, which declares twenty registers with one incoming argument, `p0` is
v19. The assembler rejects the instruction, the synthetic method it was compiling ends up with no
instructions, and the compiler then throws on `classDef.methods.first()`.

`invoke-static/range` is format 3rc with a sixteen bit base register and has no such limit, so
every injected call in this bundle uses it. The tradeoff is that a range must be contiguous and
ascending, which is why `MediaOptions.onOptionClick` takes the handler before the option: on an
instance method those are exactly p0 and p1.

This is not an Instagram specific hazard. It applies to any injection into any method with more
than sixteen registers, which for this app is most of them.

### Where each injection actually landed

Confirmed by disassembling the patched APK, not by trusting the patcher's exit code.

| Extension call | Injected into |
|---|---|
| `InstaSave.setApplication` | `com.instagram.app.InstagramAppShell.onCreate:()V` |
| `ImageViewRegistry.onImageUrlBound` | `IgImageView.setTrackingUrl:(ImageUrl;LX/074e;)V` |
| `StoryOptions.rememberOwner` / `addOption` | `X.0Vl7.A0h:(LX/0Vl7;)[Ljava/lang/CharSequence;` |
| `StoryOptions.onOptionClick` | `X.0Vl7.A0B:(...;Ljava/lang/CharSequence;)V` |
| `MediaOptions.addDownloadOption` | `X.0Tnx.A01:(Z)Ljava/util/List;` |
| `MediaOptions.addDownloadOptionToArrayList` | `X.020y.A04:(LX/01c2;Lcom/instagram/feed/media/Media;)Ljava/util/ArrayList;` |
| `MediaOptions.onOptionClick` | `X.0VGy.A08:(Lcom/instagram/feed/media/mediaoption/MediaOption$Option;)V` |
| `MobileConfigOverrides.evaluate` | `X.09Qa.BTq:(LX/00A1;J)Z` |

Two things worth noting. Both story hooks landed in the same class, which is the correlation you
want to see, since a sheet builder and its tap handler belong to one controller. And the option
tap handler turned out to be static, so the one argument overload was selected automatically.

### Patch the bundle, not the base APK

Instagram ships from Play as an app bundle. Extracting `com.instagram.android.apk` out of the
XAPK and patching that produces a valid, signed APK that will not install:
`INSTALL_FAILED_MISSING_SPLIT`, because the base declares that splits are required. Feed the
whole `.xapk` to the patcher instead and it merges the density splits into one universal APK.

## Still open

- Save actions are not exercised end to end. Everything up to the tap is verified: the patches
  apply, ART accepts the bytecode, the app runs, and the extension logs from inside the process.
  Actually tapping a save entry needs a logged in account on the test device, which nothing here
  has, and per the earlier decision in this file no account is being used from an instrumented
  environment.
- Carousel slide selection. `MediaUrlResolver.resolve` accepts an index and ranks candidates so
  the visible slide can be chosen, and that path is unit tested, but nothing supplies the index
  yet, so a multi image post saves its largest slide instead. InstaEclipse resolves it
  structurally, by finding the unique field on the controller whose class holds exactly one
  `int`.
- The two MobileConfig ids are the only version specific values in the bundle, and both moved
  once already. `tools/verify_anchors.py` checks them, so the failure mode is a clear report
  rather than a silently inert patch, but a new Instagram release can still require looking up
  the replacements in category `0x81035F`.
