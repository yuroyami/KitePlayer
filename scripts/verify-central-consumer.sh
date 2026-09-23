#!/usr/bin/env bash
#
# Builds the README's install lines the way a stranger would: from Maven Central alone, with an
# empty Gradle cache, in a consumer project that never sees this checkout's bytes.
#
# Every other check here resolves the player from this checkout, or from a staged copy of it. This
# one checks what the public repository hands a consumer:
#
#   - The install lines resolve for the JVM and for Android, and a consumer compiles against them.
#   - Every module asks for one version of the player and one version of KiteFFmpeg, the media
#     library. Gradle quietly picks the newest of two requests, so the check reads the requests.
#   - The FFmpeg inside KiteFFmpeg's native libraries is the series that this project records
#     against, EXPECTED_FFMPEG_SERIES in scripts/testmedia.sh.
#   - The player's default backend loads on this machine, from the published artifacts alone.
#
# It prints the version of every io.github.yuroyami artifact it pulled, and the native libraries
# that each one carries. The consumer project is verification/central-consumer.
#
#   ./scripts/verify-central-consumer.sh                    # an empty Gradle cache in a temp dir
#   ./scripts/verify-central-consumer.sh --user-home DIR    # DIR, which must hold no player yet
#
# An empty cache downloads Gradle itself and every dependency, so a run takes several minutes.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONSUMER="$ROOT/verification/central-consumer"
GROUP="io.github.yuroyami"

user_home=""
while [ $# -gt 0 ]; do
    case "$1" in
        --user-home)
            [ $# -ge 2 ] || { echo "usage: $0 [--user-home DIR]" >&2; exit 2; }
            user_home="$2"
            shift 2
            ;;
        *) echo "usage: $0 [--user-home DIR]" >&2; exit 2 ;;
    esac
done

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
[ -n "$user_home" ] || user_home="$scratch/gradle-home"
mkdir -p "$user_home"
cache="$user_home/caches/modules-2/files-2.1/$GROUP"
if [ -d "$cache" ]; then
    echo "$user_home already holds $GROUP artifacts, so the resolution would not be clean." >&2
    exit 2
fi
export GRADLE_USER_HOME="$user_home"

if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ -f "$ROOT/local.properties" ]; then
    ANDROID_HOME=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
    export ANDROID_HOME
fi

series=$(sed -n 's/^EXPECTED_FFMPEG_SERIES=//p' "$ROOT/scripts/testmedia.sh")
[ -n "$series" ] || { echo "scripts/testmedia.sh has no EXPECTED_FFMPEG_SERIES" >&2; exit 2; }

failures=0
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }

# ---- The install lines, from the Install section of the README only ----
coordinates=$(awk '/^## Install/ { inside = 1; next } /^## / { inside = 0 } inside' "$ROOT/README.md" |
    sed -nE 's/^[[:space:]]*implementation\("('"$GROUP"':[^"]+)"\).*/\1/p')
[ -n "$coordinates" ] || { echo "The Install section of README.md has no install line." >&2; exit 2; }
version=$(printf '%s\n' "$coordinates" | awk -F: '{ print $3 }' | sort -u)
if [ "$(printf '%s\n' "$version" | wc -l | tr -d ' ')" != 1 ]; then
    echo "The install lines name more than one version: $(echo $version)" >&2
    exit 1
fi
echo "== The install lines of README.md, version $version"
printf '  %s\n' $coordinates
echo

consumer_gradle() {
    # No daemon and an in-process compiler, so nothing keeps running on the temporary Gradle home.
    "$ROOT/gradlew" -p "$CONSUMER" --no-daemon --console=plain \
        -Pkotlin.compiler.execution.strategy=in-process \
        -PinstallLines="$(echo $coordinates)" "$@"
}

# ---- Build and run the consumer ----
echo "== Building the consumer for the JVM and Android, then running it on the JVM"
consumer_gradle compileKotlinJvm compileAndroidMain runJvmProbe | tee "$scratch/build.log"
echo

# ---- The requests, which conflict resolution would otherwise hide ----
echo "== What each module asks for"
for configuration in jvmRuntimeClasspath androidRuntimeClasspath; do
    consumer_gradle -q dependencies --configuration "$configuration" > "$scratch/$configuration.txt"
    grep -oE "$GROUP:[^ ]+( -> [^ ]+)?" "$scratch/$configuration.txt" | sort -u > "$scratch/$configuration.requests"
    [ -s "$scratch/$configuration.requests" ] || fail "$configuration resolved no $GROUP module"
    while IFS= read -r request; do
        case "$request" in
            *" -> "*) fail "$configuration: a module asks for $request, so two versions were requested" ;;
        esac
    done < "$scratch/$configuration.requests"
done
cat "$scratch"/*.requests | sed 's/ -> .*//' | sort -u > "$scratch/all.requests"
media_versions=$(grep -E "^$GROUP:kiteffmpeg[^:]*:" "$scratch/all.requests" | awk -F: '{ print $3 }' | sort -u)
player_versions=$(grep -E "^$GROUP:kiteplayer[^:]*:" "$scratch/all.requests" | awk -F: '{ print $3 }' | sort -u)
echo "  KiteFFmpeg versions asked for: $(echo $media_versions)"
echo "  player versions asked for:     $(echo $player_versions)"
[ "$(echo $media_versions | wc -w | tr -d ' ')" = 1 ] || fail "the modules ask for more than one KiteFFmpeg version"
[ "$(echo $player_versions)" = "$version" ] || fail "the modules ask for player versions other than $version"
echo

# ---- Every artifact pulled, and the native libraries inside it ----
echo "== Every $GROUP artifact pulled from the public repositories"
find "$cache" -type f \( -name '*.jar' -o -name '*.aar' \) ! -name '*-sources.jar' ! -name '*-javadoc.jar' |
    sort > "$scratch/pulled.txt"
[ -s "$scratch/pulled.txt" ] || fail "no $GROUP jar or Android archive was pulled"
while IFS= read -r file; do
    # files-2.1/<group>/<module>/<version>/<sha1>/<file>
    module_version=$(dirname "$(dirname "$file")")
    printf '  %s %s, %s bytes\n' "$(basename "$(dirname "$module_version")")" "$(basename "$module_version")" \
        "$(( $(wc -c < "$file") ))"
    unzip -Z1 "$file" 2>/dev/null | grep -E '\.(so|dylib|dll|jnilib)$' | sed 's/^/      native: /' || true
done < "$scratch/pulled.txt"
echo

# ---- The FFmpeg inside the media library ----
# Only some builds carry the banner "FFmpeg version X". Every build carries the libavcodec ident,
# such as Lavc62.28.102, so each library must share the ident of one that states the series. A
# bare "8.1.2" proves nothing: channel layouts such as "7.1.4" are strings of the same shape.
echo "== The FFmpeg inside KiteFFmpeg's native libraries (expected series $series)"
: > "$scratch/ffmpeg.txt"
while IFS= read -r file; do
    case "$(basename "$file")" in kiteffmpeg*) ;; *) continue ;; esac
    for entry in $(unzip -Z1 "$file" 2>/dev/null | grep -E '\.(so|dylib|dll)$' || true); do
        unzip -p "$file" "$entry" | strings -a > "$scratch/strings.txt"
        banner=$(grep -m1 -oE 'FFmpeg version n?[0-9][0-9.]*' "$scratch/strings.txt" | sed 's/^FFmpeg version n\{0,1\}//' || true)
        ident=$(grep -m1 -xE 'Lavc[0-9]+\.[0-9]+\.[0-9]+' "$scratch/strings.txt" || true)
        shown="no banner"
        [ -z "$banner" ] || shown="FFmpeg $banner"
        printf '  %s %s: %s, %s\n' "$(basename "$file")" "$entry" "$shown" "${ident:-no libavcodec ident}"
        echo "${ident:-none} ${banner:-none} $(basename "$file") $entry" >> "$scratch/ffmpeg.txt"
    done
done < "$scratch/pulled.txt"
[ -s "$scratch/ffmpeg.txt" ] || fail "no KiteFFmpeg native library was pulled"
stated=$(awk -v series="$series" '$2 ~ "^" series "\\." { print $1 }' "$scratch/ffmpeg.txt" | sort -u)
if [ -z "$stated" ]; then
    fail "no KiteFFmpeg native library states FFmpeg $series"
else
    while read -r ident _ artifact entry; do
        case " $(echo $stated) " in
            *" $ident "*) ;;
            *) fail "$artifact $entry has $ident, not the libavcodec of the library that states FFmpeg $series" ;;
        esac
    done < "$scratch/ffmpeg.txt"
fi
echo

# ---- The backend, on this machine ----
availability=$(sed -n 's/^CONSUMER availability //p' "$scratch/build.log")
echo "== The default backend on $(uname -s) $(uname -m): ${availability:-no answer}"
[ "$availability" = "Available" ] || fail "the default backend does not load here: ${availability:-the probe printed nothing}"
echo

if [ "$failures" -eq 0 ]; then
    echo "verify-central-consumer.sh: PASS"
    exit 0
fi
echo "verify-central-consumer.sh: FAIL, $failures problem(s)"
exit 1
