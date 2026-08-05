# InstaSave

A personal [Morphe](https://morphe.software) patch bundle for Instagram that:

1. **Clones** the app under a different package name so it installs alongside the official
   Instagram app (`clonePatch`, package `com.instagram.android.instasave`, label `InstaSave`).
2. **Adds a save action** to posts, reels, stories and profile pictures.

Rootless: the output is an ordinary APK. No Xposed, no LSPosed, no Magisk, no manager app.

This is a personal project, not a redistribution ready mod. It depends on Morphe's published
patcher libraries rather than vendoring them; see [NOTICE](./NOTICE) for attribution, including
to [InstaEclipse](https://github.com/ReSo7200/InstaEclipse), whose published research located
several of the seams used here.

## Why Morphe and not LSPatch

The obvious rootless route is an Xposed module deployed with [LSPatch](https://lspatch.org/),
which is how InstaEclipse ships. It cannot satisfy the clone requirement: renaming the package
was requested in [LSPosed/LSPatch#179](https://github.com/LSPosed/LSPatch/issues/179) and closed
by the maintainer as too difficult. A rootless clone therefore needs a Morphe pass regardless,
and doing both means two toolchains, two signing steps, and a forked module whose package check
has to be taught the new name, for no capability gained.

Morphe covers all three requirements on its own, and its fingerprints are the patch time
equivalent of the runtime dex search an Xposed module would do:

| Requirement | Mechanism |
|---|---|
| Rootless | The patcher emits a normal installable APK |
| Clone | `clonePatch`, from `instagram-morphe-patches-library` |
| Survives resigning | `bypassSignatureCheckPatch`, same library |
| Cheap version rebases | Fingerprints matched on literals and unobfuscated type names |

Resolving at patch time also fails better. A fingerprint that stops matching fails the build and
names itself. The runtime equivalent fails silently and the feature simply does not appear.

## How the save actions work

Four patches, deliberately layered so the cheapest and most durable one carries most of the load.

### 1. Unlock native download (default on, no extension code)

Instagram already ships a complete DOWNLOAD row for reels and, since the post and reel overflow
menus were merged onto one row builder, for feed posts too, wired to its own save to camera roll
flow. It is not missing, it is gated behind two boolean MobileConfig methods reached by every
caller, plus a second, independent eligibility gate that decides per media item whether DOWNLOAD
is even a candidate before either of those flags is consulted at all.

`unlockNativeDownloadPatch` forces all of it. The two MobileConfig gates are answered by the
single method every boolean flag read passes through in this build, located by the parameter id
compiled into it as a literal, which is a stable identity rather than an obfuscated name. The
eligibility gate is a separate pair of methods, shared by the feed and carousel candidate list
builder and by the reel menu's own row builder, and forced to answer eligible and not restricted
directly rather than by satisfying every condition that leads to it: an organic, non remix clip
check, a media type comparison, a kill switch, and an account level bypass, none of which is a
MobileConfig id at all. Forcing the flags alone left a carousel or a reel that failed any one of
those still ineligible; hooking the eligibility gate itself is what actually reaches every caller
uniformly. No row is constructed, no URL is resolved, no bytes are written by us.

This is the fix the earlier `forceShowChannelsSaveButtonPatch` was reaching for, at the right
layer. `RESEARCH.md` records why the MobileConfig route was a dead end: a retail APK carries no
id to name mapping, so flags cannot be discovered by decompiling. These gates need no mapping,
because the literal, and the eligibility check's own internal trace label, are the things being
matched.

### 2. Save stories (default on)

Stories are outside the media option system, so there is no existing row to unhide. Instagram
builds that sheet as a plain `CharSequence[]` and dispatches taps by comparing the tapped label,
so adding an entry is an array copy and intercepting it is a string comparison.

The object the media is resolved from is captured when the sheet is **built**, not when it is
tapped. The builder is an instance method on the controller that owns the current reel, so `this`
there is dependable; the tap handler is usually a synthetic lambda whose captured state moves
between releases. Reaching into that lambda is the fragile part of doing this at runtime, and
bytecode injection can simply capture at the better of the two call sites instead.

### 3. Long press to save images (default on)

Profile pictures have no menu to add to. Instagram's own image view is patched where it binds a
URL, one seam covering every image the app renders. That yields a short history of recently bound
URLs that the resolver falls back to when a tap handler captured nothing reachable, a save gesture
on every one of those images, and the view each URL belongs to, which is what the on post save
button below is anchored to.

This seam was pointed at the wrong method until 0.2.11, and it is worth recording how, because
nothing about it looked wrong. The fingerprint asked for a void method on `IgImageView` taking
`(ImageUrl, L)`, which reads exactly like the URL setter. On 440 the only method with that shape
is `setTrackingUrl`, an analytics setter with two call sites in the whole app, both inside one
unrelated class. So the fingerprint resolved, the patch reported applied, the build was signed,
and the hook never once ran on a feed image. Everything downstream was dead: long press recorded
no URL for any view and so silently did nothing, and the save button had no bound view to attach
to and so never appeared. Three releases went out fixing symptoms of it.

The real path is `setUrl`, with 339 call sites, into the private `setUrlInternal`, which every
other entry funnels into as well, including the Vito backed loader it delegates to internally. The
fingerprint now identifies that by shape (seven parameters, `ImageUrl` second, unique on the class)
rather than by a name obfuscation is free to take. The general lesson, now enforced by habit: a
fingerprint that resolves is not a fingerprint that is correct, so count the call sites of whatever
it matched before trusting it, and confirm the injection landed where intended by disassembling the
patched APK rather than by reading the patcher's own success line.

That gesture used to skip any view where `isLongClickable()` was already true, on the reasoning
that such a view (post previews, reorder handles in the composer) had its own long press wired
through `setOnLongClickListener` and had a menu for saving anyway. That premise stopped holding
once patch 1's own menu turned out not to be reliably reachable on every build: a feed post or a
carousel slide almost always reports `isLongClickable() == true` for reasons that have nothing to
do with saving, so it was left with no save action at all. Rather than replace
`setOnLongClickListener` and risk breaking whatever claimed it, this now runs its own long press
timer through a plain `OnTouchListener` that always returns false, so it never consumes the event
and Instagram's own touch handling on that view, including whatever else registered a long click,
keeps working unmodified alongside it.

### 4. Save posts and reels, fallback (default on)

Originally written on the theory that patch 1's flags alone could not reach a multi image post or
a reel; tracing the actual eligibility gate showed that was only half right, and patch 1 now hooks
that gate directly. This patch remains as a second, independent layer: it appends the download
entry to the same allowlist Instagram renders from, wherever it finds it missing, in case some
other, still untraced path excludes it from that allowlist even once patch 1 has made it a genuine
candidate.

Running alongside patch 1 is deliberate, not a duplicate risk. The injected code only appends when
the list does not already contain the entry, and `MediaOption$Option.DOWNLOAD` is a single enum
instance with default, reference equality `equals()`, so a plain `List.contains` correctly detects
Instagram's own entry when patch 1 already put it there. Where the native row already appears,
this is a no-op; where it does not, this is what actually gives the post a download option.

Known gap: which candidate is saved for a multi image post is not yet aware of which slide is on
screen; it currently saves the highest resolution candidate found, not necessarily the visible
one. Locating the carousel position accessor is unresearched.

### Save button on the post (default on, no bytecode patch)

Instagram's own overflow menu is not a fixed target: it has changed shape once already in the time
this project has existed, and patches 1 and 4 above only reach whatever is currently baked into the
menu builder's own bytecode. Whatever Instagram does with that menu next, this does not depend on
it at all. A small dark circular button sits in the bottom right corner of the post's own image,
and tapping it saves that post.

It is a real child of the post's own container, not an overlay pinned to the screen. An earlier
version was a button fixed to the corner of the window, which was rejected as bad design and
deserved to be: a control that acts on one specific post belongs on that post, not floating
somewhere generic and inferring which post it means. Being a real child also makes it behave
correctly for free. It scrolls in exact sync with the post because it moves with its parent, it is
clipped by the same bounds as everything else in the post rather than floating over the top bar on
the way out of view, and it costs nothing per frame.

The risk that has to be answered is layout damage, so the container is chosen rather than assumed.
Adding a child to a stacking container (a vertical `LinearLayout`) would not overlay the image at
all, it would insert a whole extra row and visibly break the feed; handing the button to a recycler
or a pager would give it to something that owns its children's positions and lifecycles. So the
walk up from the image accepts only containers that position children freely and independently
(`FrameLayout`, `RelativeLayout`, and the constraint and coordinator layouts matched by name, with
scrollers rejected first since `ScrollView` is itself a `FrameLayout`), and stops rather than
settling for anything else. Such an ancestor is close to guaranteed in practice, because Instagram
already draws its own things over post images (carousel dots, product pills, audio attribution), so
the container holding the image has to support exactly this already.

Positioned by translation rather than by layout params, so it never takes part in the host's
measure pass and cannot move anything else. Both views live inside the same container, so that
offset does not change while scrolling, which is the whole reason a child needs no per frame
tracking. Only the one structural change, adding the button, is posted out of the layout pass that
triggers it, since calling `addView` on a host that is already laying out is what provokes a nested
layout request. Images below 150dp in either dimension are skipped, which keeps buttons off avatars,
story rings, grid tiles and icons, and keeps a 34dp button from growing a small wrap content
container that holds something smaller than the button itself.

Needs no new bytecode patch at all: it is driven entirely by the URL and view tracking
`imageLongPressDownloadPatch` already installs, and it knows exactly which view it belongs to, so
the post it is sitting on and the post it saves are the same by construction rather than by
inference.

Two more patches ship alongside the save actions:

### Disable screenshot detection (default on)

Stops Instagram reporting when you screenshot someone's story, reel or disappearing message. The
observer callback that fires the report is neutralized with a `return-void`. This is separate from
the save actions, which never trigger detection anyway because they download from the CDN and
never capture the screen; the patch covers a manual screenshot too.

### In-app updater (default on)

Checks GitHub Releases about once a day and, when a newer build exists, posts a notification with
a "Download and install" action driven by the system `PackageInstaller`. It rests on every build
sharing one signing key (see Building), because Android rejects an update whose signature differs
from the installed app. It is silent when the releases are not publicly reachable.

The download itself, whether started from that notification or from "Check for updates now", runs
an ongoing progress notification the whole time rather than a single toast and then silence, since
the APK is large enough that a bare "downloading" message with nothing after it reads as stuck.
Determinate whenever GitHub sends a content length, which it always does for a release asset, and
falls back to an indeterminate bar with a running byte count otherwise. Switches to "installing"
once the bytes are down, and is cancelled the moment the install finishes, fails, or hands off to
the system's own confirmation screen.

### Settings screen (default on)

A patched Instagram has no place to add a settings row without fingerprinting its own obfuscated
UI, so InstaSave's settings are a standalone Activity with their own launcher icon. It is built in
code, since a merged extension ships no layout resources, declared by a manifest resource patch,
and follows the dark system theme (`Theme.DeviceDefault`) rather than Instagram's own styling.

Every toggle changes only the on screen switch; nothing is written until the Save button, fixed at
the bottom of the screen, is tapped. Toggling writes on every flip gave no feedback that anything
had happened, which is exactly what read as "the setting isn't applied". One Save action, with a
toast confirming it, gives a single unambiguous moment of "this took". "Check for updates now" is
an action rather than a preference and still runs immediately, since there is nothing for it to
save.

Styled to sit next to Instagram's own dark mode rather than look like a bolted on utility screen:
a true black background, a back arrow and bold title in place of a floating heading, thin dividers
instead of boxed cards, and Instagram blue on the switches and the Save button.

Declared with its own `taskAffinity` and `singleTask` launch mode, in its own task, separate from
Instagram's. Without that, both launcher icons fall back to the same implicit, package wide task
affinity, and switching between them just resumes whichever task already existed instead of the
one actually tapped, stuck on the other screen until the app was force stopped.

### Highest resolution video (always on)

A video post carries several encodings; the resolver saves the largest. `VideoVersionIntf` has no
`getWidth`/`getHeight`, only obfuscated `Integer` accessors, so the pick reads every zero-arg
`Integer` accessor and takes the product of the two largest, which is the pixel area whichever
accessor is which. The settings screen can flip this to the smallest variant as a data saver.

### Disable double tap to like (default on, settings screen)

Instagram routes both a double tap and the heart button through the same like method, so this
cannot simply neutralize that method or the heart button would stop working too. The patched
method asks the extension at entry whether the current call came from a double tap, told apart by
the gesture callback names in the call stack, and returns without liking only then.

### Block ads (default on, settings screen)

Removes sponsored posts from feeds and reels. One method decides whether a sponsored item is
inserted; the patch consults the setting at its entry and, when on, forces it to answer that the
item does not qualify. Nothing is inserted, so nothing renders, and the impression tracking that
goes with rendering an ad never fires either. Scope, stated plainly: this is ad insertion and the
tracking tied to it, not Instagram's general telemetry, which has no comparable single choke point
in this app for a bytecode patch to neutralize wholesale.

## The video fix, and why saving used to give a still

Saving a video story, reel or post used to save a still frame. The cause is not obfuscation, it is
storage shape. Instagram keeps `video_versions` inside the native Pando/LiveTree and exposes it
only through a zero-argument accessor method on the media dictionary (`LiveTreeMediaDict.A9w()`),
never a plain field. The cover image, by contrast, IS a plain field on the media model. A
field-only graph walk therefore never materializes the video and always finds the cover, so it
saved the still.

The resolver now runs a second pass when the field walk found no video: it invokes the
zero-argument `List`-returning accessors on the media dictionary (matched by the unobfuscated
`MutableMediaDictIntf` name), which forces the native tree to materialize, and harvests any
element implementing `VideoVersionIntf`. It only touches the dictionary object, only calls
zero-arg `List` getters (pure tree reads), runs only on a tap, and only when no video was already
field-reachable. Posts and reels converge on the same media shape, so one fix covers all three.

## Resolving the media URL

`RESEARCH.md` documents three independent leads (`can_viewer_save`, `image_versions2`,
`video_versions`) that all bottomed out in Pando/LiveTree, Meta's compact binary tree
materialized through JNI, and concluded static tracing had hit a wall.

That wall is an artifact of reading smali, not a property of the app. By the time any UI callback
runs, the tree has already been materialized into ordinary Java objects. `MediaUrlResolver` walks
the live graph from whatever object the patched call site handed it, bounded to six levels and
6000 objects, and matches on what obfuscation does not touch:

* objects implementing `VideoVersionIntf`, whose `getUrl()` gives the video,
* objects implementing `ImageUrl`, whose `getUrl()`/`getWidth()`/`getHeight()` give an image and
  its size, so the largest can be picked,
* any remaining string that looks like an Instagram CDN URL.

Videos outrank images, because a video post also carries a cover image and the cover is never
what someone asking to save a video wants. Not one field name appears anywhere in it.

Files land in `Download/InstaSave` through MediaStore on Android 10 and newer, which needs no
runtime permission. Older releases fall back to a direct write and do need storage permission.

## Verifying against a new Instagram release

Fingerprints match on things obfuscation leaves alone, so a new release is usually just a
rebuild. Usually is doing work in that sentence: when Instagram restructures a screen an anchor
really does move. Before building against a new version:

```sh
tools/verify_anchors.py path/to/instagram.apk
```

It reads the anchors straight out of `Fingerprints.kt`, so it cannot drift from the patches it
checks, decompiles the APK with apktool (cached under `tools/.work/`, gitignored), and reports
per fingerprint whether every anchor is present. For each hit it prints the enclosing method
signature, which is what a `Fingerprint` declaration needs. It exits non zero if anything is
missing. `--smali` skips apktool if you already have a decompiled tree; `--json` is machine
readable.

Nothing in `Constants.kt` is version pinned. The previous `versionCodes` map defeated exactly
this model by forcing an edit for releases that would have patched unchanged. Add a version there
as a non experimental target only once it is confirmed working on a device.

## Building

Once, create the stable signing key. Every build shares it, which is what lets the in-app updater
install a new version over an old one; Android rejects an update whose signature differs from the
installed app. The key lives outside the repo and never leaves your machine.

```sh
tools/setup_keystore.sh
```

Then, one command does everything: build the bundle, fetch the patcher, patch, sign.

```sh
tools/build_apk.sh ~/Downloads/instagram.xapk
```

The build still works without the keystore, just unsigned for updates, and it says so. Note that
builds signed with different keys cannot update each other, so a build made before the keystore
existed has to be reinstalled once to get onto the stable key.

Requirements it checks for you:

* **JDK 17 and JDK 21, both.** Not interchangeable: the Gradle build targets 17, the Morphe CLI
  is compiled for 21. The script locates each and confirms the version by asking the binary,
  because `java_home -v 21` does loose matching and will hand back a different JDK rather than
  failing, which surfaces much later as `UnsupportedClassVersionError`.
* **The Android SDK**, since the extension is an Android module.
* **A GitHub token carrying `read:packages`.** The Morphe plugin and both patch libraries live on
  GitHub Packages, which requires authentication even for public packages, and the token
  `gh auth login` creates by default does not carry that scope:

```sh
gh auth refresh --hostname github.com -s read:packages
```

To build only the bundle, without patching anything:

```sh
export GITHUB_ACTOR="$(gh api user --jq .login)" GITHUB_TOKEN="$(gh auth token)"
./gradlew :patches:build
```

`local.properties` points Gradle at the Android SDK and is gitignored; recreate it with
`sdk.dir=$HOME/Library/Android/sdk` if missing.

## Tests

```sh
./gradlew :extensions:instasave:test
```

Sixteen JVM tests, no device and no Robolectric, covering the parts with no other safety net:
the media graph walk (ranking, nesting, reference cycles, the package gate that stops it
reflecting over arbitrary objects), file naming, and the flag overrides. The graph walk is the
piece most likely to be quietly wrong, because it succeeds by finding something *plausible*, so
a bad ranking looks identical to working code until it saves the wrong image.

The fakes live under `com.instagram.*` on purpose. The resolver refuses to descend into anything
outside Instagram's own namespaces, so fakes declared elsewhere would be skipped and every test
would pass while exercising nothing.

## Layout

```
patches/     Kotlin. Fingerprints and the bytecode injections. Runs at build time.
extensions/  Java. Compiled to a dex merged into the APK. Runs inside Instagram.
             Unit tests live under src/test.
tools/       build_apk.sh      one command from an Instagram bundle to a signed APK
             setup_keystore.sh the stable signing key the updater needs
             verify_anchors.py the per release anchor check
```

## Patching an APK

Use the Morphe desktop CLI (`morphe-desktop-*-all.jar` from
[MorpheApp/morphe-desktop](https://github.com/MorpheApp/morphe-desktop) releases; it needs **JDK
21**, while building this repo needs JDK 17):

```sh
java -jar morphe-desktop-all.jar patch \
  -p patches/build/libs/patches-0.1.0.mpp \
  --exclusive -f \
  -e "Bypass signature check" -e "Clone" \
  -e "Unlock native download" -e "Save stories" -e "Long press to save images" \
  -o instasave.apk \
  instagram.xapk
```

**Feed it the bundle, not an extracted base APK.** Instagram ships from Play as an app bundle.
Patching the base APK alone succeeds and produces a signed file that then fails to install with
`INSTALL_FAILED_MISSING_SPLIT`, because the base declares splits are required. Handing the whole
`.xapk` to the patcher makes it merge the splits into one universal APK first.

Add `--continue-on-error` when trying a new Instagram version: patching then reports every
fingerprint that failed in one run instead of stopping at the first.

Enable `Unlock native download` and `Save posts and reels (fallback)` together; both are on by
default in `tools/build_apk.sh`. The fallback only appends its entry where one is not already
there, so running both never produces two.

## Status

- [x] Rootless clone with signature bypass
- [x] Native download row unlock for posts and reels
- [x] Story save entry
- [x] Long press to save images, including profile pictures
- [x] Fallback menu injection and standalone downloader
- [x] Per release anchor verification tool
- [x] The video fix: saving a video story, reel or post now resolves the real `.mp4` instead of
      the cover still. Unit tested against a fake that reproduces the native-accessor shape.
- [x] Disable screenshot detection.
- [x] In-app updater from GitHub Releases, with a stable signing key so updates install in place.
      The download runs an ongoing progress notification, determinate whenever the server sends a
      content length and an indeterminate bar with a running byte count otherwise, since a single
      toast and then silence on a large APK reads as stuck rather than working.
- [x] Settings screen with its own launcher icon, styled like Instagram's own dark mode, and an
      explicit Save button rather than writing on every toggle flip. Declared with its own
      taskAffinity and singleTask so switching between it and Instagram never gets stuck on
      whichever one was already open.
- [x] Highest resolution video: the largest variant is saved, ranked by obfuscated dimension
      accessors.
- [x] Disable double tap to like (on by default), told apart from the heart button by the call
      stack.
- [x] Block ads (on by default): removes sponsored posts and the impression tracking tied to
      showing one.
- [x] Multi image posts and reels get a download entry: the native unlock now hooks the shared
      eligibility gate that decides per item whether DOWNLOAD is a candidate at all, not just the
      two MobileConfig flags that gate it afterward. The fallback patch remains as a second,
      independent layer, guarded against duplicating an entry that is already there. Verified at
      the bytecode level (both fingerprints resolve, the injected return lands at each method's
      entry) and on device with no crash; not yet confirmed against a real logged in feed.
- [ ] Which carousel slide is saved is not yet aware of which one is on screen; the highest
      resolution candidate found is saved regardless of position.
- [x] Real world use after the eligibility gate fix above showed Instagram's own overflow menu is
      not a stable target: on a real account it no longer showed the classic Report/Share/Download
      list at all, replaced by different entries. Whatever the cause, chasing wherever that menu
      moves to next is not a durable fix on its own.
- [x] The image bind hook was pointed at the wrong method from the day it was written, which is
      why nothing that depended on it ever worked. The fingerprint asked for a void method on
      `IgImageView` taking `(ImageUrl, L)`, which reads like the URL setter and on 440 matches only
      `setTrackingUrl`, an analytics setter with two call sites in the entire app. It resolved, so
      the patch reported applied and the build was signed, and the hook then never ran on a single
      feed image: long press recorded no URL and silently did nothing, and the save button had no
      bound view to attach to and never appeared. Retargeted at `setUrlInternal`, the funnel every
      bind reaches (339 call sites through `setUrl` alone, plus the Vito loader it delegates to),
      identified by shape rather than by an obfuscatable name. Verified by disassembling the patched
      APK and confirming the injected instructions sit at the top of that exact method, which is the
      check whose absence let this survive three releases.
- [x] Save button on the post, a real child of the post's own container rather than a button pinned
      to a corner of the screen. Scrolls with the post, is clipped with it, costs nothing per frame,
      and saves the post it is sitting on by construction rather than by inferring which post is
      meant. The container is chosen, never assumed: only containers that position children freely
      are accepted, so a stacking layout can never receive it as an extra row, and scrollers, pagers
      and recyclers are rejected outright. Verified: builds clean, all eleven patches apply with
      zero exceptions, unit tests pass, and the injection site is confirmed in the disassembly. Not
      confirmed against a real logged in feed, which is the next real device test; this project does
      not use an emulator to verify, since emulator runs have never caught the bugs that mattered
      here.
- [x] Long press to save no longer skips feed posts and carousel slides. It used to skip any view
      Instagram had already made long clickable, on the assumption those surfaces had a menu for
      saving anyway; once that stopped being reliably true, it now runs its own independent long
      press timer through a plain `OnTouchListener` that never consumes the event, so Instagram's
      own touch handling on the same view, long click or otherwise, keeps working unmodified
      alongside it.
- [x] Builds. `./gradlew :patches:build` produces `patches/build/libs/patches-0.2.0.mpp` with the
      extension dex bundled at `extensions/instasave.mpe`. 23 JVM unit tests pass.
- [x] **All eight patches apply cleanly to Instagram 440.1.0.46.86**, with zero patch exceptions,
      and each injection was confirmed by disassembling the output rather than trusting the exit
      code, including the screenshot method now beginning `return-void` and the two `onCreate`
      injections.
- [x] **Installs and runs.** The patched build installs as a separate app alongside Instagram,
      ART verifies the injected bytecode, and the app launches to the real login screen. The
      extension logs its captured context from inside the process, and the updater fires at
      startup, reaches GitHub, and handles the response without crashing.
- [ ] Save actions not yet exercised end to end on a real logged-in video. That needs a logged in
      account on the test device, which nothing here has. Everything up to the tap is verified,
      and the video resolution is unit tested against a faithful fake.

Verified against Instagram 440.1.0.46.86 (versionCode 384611456) on an Android 16 (API 36)
arm64 emulator.

This repo contains no Instagram APK and no decompiled output. Neither is redistributable.

## License

GPLv3, see [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
