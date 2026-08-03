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

One command does everything: build the bundle, fetch the patcher, patch, sign.

```sh
tools/build_apk.sh ~/Downloads/instagram.xapk
```

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
- [x] Builds. `./gradlew :patches:build` produces `patches/build/libs/patches-0.1.0.mpp` with the
      extension dex bundled at `extensions/instasave.mpe`.
- [x] **All six patches apply cleanly to Instagram 440.1.0.46.86**, with zero patch exceptions,
      and each injection was confirmed by disassembling the output rather than trusting the exit
      code. See `RESEARCH.md` for the table of exactly which method each one landed in.
- [x] **Installs and runs.** The patched build installs as a separate app alongside Instagram,
      ART verifies the injected bytecode, the app launches to the real login screen, and the
      extension logs `InstaSave: context captured: com.instagram.android.instasave` from inside
      the running process, so the injected code is confirmed executing and not merely present.
- [ ] Save actions not yet exercised end to end. That needs a logged in account on the test
      device, which nothing here has. Everything up to the point of tapping is verified.

Verified against Instagram 440.1.0.46.86 (versionCode 384611456) on an Android 16 (API 36)
arm64 emulator.

This repo contains no Instagram APK and no decompiled output. Neither is redistributable.

## License

GPLv3, see [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
