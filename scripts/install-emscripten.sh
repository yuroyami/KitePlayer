#!/usr/bin/env bash
#
# Installs the emscripten that emscripten-version.txt pins, into <dir> (#778).
#
# The libass web module, kiteass.mjs and kiteass.wasm, is linked by emcc against the wasm32 libass
# chain that KiteFFmpeg's release job builds with the same pinned version. One file holds the
# version so that CI, the publish job and the bill of materials all name one compiler, and a new
# emscripten release cannot change what a commit builds.
#
# Under GitHub Actions it puts emcc on the PATH of the later steps and sets EMSDK for them.
#
#   ./scripts/install-emscripten.sh "$RUNNER_TEMP/emsdk"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=$(tr -d '[:space:]' < "$ROOT/emscripten-version.txt")
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "emscripten-version.txt holds '$VERSION', not an exact version" >&2; exit 2; }
DIR="${1:?usage: $0 <install dir>}"

if [ ! -x "$DIR/emsdk" ]; then
    git clone --depth 1 --branch "$VERSION" https://github.com/emscripten-core/emsdk.git "$DIR"
fi
"$DIR/emsdk" install "$VERSION"
"$DIR/emsdk" activate "$VERSION"

found=$("$DIR/upstream/emscripten/emcc" --version | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
[ "$found" = "$VERSION" ] || { echo "emsdk installed emcc $found, not the pinned $VERSION" >&2; exit 1; }
echo "emcc $found, as emscripten-version.txt pins"

if [ -n "${GITHUB_PATH:-}" ]; then
    echo "$DIR" >> "$GITHUB_PATH"
    echo "$DIR/upstream/emscripten" >> "$GITHUB_PATH"
fi
if [ -n "${GITHUB_ENV:-}" ]; then
    echo "EMSDK=$DIR" >> "$GITHUB_ENV"
fi
