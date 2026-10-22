#!/usr/bin/env bash
#
# Holds the web modules to their size budgets.
#
# A web page downloads the codec module that the player pins: kite.wasm and kite.mjs from the
# KiteFFmpeg web zip (see the README's Web section). A page that runs the player in a worker also
# downloads the worker binary, from kiteplayer's own web zip (#58), which scripts/web-zips.sh
# builds. This script measures the files of each gzipped at level 9, and fails when either sum is
# over its budget below.
#
# Gzipped and not raw, because the raw module answers the wrong question: exporting every binding
# entry point makes the raw module about four times bigger and the gzipped one only about six
# percent bigger. The measurement uses Python's zlib, so macOS and Linux count the same bytes.
#
# The codec budget was the size of the module that KiteFFmpeg 0.3.0 publishes, 1.42 MiB, which the
# owner chose on #58. KiteFFmpeg 0.5.0 raised it to 1.47 MiB, because its module carries FFmpeg's
# HLS reader so that a nested opener can play HLS and DASH in a browser, and the readers that 0.5.0
# added beside it, from the Dolby Vision RPU to Matroska editions (#523).
#
# The worker budget was 0.61 MiB (#554), and 0.53 MiB before: the 0.50 MiB the binary measured once
# its zip took Binaryen's optimised build and the worker stopped linking the network transport it
# never asks (#519), with a little room. That measurement used a local KiteFFmpeg 0.5.0 build from
# before the release; the same commit built against the released 0.5.0 measures 537,689 bytes. On
# 2026-10-07 the worker measured 611,648 bytes, and the 73,939 more were player features, not a
# packaging fault: the zip still held the optimiser's output byte for byte, and the unoptimised
# binary's code grew by 253,458 bytes spread over hundreds of functions of the engine, its FFmpeg
# adapter and its subtitle readers (closed captions, teletext, lyrics, cue sheets, seek bar
# pictures, turned pictures, late streams), the largest under 5 KB. Until #519 the zip held the
# compiler's unoptimised output instead, 1.21 MiB when it first shipped and 1.49 MiB by then,
# against a budget of 1.25 MiB.
#
# The worker budget was then 0.62 MiB. On 2026-10-08 the worker measured 643,266 bytes, 11,550 more
# than the 631,716 of the run before it. Three features came between the two: text subtitles drawn
# on the web canvas (#559), the audio output that reopens when the route changes its channel count
# (#563), and the red flash rule of the flash guard (#561). The binary grew by 7,577 bytes and the
# JavaScript beside it by 3,973, which is the subtitle drawing and the longer flash measure.
#
# The worker budget was then 0.64 MiB. HLS plays in the worker (#546), so the code that reads a
# playlist, serves a variant and moves to another one is reached there for the first time: the
# worker measured 668,414 bytes with it and 644,892 without, 23,522 more.
#
# The worker budget was then 0.69 MiB. DASH plays in the worker (#546), which links the DASH reader
# of kiteplayer-adaptive: the manifest parser, the playlists it writes for the HLS path, the
# fragmented MP4 and WebM index readers and the subtitle conversions. The worker measured 720,786
# bytes with it and 668,414 without, 52,372 more. Ktor stays out of the worker.
#
# The worker budget was then 0.70 MiB. A quality change with no new open reaches a DASH stream of
# several Periods and a WebM one (#566), which adds the WebM header reader and the rewrite of a
# cluster's track numbers. The worker measured 723,596 bytes with it, 2,810 more, and 79 over the
# budget before.
#
# The worker budget was then 0.71 MiB. Kotlin 2.4.21 grew the worker from 731,855 bytes to 735,258,
# 3,403 more and 1,255 over the budget before, with no player change between the two runs. The
# growth is all in the wasm binary; the three JavaScript files beside it kept their exact bytes.
# The codec module measured the same 1,537,134 bytes in both runs.
#
# The worker budget is now 0.73 MiB. Matroska editions (#501) grew the worker from 737,229 bytes to
# 754,187, 16,958 more and 9,698 over the budget before: an ordered edition's timeline, its parts
# in other files, the edition switch, and the worker's messages that carry them. 360 degree video
# (#460) then added 3,361, to 757,548: the projection on a track, the view and its warning, in the
# engine and in the worker's messages. The growth is all in the wasm binary; the three JavaScript
# files beside it kept their exact bytes, and the codec module measured 1,538,723 bytes throughout.
#
# The worker budget is now 0.85 MiB. The Kotlin Toolchain has no dependency exclusion, so the worker
# links kiteplayer-network, which kiteplayer brings on wasmJs, and Ktor with it. The worker measured
# 757,505 bytes without kiteplayer-network and 877,468 bytes with it, 119,963 more. Taking
# kiteplayer-network out of kiteplayer's wasmJs dependencies brings the old size back, but a web app
# then no longer gets kiteplayer-network from kiteplayer.
#
# Both budgets are ratchets: a new KiteFFmpeg pin, or player code, that grows a module past its
# budget fails here, and raising a budget is a decision made in the same commit, with both numbers
# in its message. Each file's SHA-256 is printed beside its size, so a failure names the exact bytes
# it measured, and CI keeps those files as the run's web-size artifact.
#
#   ./scripts/check-web-size.sh    # build, unpack, measure, compare: must PASS

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 1.47 MiB.
BUDGET_BYTES=1541407
# 0.85 MiB.
WORKER_BUDGET_BYTES=891289

# Measures the files after the label gzipped, prints a table, and fails when the sum is over the budget.
measure() { # <label> <budget bytes> <file>...
    python3 - "$@" <<'EOF'
import hashlib, os, sys, zlib

label = sys.argv[1]
budget = int(sys.argv[2])
total = 0
print(label)
print(f"{'file':<44} {'raw bytes':>12} {'gzipped':>12}  sha256")
for path in sys.argv[3:]:
    if not os.path.isfile(path):
        sys.exit(f"{path} is missing; the task that makes it did not produce it")
    data = open(path, "rb").read()
    # wbits 31 writes a gzip wrapper, which is what a server sends.
    packer = zlib.compressobj(9, zlib.DEFLATED, 31)
    packed = len(packer.compress(data) + packer.flush())
    total += packed
    print(f"{os.path.basename(path):<44} {len(data):>12} {packed:>12}  {hashlib.sha256(data).hexdigest()[:16]}")
mib = lambda n: n / (1024 * 1024)
print(f"{'total':<44} {'':>12} {total:>12}  ({mib(total):.3f} MiB, budget {mib(budget):.2f} MiB)")
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as out:
        out.write(f"{label}: {total} bytes gzipped ({mib(total):.3f} MiB), budget {budget} bytes ({mib(budget):.2f} MiB).\n")
if total > budget:
    sys.exit(f"{label} is {total - budget} bytes over its budget of {budget} bytes gzipped.")
print("PASS")
EOF
}

# The codec module: kite.wasm and kite.mjs from the KiteFFmpeg web files that libs.versions.toml pins.
# Releases after 0.5.1 ship them in the kiteffmpeg-web jar, and older ones in the web zip. Maven Local
# comes first, else Maven Central with the SHA-1 checked.
kiteffmpeg=$(sed -n 's/^kiteffmpeg = "\(.*\)"$/\1/p' libs.versions.toml)
[ -n "$kiteffmpeg" ] || { echo "libs.versions.toml has no kiteffmpeg version" >&2; exit 2; }
echo "kiteffmpeg = \"$kiteffmpeg\""
codec_dir="$(mktemp -d)"
worker_dir="$(mktemp -d)"
trap 'rm -rf "$codec_dir" "$worker_dir"' EXIT
codec_paths=(
    "kiteffmpeg-web/$kiteffmpeg/kiteffmpeg-web-$kiteffmpeg.jar"
    "kiteffmpeg-wasm-js/$kiteffmpeg/kiteffmpeg-wasm-js-$kiteffmpeg-web.zip"
)
codec_archive=""
for path in "${codec_paths[@]}"; do
    local_file="$HOME/.m2/repository/io/github/yuroyami/$path"
    if [ -f "$local_file" ]; then
        codec_archive="$codec_dir/${path##*/}"
        cp "$local_file" "$codec_archive"
        break
    fi
done
if [ -z "$codec_archive" ]; then
    for path in "${codec_paths[@]}"; do
        url="https://repo1.maven.org/maven2/io/github/yuroyami/$path"
        file="$codec_dir/${path##*/}"
        # A 404 here only means this form does not exist for the version, so try the next one.
        curl -fsL -o "$file" "$url" 2>/dev/null || continue
        expected=$(curl -fsSL "$url.sha1" | awk '{ print $1 }')
        actual=$(shasum -a 1 "$file" | awk '{ print $1 }')
        [ "$expected" = "$actual" ] || { echo "${path##*/} has SHA-1 $actual, expected $expected" >&2; exit 1; }
        codec_archive="$file"
        break
    done
fi
[ -n "$codec_archive" ] || { echo "no web files of KiteFFmpeg $kiteffmpeg in Maven Local or on Maven Central" >&2; exit 1; }
unzip -q "$codec_archive" -d "$codec_dir"

measure "Web codec module" "$BUDGET_BYTES" "$codec_dir/kite.wasm" "$codec_dir/kite.mjs"

# Measured from the zip that ships, so what is counted is what a page serves.
./scripts/web-zips.sh worker
unzip -q build/web-zips/kiteplayer-wasm-js-*-web.zip -d "$worker_dir"
measure "Web worker binary" "$WORKER_BUDGET_BYTES" \
    "$worker_dir"/kiteplayer-web-worker.wasm "$worker_dir"/kiteplayer-web-worker*.mjs
