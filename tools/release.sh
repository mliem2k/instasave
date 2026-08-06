#!/usr/bin/env bash
#
# Publishes the GitHub release for the version currently in gradle.properties.
#
# Every inconsistency this repo's releases have accumulated came from typing the publish by hand:
# three releases titled "v0.2.9" instead of "InstaSave 0.2.9", and a stray draft left behind when
# a single create-with-all-assets call timed out partway through uploading a 250MB APK.
#
# It also fixes the window that broke the in-app updater. A release is visible to the releases API
# the moment it is published, but an asset is only fetchable once its upload completes, so
# publishing first and uploading the APK afterwards leaves minutes where the updater sees a new
# release whose download 404s. Everything goes into a draft here, and the release is published only
# once every asset is up.
#
# Assumes tools/build_apk.sh has already produced the APK and that the bundle has been copied in.
# Does not touch git: commit and push separately.
#
# Usage: tools/release.sh

set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^version *= *//p' gradle.properties | tr -d '[:space:]')
[ -n "$version" ] || { echo "error: no version in gradle.properties" >&2; exit 1; }
tag="v$version"

mpp="release/instasave_patches_${version}.mpp"
notes="release/notes.md"
sums="release/sha256sums.txt"

shopt -s nullglob
apks=(release/instasave_"${version}"_instagram_*.apk)
shopt -u nullglob
if [ ${#apks[@]} -ne 1 ]; then
    echo "error: expected exactly one release/instasave_${version}_instagram_*.apk, found ${#apks[@]}" >&2
    exit 1
fi
apk="${apks[0]}"

for f in "$apk" "$mpp" "$notes"; do
    [ -f "$f" ] || { echo "error: missing $f" >&2; exit 1; }
done

# Stale artifacts from an earlier version in release/ would be uploaded or checksummed by mistake.
shopt -s nullglob
strays=()
for f in release/instasave_*; do
    case "$f" in
        "$apk"|"$mpp") ;;
        *) strays+=("$f") ;;
    esac
done
shopt -u nullglob
if [ ${#strays[@]} -gt 0 ]; then
    echo "error: release/ holds artifacts from another version, remove them first:" >&2
    printf '  %s\n' "${strays[@]}" >&2
    exit 1
fi

# The note headings have been identical since 0.2.5. Checking them here is what keeps them that
# way, since the notes are written from scratch each time (release/ is gitignored).
missing=()
while IFS= read -r heading; do
    grep -qxF "$heading" "$notes" || missing+=("$heading")
done <<EOF
## Changed in $version
## Assets
## Every patch, all on by default
## Verified
## Not verified
## Still on 0.1.0?
EOF
if [ ${#missing[@]} -gt 0 ]; then
    echo "error: $notes is missing the standard headings:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    exit 1
fi

if gh release view "$tag" >/dev/null 2>&1; then
    echo "error: release $tag already exists" >&2
    exit 1
fi

( cd release && shasum -a 256 "$(basename "$apk")" "$(basename "$mpp")" > "$(basename "$sums")" )

echo "==> creating draft $tag"
gh release create "$tag" --draft --title "InstaSave $version" --notes-file "$notes" "$mpp" "$sums"

echo "==> uploading $(basename "$apk")"
gh release upload "$tag" "$apk"

echo "==> publishing"
gh release edit "$tag" --draft=false

echo "==> verifying"
gh release view "$tag" --json isDraft,assets \
    --jq '"draft=\(.isDraft)", (.assets[] | "\(.name)  \(.size)  \(.state)  \(.digest)")'
echo "--- local ---"
cat "$sums"
