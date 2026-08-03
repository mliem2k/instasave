#!/usr/bin/env bash
#
# Build a patched InstaSave APK from an Instagram release, end to end.
#
#   tools/build_apk.sh ~/Downloads/instagram.xapk
#   tools/build_apk.sh instagram.xapk out.apk "Save posts and reels (fallback)"
#
# Give it the BUNDLE Instagram actually ships, not a base APK extracted from one.
# Patching a lone base.apk succeeds and produces a signed file that then refuses to
# install with INSTALL_FAILED_MISSING_SPLIT, because the base declares that splits are
# required. Handed the whole .xapk/.apkm/.apks, the patcher merges the splits first.
#
# Two JDKs are involved and they are not interchangeable: the Gradle build targets 17,
# and the Morphe desktop CLI is compiled for 21. Both are located automatically.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$REPO_ROOT/tools/.work"
MORPHE_REPO="MorpheApp/morphe-desktop"

# Patches enabled by default. "Save posts and reels (fallback)" is deliberately absent:
# it duplicates the row that "Unlock native download" reveals. Pass it as $3 to add it.
DEFAULT_PATCHES=(
    "Bypass signature check"
    "Clone"
    "Unlock native download"
    "Save stories"
    "Long press to save images"
)

die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

[ $# -ge 1 ] || die "usage: $(basename "$0") <instagram.xapk|.apk> [output.apk] [extra patch name]..."

INPUT="$1"; shift
[ -f "$INPUT" ] || die "no such file: $INPUT"
INPUT="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"

OUTPUT="${1:-$REPO_ROOT/instasave.apk}"
[ $# -gt 0 ] && shift || true
EXTRA_PATCHES=("$@")

case "$INPUT" in
    *.apk) printf 'warning: %s looks like a bare APK. If it came out of a bundle the result\n' "$(basename "$INPUT")"
           printf '         will fail to install with INSTALL_FAILED_MISSING_SPLIT. Prefer the .xapk.\n' ;;
esac

# region toolchains

jdk_major() {
    "$1/bin/java" -version 2>&1 | head -1 | sed -nE 's/.*version "([0-9]+).*/\1/p'
}

find_jdk() {
    # $1 is the major version wanted. Every candidate is confirmed by asking the binary
    # itself, because `java_home -v 21` does loose matching: with no JDK 21 installed it
    # hands back the default JVM rather than failing, so trusting it silently builds
    # against the wrong runtime and fails much later with UnsupportedClassVersionError.
    local want="$1" candidate
    for candidate in \
        "$(/usr/libexec/java_home -v "$want" 2>/dev/null || true)" \
        "/opt/homebrew/opt/openjdk@$want" \
        "/usr/local/opt/openjdk@$want" \
        "/usr/lib/jvm/java-$want-openjdk"
    do
        [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] || continue
        [ "$(jdk_major "$candidate")" = "$want" ] && { printf '%s' "$candidate"; return 0; }
    done
    return 1
}

step "Locating toolchains"
JDK17="$(find_jdk 17)" || die "JDK 17 not found; it is required to build the patches"
JDK21="$(find_jdk 21)" || die "JDK 21 not found; it is required to run the Morphe CLI"
printf '    JDK 17: %s\n    JDK 21: %s\n' "$JDK17" "$JDK21"

if [ -z "${ANDROID_HOME:-}" ]; then
    for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [ -d "$candidate" ] && export ANDROID_HOME="$candidate" && break
    done
fi
[ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set and no SDK was found; the extension is an Android module"
printf '    Android SDK: %s\n' "$ANDROID_HOME"

# GitHub Packages needs authentication even for public packages, and the token has to
# carry read:packages. The default token from `gh auth login` does not.
if [ -z "${GITHUB_TOKEN:-}" ] && command -v gh >/dev/null 2>&1; then
    export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user --jq .login 2>/dev/null || true)}"
    export GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
fi
[ -n "${GITHUB_TOKEN:-}" ] || die "set GITHUB_TOKEN (needs the read:packages scope) or run: gh auth refresh --hostname github.com -s read:packages"

# endregion

step "Building the patch bundle"
( cd "$REPO_ROOT" && JAVA_HOME="$JDK17" ./gradlew :patches:build --console=plain -q )

MPP="$(ls -t "$REPO_ROOT"/patches/build/libs/patches-*.mpp 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1)"
[ -n "$MPP" ] || die "the build produced no .mpp bundle"
printf '    %s\n' "$MPP"

step "Fetching the Morphe desktop CLI"
mkdir -p "$WORK_DIR"
CLI="$(ls -t "$WORK_DIR"/morphe-desktop-*-all.jar 2>/dev/null | head -1 || true)"
if [ -z "$CLI" ]; then
    command -v gh >/dev/null 2>&1 || die "gh is needed to download the CLI; or drop morphe-desktop-*-all.jar into $WORK_DIR yourself"
    gh release download --repo "$MORPHE_REPO" --pattern '*-all.jar' --dir "$WORK_DIR" --clobber
    CLI="$(ls -t "$WORK_DIR"/morphe-desktop-*-all.jar | head -1)"
fi
printf '    %s\n' "$CLI"

ENABLE_ARGS=()
for patch in "${DEFAULT_PATCHES[@]}" ${EXTRA_PATCHES[@]+"${EXTRA_PATCHES[@]}"}; do
    ENABLE_ARGS+=(-e "$patch")
    printf '    enable: %s\n' "$patch"
done

# Sign with a stable key so the in-app updater works. Morphe otherwise mints a fresh
# ephemeral key per build, and Android rejects an update whose signature does not match
# the installed app, so two builds off the same source would refuse to update each other.
# The keystore lives outside the repo (it is a signing key) and is created by
# tools/setup_keystore.sh. Without it the build still works, just unsigned for updates.
SIGN_ARGS=()
KEYSTORE="${INSTASAVE_KEYSTORE:-$HOME/.instasave/instasave-release.keystore}"
KEYSTORE_PASS="${INSTASAVE_KEYSTORE_PASS:-instasave}"
KEYSTORE_ALIAS="${INSTASAVE_KEYSTORE_ALIAS:-instasave}"
if [ -f "$KEYSTORE" ]; then
    SIGN_ARGS=(
        --keystore "$KEYSTORE"
        --keystore-password "$KEYSTORE_PASS"
        --keystore-entry-alias "$KEYSTORE_ALIAS"
        --keystore-entry-password "$KEYSTORE_PASS"
    )
    printf '    signing with stable key: %s (alias %s)\n' "$KEYSTORE" "$KEYSTORE_ALIAS"
else
    printf '    warning: %s not found, using an ephemeral key.\n' "$KEYSTORE"
    printf '             In-app updates will not work across builds. Run tools/setup_keystore.sh.\n'
fi

step "Patching"
# --continue-on-error reports every fingerprint that failed in one run rather than
# stopping at the first, which is what you want when trying a new Instagram version.
# -f skips the version compatibility check, since compatibility here is deliberately
# declared as "any version".
# The +"${...}" guard is required: SIGN_ARGS is empty on the no-keystore path, and under
# `set -u` an empty-array [@] expansion is an "unbound variable" error on bash 3.2 (macOS's
# default), which would abort the very fallback the script documents as supported.
JAVA_HOME="$JDK21" "$JDK21/bin/java" -jar "$CLI" patch \
    -p "$MPP" \
    --exclusive --continue-on-error -f \
    ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"} \
    "${ENABLE_ARGS[@]}" \
    -o "$OUTPUT" \
    -t "$WORK_DIR/patching" \
    "$INPUT"

[ -f "$OUTPUT" ] || die "the patcher reported success but produced no file at $OUTPUT"

step "Result"
printf '    %s (%s)\n' "$OUTPUT" "$(du -h "$OUTPUT" | cut -f1)"
if [ -x "$ANDROID_HOME/build-tools" ]; then :; fi
AAPT="$(ls -t "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | head -1 || true)"
if [ -n "$AAPT" ]; then
    "$AAPT" dump badging "$OUTPUT" 2>/dev/null | grep -E "^package:|^application-label:" | sed 's/^/    /'
fi

cat <<'NEXT'

Next:
    adb install -r <the apk above>

If a fingerprint failed above, run tools/verify_anchors.py against the same input to
see which anchors moved and what the enclosing methods are now.
NEXT
