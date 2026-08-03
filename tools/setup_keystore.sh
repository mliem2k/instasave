#!/usr/bin/env bash
#
# Create the stable signing key InstaSave builds use.
#
# Why this exists: the in-app updater installs a new APK over the running one, and Android
# only accepts that as an update when the new APK carries the SAME signature. Morphe otherwise
# generates a throwaway key on every build, so two builds off identical source would refuse to
# update each other with a signature mismatch. This key is generated once and reused, which is
# what gives every InstaSave build one stable identity.
#
# The keystore is deliberately kept OUTSIDE the repo. It is a signing key: committing it would
# let anyone sign an APK that installs as an update over yours. It lives in ~/.instasave by
# default; back it up somewhere private, because losing it means no future build can update an
# already installed one (the user has to uninstall and reinstall).
#
# This is a personal, single user signing key with a memorable password, which is the right
# trade for a self-distributed mod. It is not a Play Store upload key and makes no such claim.
#
set -euo pipefail

KEYSTORE="${INSTASAVE_KEYSTORE:-$HOME/.instasave/instasave-release.keystore}"
PASS="${INSTASAVE_KEYSTORE_PASS:-instasave}"
ALIAS="${INSTASAVE_KEYSTORE_ALIAS:-instasave}"

find_jdk() {
    local want="$1" candidate
    for candidate in \
        "$(/usr/libexec/java_home -v "$want" 2>/dev/null || true)" \
        "/opt/homebrew/opt/openjdk@$want" "/usr/local/opt/openjdk@$want" \
        "/usr/lib/jvm/java-$want-openjdk"
    do
        [ -n "$candidate" ] && [ -x "$candidate/bin/keytool" ] || continue
        printf '%s' "$candidate"; return 0
    done
    return 1
}

JDK="$(find_jdk 21 || find_jdk 17)" || { echo "no JDK with keytool found" >&2; exit 1; }
KEYTOOL="$JDK/bin/keytool"

if [ -f "$KEYSTORE" ]; then
    echo "Keystore already exists: $KEYSTORE"
    echo "Leaving it untouched. Delete it by hand first if you really mean to replace it,"
    echo "but note that will orphan every already installed build."
else
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" \
        -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 9999 \
        -dname "CN=InstaSave, OU=personal, O=mliem2k, L=, ST=, C="
    echo
    echo "Created $KEYSTORE"
fi

echo
echo "Signature identity every InstaSave build must share:"
"$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$PASS" -alias "$ALIAS" 2>/dev/null \
    | grep -iE 'SHA256:|Valid from' | sed 's/^/    /'
