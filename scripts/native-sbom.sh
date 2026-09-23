#!/usr/bin/env bash
#
# Writes the bill of materials for the native code that KitePlayer's artifacts bundle, as an
# SPDX 2.3 JSON document.
#
# Only kiteplayer-libass bundles third-party native code. It links libass, HarfBuzz, FreeType and
# FriBidi statically, from the "ass chain" archives that the KiteFFmpeg repository builds and
# releases. For each archive the document records its download location and its SHA-256 digest.
# For each library it records the version, the source repository and tag, and the licence.
#
# The adapters also link runtime code from the toolchains that built them: the C++ runtime of the
# Android NDK, the GCC runtime and GNU libiconv of the Kotlin/Native MinGW toolchain, and the
# Emscripten runtime. The script finds those toolchains the way kiteplayer-libass/build.gradle.kts
# does. A toolchain that this machine lacks gets NOASSERTION as its version. The startup objects
# that every shared library links are not listed.
#
# FFmpeg reaches KitePlayer only as the kiteffmpeg Maven dependency, which KitePlayer does not
# bundle, so its contents belong in the KiteFFmpeg repository's own bill of materials.
#
#   ./scripts/native-sbom.sh                  # writes build/sbom/kiteplayer-<VERSION>-native.spdx.json
#   ./scripts/native-sbom.sh --out FILE       # writes FILE
#   ./scripts/native-sbom.sh --chains DIR     # keeps the downloaded archives in DIR, and reuses them
#
# The script downloads each archive pinned in kiteplayer-libass/ass-chain.sha256 and refuses one
# whose digest differs. It also refuses when two archives name different library versions, and
# when NOTICE does not name a version that the artifacts contain. Needs curl, unzip, shasum and jq.
#
# The document describes the pinned archives. A build that takes its chain from
# ../KiteFFmpeg/native-libs/deps or from -Pkiteplayer.libass.root links whatever that directory
# holds. For that reason the document for a release comes from the publish workflow, where the
# build downloads the pinned archives.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINS="$ROOT/kiteplayer-libass/ass-chain.sha256"
REPOSITORY="yuroyami/KiteFFmpeg"

out=""
chains=""
while [ $# -gt 0 ]; do
    case "$1" in
        --out) out="$2"; shift 2 ;;
        --chains) chains="$2"; shift 2 ;;
        *) echo "usage: $0 [--out FILE] [--chains DIR]" >&2; exit 2 ;;
    esac
done

version=$(sed -n 's/^VERSION=//p' "$ROOT/gradle.properties")
release=$(sed -n 's/^val assChainReleaseTag = "\(.*\)"$/\1/p' "$ROOT/kiteplayer-libass/build.gradle.kts")
mingw_name=$(sed -n 's/.*"mingw_x64" -> CTargetSpec(.*konanSysroot = "\([^"]*\)".*/\1/p' \
    "$ROOT/buildSrc/src/main/kotlin/CompileKiteRtTask.kt")
[ -n "$version" ] && [ -n "$release" ] && [ -n "$mingw_name" ] || {
    echo "native-sbom.sh: could not read the version, the chain release or the MinGW sysroot name" >&2
    exit 1
}
out="${out:-$ROOT/build/sbom/kiteplayer-$version-native.spdx.json}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [ -z "$chains" ]; then
    chains="$work/chains"
fi
mkdir -p "$chains"

# One JSON object per archive: asset, target, digest, and the four versions from its CHAIN.txt.
archives="$work/archives.jsonl"
: > "$archives"
while read -r digest asset; do
    case "$digest" in ''|'#'*) continue ;; esac
    url="https://github.com/$REPOSITORY/releases/download/$release/$asset"
    [ -f "$chains/$asset" ] || curl -fsSL -o "$chains/$asset" "$url"
    actual=$(shasum -a 256 "$chains/$asset" | cut -d' ' -f1)
    [ "$actual" = "$digest" ] || {
        echo "native-sbom.sh: $asset has digest $actual, but ass-chain.sha256 pins $digest" >&2
        exit 1
    }
    manifest=$(unzip -p "$chains/$asset" CHAIN.txt)
    library() { printf '%s\n' "$manifest" | awk -v name="$1" '$1 == name { print $2 }'; }
    jq -cn --arg asset "$asset" --arg url "$url" --arg digest "$digest" \
        --arg fribidi "$(library fribidi)" --arg freetype "$(library freetype)" \
        --arg harfbuzz "$(library harfbuzz)" --arg libass "$(library libass)" \
        '{asset: $asset, target: ($asset | sub("^ass-chain-"; "") | sub("\\.zip$"; "")), url: $url,
          digest: $digest, tags: {fribidi: $fribidi, freetype: $freetype, harfbuzz: $harfbuzz, libass: $libass}}' \
        >> "$archives"
done < "$PINS"

[ -s "$archives" ] || { echo "native-sbom.sh: $PINS pins no archive" >&2; exit 1; }
if [ "$(jq -c '.tags' "$archives" | sort -u | wc -l | tr -d ' ')" != 1 ]; then
    echo "native-sbom.sh: the archives were built from different library versions:" >&2
    jq -r '"  \(.target): \(.tags)"' "$archives" >&2
    exit 1
fi
tags=$(head -1 "$archives" | jq -c '.tags')
for name in fribidi freetype harfbuzz libass; do
    [ -n "$(jq -r --arg n "$name" '.[$n]' <<<"$tags")" ] || {
        echo "native-sbom.sh: CHAIN.txt names no $name version" >&2
        exit 1
    }
done

# The toolchains. Each lookup prints NOASSERTION, and says why on stderr, when it finds nothing.
unknown() { echo "native-sbom.sh: $1, so its version is NOASSERTION" >&2; echo NOASSERTION; }

# The Android NDK: the environment first, then the newest NDK of the first SDK that has one.
ndk=""
for variable in ANDROID_NDK_HOME ANDROID_NDK_ROOT ANDROID_NDK_LATEST_HOME; do
    if [ -d "${!variable:-}" ]; then ndk="${!variable}"; break; fi
done
if [ -z "$ndk" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)
    for root in "$sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$root" ] && [ -d "$root/ndk" ]; then
            # Folders only, as the build does: an SDK folder can also hold Finder aliases.
            ndk=$(find -L "$root/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
            break
        fi
    done
fi
ndk_version=$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk/source.properties" 2>/dev/null || true)
[ -n "$ndk_version" ] || ndk_version=$(unknown "no Android NDK was found")

# The MinGW toolchain that Kotlin/Native downloads, by the name CompileKiteRtTask gives it.
mingw="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies/$mingw_name"
gcc_version=$(ls "$mingw/lib/gcc/x86_64-w64-mingw32" 2>/dev/null | sort -V | tail -1 || true)
[ -n "$gcc_version" ] || gcc_version=$(unknown "no MinGW toolchain is at $mingw")
# iconv.h spells the version as (major << 8) + minor, in hexadecimal.
iconv_hex=$(sed -n 's/^#define _LIBICONV_VERSION 0x\([0-9a-fA-F]\{4\}\).*/\1/p' "$mingw/include/iconv.h" 2>/dev/null || true)
if [ -n "$iconv_hex" ]; then
    iconv_version="$((16#${iconv_hex:0:2})).$((16#${iconv_hex:2:2}))"
else
    iconv_version=$(unknown "no iconv.h is in $mingw/include")
fi

emscripten_version=$({ emcc --version 2>/dev/null || true; } | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
[ -n "$emscripten_version" ] || emscripten_version=$(unknown "emcc is not on the PATH")

# NOTICE must name every version that the artifacts contain. Tags become the version a person
# would write: v1.0.16 is 1.0.16, and VER-2-14-3 is 2.14.3.
plain() { sed -e 's/^v//' -e 's/^VER-//' -e 's/-/./g' <<<"$1"; }
named() {
    grep -qiF "$1 $2" "$ROOT/NOTICE" || {
        echo "native-sbom.sh: NOTICE does not name $1 $2, the version the artifacts contain" >&2
        exit 1
    }
}
for name in fribidi freetype harfbuzz libass; do
    named "$name" "$(plain "$(jq -r --arg n "$name" '.[$n]' <<<"$tags")")"
done
[ "$iconv_version" = NOASSERTION ] || named libiconv "$iconv_version"

namespace_id=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || true)
mkdir -p "$(dirname "$out")"
jq -n --slurpfile archives "$archives" --argjson tags "$tags" \
    --arg version "$version" --arg release "$release" \
    --arg ndk "$ndk_version" --arg gcc "$gcc_version" --arg iconv "$iconv_version" \
    --arg emscripten "$emscripten_version" \
    --arg namespace "${namespace_id:-$(date -u +%Y%m%d%H%M%S)}" \
    --arg created "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
    def plain: sub("^v"; "") | sub("^VER-"; "") | gsub("-"; ".");
    def libraries: [
        {id: "libass", name: "libass", tag: $tags.libass, declared: "ISC", concluded: "ISC",
         source: "https://github.com/libass/libass"},
        {id: "harfbuzz", name: "HarfBuzz", tag: $tags.harfbuzz,
         declared: "MIT-Modern-Variant", concluded: "MIT-Modern-Variant",
         source: "https://github.com/harfbuzz/harfbuzz"},
        {id: "freetype", name: "FreeType", tag: $tags.freetype,
         declared: "FTL OR GPL-2.0-or-later", concluded: "FTL",
         source: "https://gitlab.freedesktop.org/freetype/freetype",
         comment: "Offered under two licences. KitePlayer uses it under the FreeType License."},
        {id: "fribidi", name: "FriBidi", tag: $tags.fribidi,
         declared: "LGPL-2.1-or-later", concluded: "LGPL-2.1-or-later",
         source: "https://github.com/fribidi/fribidi"}
    ];
    def windows: "The Windows adapter in the JVM jar links it statically, and so does a Windows executable that Kotlin/Native links against the mingwX64 klib.";
    def runtimes: [
        {id: "libcxx-android", name: "LLVM libc++ (Android NDK)", version: $ndk,
         licence: "Apache-2.0 WITH LLVM-exception", homepage: "https://developer.android.com/ndk",
         comment: "The C++ runtime of the Android NDK that built the adapters, which each Android adapter in the AAR links statically. The version is the NDK revision."},
        {id: "gcc-runtime-mingw", name: "GCC runtime libraries (libstdc++, libgcc)", version: $gcc,
         licence: "GPL-3.0-or-later WITH GCC-exception-3.1", homepage: "https://gcc.gnu.org",
         comment: "From the Kotlin/Native MinGW toolchain, which MSYS2 builds. \(windows)"},
        {id: "libiconv-mingw", name: "GNU libiconv", version: $iconv,
         licence: "LGPL-2.0-or-later", homepage: "https://www.gnu.org/software/libiconv/",
         comment: "From the Kotlin/Native MinGW toolchain, which MSYS2 builds. \(windows)"},
        {id: "emscripten-runtime", name: "Emscripten runtime and system libraries", version: $emscripten,
         licence: "MIT OR NCSA", homepage: "https://emscripten.org",
         comment: "emcc links its JavaScript runtime and its system libraries into kiteass.mjs and kiteass.wasm, which the web zip carries. The system libraries (musl, libc++, compiler-rt) carry their own licences."}
    ];
    {
        spdxVersion: "SPDX-2.3",
        dataLicense: "CC0-1.0",
        SPDXID: "SPDXRef-DOCUMENT",
        name: "KitePlayer \($version) bundled native code",
        documentNamespace: "https://github.com/yuroyami/KitePlayer/spdx/kiteplayer-\($version)-native-\($namespace)",
        creationInfo: {created: $created, creators: ["Tool: KitePlayer scripts/native-sbom.sh"]},
        packages: (
            [{
                SPDXID: "SPDXRef-kiteplayer-libass",
                name: "io.github.yuroyami:kiteplayer-libass",
                versionInfo: $version,
                downloadLocation: "https://repo1.maven.org/maven2/io/github/yuroyami/kiteplayer-libass/\($version)/",
                homepage: "https://github.com/yuroyami/KitePlayer",
                licenseDeclared: "Apache-2.0",
                licenseConcluded: "NOASSERTION",
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                comment: "Links the libraries below into the artifacts it publishes. Each package names its own licence."
            }]
            + [libraries[] | {
                SPDXID: "SPDXRef-\(.id)",
                name: .name,
                versionInfo: (.tag | plain),
                downloadLocation: "git+\(.source)@\(.tag)",
                sourceInfo: "Built from the \(.tag) tag of \(.source)",
                licenseDeclared: .declared,
                licenseConcluded: .concluded,
                copyrightText: "NOASSERTION",
                filesAnalyzed: false
            } + (if .comment then {comment: .comment} else {} end)]
            + [$archives[] | {
                SPDXID: "SPDXRef-ass-chain-\(.target)",
                name: .asset,
                versionInfo: $release,
                downloadLocation: .url,
                checksums: [{algorithm: "SHA256", checksumValue: .digest}],
                licenseDeclared: "NOASSERTION",
                licenseConcluded: "NOASSERTION",
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                comment: "Static libraries for \(.target), built by the KiteFFmpeg repository from the tags that the archive'"'"'s CHAIN.txt lists."
            }]
            + [runtimes[] | {
                SPDXID: "SPDXRef-\(.id)",
                name: .name,
                versionInfo: .version,
                downloadLocation: "NOASSERTION",
                homepage: .homepage,
                licenseDeclared: .licence,
                licenseConcluded: .licence,
                copyrightText: "NOASSERTION",
                filesAnalyzed: false,
                comment: .comment
            }]
        ),
        relationships: (
            [{spdxElementId: "SPDXRef-DOCUMENT", relationshipType: "DESCRIBES",
              relatedSpdxElement: "SPDXRef-kiteplayer-libass"}]
            + [(libraries[], runtimes[]) | {spdxElementId: "SPDXRef-kiteplayer-libass",
                relationshipType: "STATIC_LINK", relatedSpdxElement: "SPDXRef-\(.id)"}]
            + [$archives[] as $archive | libraries[] | {spdxElementId: "SPDXRef-ass-chain-\($archive.target)",
                relationshipType: "CONTAINS", relatedSpdxElement: "SPDXRef-\(.id)"}]
            + [$archives[] | {spdxElementId: "SPDXRef-ass-chain-\(.target)",
                relationshipType: "BUILD_DEPENDENCY_OF", relatedSpdxElement: "SPDXRef-kiteplayer-libass"}]
        )
    }' > "$out"

echo "native-sbom.sh: $(jq '.packages | length' "$out") packages from $(wc -l < "$archives" | tr -d ' ') archives in $out"
