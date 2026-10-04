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
# owner chose on #58. KiteFFmpeg 0.5.0 raised it to 1.46 MiB, because its module carries FFmpeg's
# HLS reader so that a nested opener can play HLS and DASH in a browser. The worker budget is
# 0.70 MiB, the 0.66 MiB that the binary measured once its zip took Binaryen's optimised build, with
# a little room. Until #519 the zip held the compiler's unoptimised output instead, 1.21 MiB when it
# first shipped and 1.49 MiB by then, against a budget of 1.25 MiB. Both are ratchets: a new
# KiteFFmpeg pin, or player code, that grows a module past its budget fails here, and raising a
# budget is a decision made in the same commit, with both numbers in its message.
#
#   ./scripts/check-web-size.sh    # build, unpack, measure, compare: must PASS

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 1.46 MiB.
BUDGET_BYTES=1530921
# 0.70 MiB.
WORKER_BUDGET_BYTES=734003

# Measures the files after the label gzipped, prints a table, and fails when the sum is over the budget.
measure() { # <label> <budget bytes> <file>...
    python3 - "$@" <<'EOF'
import os, sys, zlib

label = sys.argv[1]
budget = int(sys.argv[2])
total = 0
print(label)
print(f"{'file':<44} {'raw bytes':>12} {'gzipped':>12}")
for path in sys.argv[3:]:
    if not os.path.isfile(path):
        sys.exit(f"{path} is missing; the task that makes it did not produce it")
    data = open(path, "rb").read()
    # wbits 31 writes a gzip wrapper, which is what a server sends.
    packer = zlib.compressobj(9, zlib.DEFLATED, 31)
    packed = len(packer.compress(data) + packer.flush())
    total += packed
    print(f"{os.path.basename(path):<44} {len(data):>12} {packed:>12}")
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

measure "Web codec module" "$BUDGET_BYTES" \
    kiteplayer-ffmpeg/build/kiteffmpeg-web/kite.wasm kiteplayer-ffmpeg/build/kiteffmpeg-web/kite.mjs

# Measured from the zip that ships, so what is counted is what a page serves.
worker_dir="$(mktemp -d)"
trap 'rm -rf "$worker_dir"' EXIT
unzip -q kiteplayer/build/worker-zip/kiteplayer-wasm-js-*-web.zip -d "$worker_dir"
measure "Web worker binary" "$WORKER_BUDGET_BYTES" \
    "$worker_dir"/kiteplayer-web-worker.wasm "$worker_dir"/kiteplayer-web-worker*.mjs
