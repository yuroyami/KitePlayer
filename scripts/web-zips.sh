#!/usr/bin/env bash
#
# Builds the two web zips of KitePlayer into build/web-zips/:
#
#   kiteplayer-wasm-js-<version>-web.zip          the worker binary that KitePlayerWorker loads
#   kiteplayer-libass-wasm-js-<version>-web.zip   the libass web module (needs emcc on PATH)
#
# A page serves these files beside index.html (see the README's web setup). A Kotlin Toolchain
# publication carries no extra files, so the zips go to the GitHub release page, not Maven Central.
#
# The worker binary goes through Binaryen's wasm-opt with the flags that Kotlin's Gradle plugin
# uses for a production wasmJs binary. The compiler's own output is more than twice the size
# gzipped. Binaryen is downloaded once into build/binaryen and checked against its SHA-256 file.
#
#   ./scripts/web-zips.sh             # both zips
#   ./scripts/web-zips.sh worker      # the worker zip only, for scripts/check-web-size.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
WHICH="${1:-all}"
case "$WHICH" in all|worker|libass) ;; *) echo "usage: $0 [worker|libass]" >&2; exit 2 ;; esac

VERSION=$(sed -n 's/^    version:[[:space:]]*//p' library.module-template.yaml | tr -d '\r')
[ -n "$VERSION" ] || { echo "library.module-template.yaml has no publishing version" >&2; exit 2; }
OUT="$ROOT/build/web-zips"
mkdir -p "$OUT"

# The Binaryen release that Kotlin 2.4's Gradle plugin downloads, and the flags it passes.
BINARYEN_VERSION=130
BINARYEN_FLAGS=(--enable-gc --enable-reference-types --enable-exception-handling --enable-bulk-memory
    --enable-stack-switching --enable-multivalue --enable-nontrapping-float-to-int
    --no-inline=kotlin.wasm.internal.throwValue --no-inline=kotlin.wasm.internal.getKotlinException
    --no-inline=kotlin.wasm.internal.jsToKotlinStringAdapter --inline-functions-with-loops
    --traps-never-happen --fast-math --closed-world --type-ssa -O3 -O3 --gufa -O3 --type-merging -O3 -Oz)

wasm_opt() {
    local classifier dir archive url
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64) classifier=arm64-macos ;;
        Darwin-x86_64) classifier=x86_64-macos ;;
        Linux-x86_64) classifier=x86_64-linux ;;
        Linux-aarch64) classifier=aarch64-linux ;;
        *) echo "no Binaryen build for $(uname -s) $(uname -m)" >&2; exit 2 ;;
    esac
    dir="$ROOT/build/binaryen/binaryen-version_$BINARYEN_VERSION"
    if [ ! -x "$dir/bin/wasm-opt" ]; then
        archive="binaryen-version_$BINARYEN_VERSION-$classifier.tar.gz"
        url="https://github.com/WebAssembly/binaryen/releases/download/version_$BINARYEN_VERSION/$archive"
        mkdir -p "$ROOT/build/binaryen"
        curl -fsSL -o "$ROOT/build/binaryen/$archive" "$url"
        curl -fsSL -o "$ROOT/build/binaryen/$archive.sha256" "$url.sha256"
        expected=$(awk '{ print $1 }' "$ROOT/build/binaryen/$archive.sha256")
        actual=$(shasum -a 256 "$ROOT/build/binaryen/$archive" | awk '{ print $1 }')
        [ "$expected" = "$actual" ] || { echo "$archive has SHA-256 $actual, expected $expected" >&2; exit 1; }
        tar -xzf "$ROOT/build/binaryen/$archive" -C "$ROOT/build/binaryen"
        rm -f "$ROOT/build/binaryen/$archive" "$ROOT/build/binaryen/$archive.sha256"
    fi
    echo "$dir/bin/wasm-opt"
}

worker_zip() {
    KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin build -m kiteplayer-web-worker -p wasmJs --variant release
    local built work opt zip
    built=$(find build/artifacts build/tasks -maxdepth 3 -type f -name 'kiteplayer-web-worker.wasm' -path '*elease*' 2>/dev/null | head -1)
    [ -n "$built" ] || { echo "the release build made no kiteplayer-web-worker.wasm" >&2; exit 1; }
    built=$(dirname "$built")
    work=$(mktemp -d)
    cp "$built"/kiteplayer-web-worker.mjs "$work/"
    cp "$built"/kiteplayer-web-worker.*.mjs "$work/" 2>/dev/null || true
    opt=$(wasm_opt)
    "$opt" "${BINARYEN_FLAGS[@]}" "$built/kiteplayer-web-worker.wasm" -o "$work/kiteplayer-web-worker.wasm"
    # Everything in the binary is Kotlin under the Apache License; the codec and the typesetter are
    # the other two zips, with their own licences.
    mkdir -p "$work/licenses/kiteplayer"
    cp LICENSE NOTICE "$work/licenses/kiteplayer/"
    zip="$OUT/kiteplayer-wasm-js-$VERSION-web.zip"
    rm -f "$zip"
    (cd "$work" && zip -qr -X "$zip" .)
    rm -rf "$work"
    echo "$zip"
}

libass_zip() {
    command -v emcc > /dev/null || { echo "emcc is not on PATH (brew install emscripten)" >&2; exit 2; }
    # The web-assets plugin links kiteass.mjs and kiteass.wasm into the module's web test resources.
    KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin task :kiteplayer-libass:tests@web-assets
    local dir="kiteplayer-libass/testResources@wasmJs" work zip
    [ -f "$dir/kiteass.mjs" ] && [ -f "$dir/kiteass.wasm" ] || { echo "no kiteass module in $dir" >&2; exit 1; }
    work=$(mktemp -d)
    cp "$dir/kiteass.mjs" "$dir/kiteass.wasm" "$work/"
    # The module contains the libass chain, so its licence texts travel with it. libiconv is Windows only.
    mkdir -p "$work/licenses"
    find kiteplayer-libass/resources@jvm/META-INF/licenses/kiteplayer-libass -type f ! -name 'libiconv-*' \
        -exec cp {} "$work/licenses/" \;
    zip="$OUT/kiteplayer-libass-wasm-js-$VERSION-web.zip"
    rm -f "$zip"
    (cd "$work" && zip -qr -X "$zip" .)
    rm -rf "$work"
    echo "$zip"
}

case "$WHICH" in
    worker) worker_zip ;;
    libass) libass_zip ;;
    all) worker_zip; libass_zip ;;
esac
