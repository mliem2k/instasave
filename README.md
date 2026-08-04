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
flow. It is not missing, it is gated behind two boolean methods: "can this media be downloaded"
and "is the viewer restricted from downloading". Both answer against you for someone else's post.

`unlockNativeDownloadPatch` flips those two and stops. No row is constructed, no URL is resolved,
no bytes are written by us. Each gate is located by the MobileConfig parameter id compiled into
it as a literal, which is a stable identity rather than an obfuscated name.

This is the fix the earlier `forceShowChannelsSaveButtonPatch` was reaching for, at the right
layer. `RESEARCH.md` records why the MobileConfig route was a dead end: a retail APK carries no
id to name mapping, so flags cannot be discovered by decompiling. These gates need no mapping,
because the literal is the thing being matched.

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
URL, one seam covering every image the app renders. That yields a long press handler on views
nothing else claims (`isLongClickable()` is false), plus a short history of recently bound URLs
that the resolver falls back to when a tap handler captured nothing reachable.

### 4. Save posts and reels, fallback (default **off**)

For builds where the native row does not appear. Instagram models the row as a constant of the
unobfuscated enum `MediaOption$Option` and filters it out of an allowlist, so this appends a
value to a list rather than building a row, and handles the tap with InstaSave's own downloader.
Off by default because enabling it together with patch 1 would show two entries.

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

### Settings screen (default on)

A patched Instagram has no place to add a settings row without fingerprinting its own obfuscated
UI, so InstaSave's settings are a standalone Activity with their own launcher icon. The screen
holds the update controls (an automatic-check toggle, a check-now button, the installed version)
and the video quality choice. It is built in code, since a merged extension ships no layout
resources, and declared by a manifest resource patch.

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

Start with `Unlock native download`. Only enable `Save posts and reels (fallback)` if the
download row does not appear, since together they would produce two entries.

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
- [x] Settings screen with its own launcher icon: update controls and the video quality choice.
- [x] Highest resolution video: the largest variant is saved, ranked by obfuscated dimension
      accessors.
- [x] Disable double tap to like (on by default), told apart from the heart button by the call
      stack.
- [x] Block ads (on by default): removes sponsored posts and the impression tracking tied to
      showing one.
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
