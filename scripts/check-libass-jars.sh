#!/usr/bin/env bash
#
# Fails unless the published libass jars carry every native library they promise (#776).
#
# The libass build only warns when it cannot link an adapter, unless KITEPLAYER_LIBASS_REQUIRE_ALL_HOST_JNI
# and KITEPLAYER_LIBASS_REQUIRE_ANDROID_JNI are true, so a jar with a missing half can be signed and
# uploaded while every other check stays green. This reads the jars that a publish to Maven Local
# left, and needs no build of its own:
#
#   kiteplayer-libass-android-natives   lib/<abi>/libkiteplayer_libass_jni.so for each Android ABI
#   kiteplayer-libass-jvm               kiteplayer-libass-native/<host>/<library> for each desktop host
#
# KITEPLAYER_POM_REPOSITORY names another local Maven repository than ~/.m2/repository.
#
#   ./scripts/check-libass-jars.sh               # after ./kotlin publish mavenLocal: must PASS
#   ./scripts/check-libass-jars.sh --falsify     # a jar with one library taken out: must FAIL
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

VERSION=$(sed -n 's/^    version:[[:space:]]*//p' library.module-template.yaml | tr -d '\r')
[ -n "$VERSION" ] || { echo "library.module-template.yaml has no publishing version" >&2; exit 2; }
REPOSITORY="${KITEPLAYER_POM_REPOSITORY:-$HOME/.m2/repository}/io/github/yuroyami"

NATIVES_JAR="$REPOSITORY/kiteplayer-libass-android-natives/$VERSION/kiteplayer-libass-android-natives-$VERSION.jar"
JVM_JAR="$REPOSITORY/kiteplayer-libass-jvm/$VERSION/kiteplayer-libass-jvm-$VERSION.jar"

ANDROID_ENTRIES=(
    lib/arm64-v8a/libkiteplayer_libass_jni.so
    lib/armeabi-v7a/libkiteplayer_libass_jni.so
    lib/x86_64/libkiteplayer_libass_jni.so
)
# The hosts of LibassAdapters.CHAIN_DIR_FOR_HOST, with the library name each one links.
JVM_ENTRIES=(
    kiteplayer-libass-native/macos-arm64/libkiteplayer_libass_jni.dylib
    kiteplayer-libass-native/linux-x64/libkiteplayer_libass_jni.so
    kiteplayer-libass-native/linux-arm64/libkiteplayer_libass_jni.so
    kiteplayer-libass-native/windows-x64/kiteplayer_libass_jni.dll
)

# Prints a line for each entry of $2... that the jar $1 lacks, and returns non-zero when any is.
missing() {
    local jar="$1" listing entry status=0
    shift
    if [ ! -f "$jar" ]; then
        echo "no jar at $jar; publish to Maven Local first"
        return 1
    fi
    listing=$(unzip -Z1 "$jar")
    for entry in "$@"; do
        if ! grep -qxF "$entry" <<< "$listing"; then
            echo "$(basename "$jar") has no $entry"
            status=1
        fi
    done
    return $status
}

check() {
    local natives="$1" jvm="$2" status=0
    missing "$natives" "${ANDROID_ENTRIES[@]}" || status=1
    missing "$jvm" "${JVM_ENTRIES[@]}" || status=1
    return $status
}

if [ "${1:-}" = "--falsify" ]; then
    scratch=$(mktemp -d)
    trap 'rm -rf "$scratch"' EXIT
    failures=0
    for pair in "natives:${ANDROID_ENTRIES[1]}" "jvm:${JVM_ENTRIES[3]}"; do
        which="${pair%%:*}"
        entry="${pair#*:}"
        cp "$NATIVES_JAR" "$scratch/natives.jar"
        cp "$JVM_JAR" "$scratch/jvm.jar"
        zip -q -d "$scratch/$which.jar" "$entry"
        if check "$scratch/natives.jar" "$scratch/jvm.jar" > /dev/null; then
            echo "FALSIFY: a $which jar without $entry passed"
            failures=1
        else
            echo "falsify: a $which jar without $entry fails, as it must"
        fi
    done
    exit $failures
fi

if check "$NATIVES_JAR" "$JVM_JAR"; then
    echo "both libass jars carry every native library: ${#ANDROID_ENTRIES[@]} Android ABIs, ${#JVM_ENTRIES[@]} desktop hosts"
else
    echo "A libass jar is missing a native library. Publish with KITEPLAYER_LIBASS_REQUIRE_ALL_HOST_JNI=true and" \
        "KITEPLAYER_LIBASS_REQUIRE_ANDROID_JNI=true to see why the build skipped it (#776)." >&2
    exit 1
fi
