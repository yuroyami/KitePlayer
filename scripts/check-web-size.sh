#!/usr/bin/env bash
#
# Holds the web modules to their size budgets.
#
# A web page downloads the codec module that the player pins: kite.wasm and kite.mjs from the
# KiteFFmpeg web zip (see the README's Web section). A page that runs the player in a worker also
# downloads the worker binary, from kiteplayer's own web zip (#58). This script measures the files
# of each gzipped at level 9, and fails when either sum is over its budget below.
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
# The worker budget is now 0.64 MiB. HLS plays in the worker (#546), so the code that reads a
# playlist, serves a variant and moves to another one is reached there for the first time: the
# worker measured 668,414 bytes with it and 644,892 without, 23,522 more.
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
# 0.64 MiB.
WORKER_BUDGET_BYTES=671089

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

./gradlew --quiet :kiteplayer-ffmpeg:unpackKiteFFmpegWebModule :kiteplayer:workerWebZip
grep -E '^kiteffmpeg = "' gradle/libs.versions.toml

measure "Web codec module" "$BUDGET_BYTES" \
    kiteplayer-ffmpeg/build/kiteffmpeg-web/kite.wasm kiteplayer-ffmpeg/build/kiteffmpeg-web/kite.mjs

# Measured from the zip that ships, so what is counted is what a page serves.
worker_dir="$(mktemp -d)"
trap 'rm -rf "$worker_dir"' EXIT
unzip -q kiteplayer/build/worker-zip/kiteplayer-wasm-js-*-web.zip -d "$worker_dir"
measure "Web worker binary" "$WORKER_BUDGET_BYTES" \
    "$worker_dir"/kiteplayer-web-worker.wasm "$worker_dir"/kiteplayer-web-worker*.mjs
