#!/usr/bin/env bash
#
# Holds the web codec module to its size budget.
#
# A web page downloads the codec module that the player pins: kite.wasm and kite.mjs from the
# KiteFFmpeg web zip (see the README's Web section). This script unpacks the pinned zip, measures
# both files gzipped at level 9, and fails when their sum is over the budget below.
#
# Gzipped and not raw, because the raw module answers the wrong question: exporting every binding
# entry point makes the raw module about four times bigger and the gzipped one only about six
# percent bigger. The measurement uses Python's zlib, so macOS and Linux count the same bytes.
#
# The budget is the size of the module that KiteFFmpeg 0.3.0 publishes, 1.42 MiB, which the owner
# chose on #58. It is a ratchet: a new KiteFFmpeg pin that grows the module fails here, and raising
# the budget is a decision made in the same commit as the pin, with both numbers in its message.
#
#   ./scripts/check-web-size.sh    # unpack, measure, compare: must PASS

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 1.42 MiB.
BUDGET_BYTES=1488978

./gradlew --quiet :kiteplayer-ffmpeg:unpackKiteFFmpegWebModule

python3 - "$BUDGET_BYTES" kiteplayer-ffmpeg/build/kiteffmpeg-web/kite.wasm kiteplayer-ffmpeg/build/kiteffmpeg-web/kite.mjs <<'EOF'
import os, sys, zlib

budget = int(sys.argv[1])
total = 0
print(f"{'file':<12} {'raw bytes':>12} {'gzipped':>12}")
for path in sys.argv[2:]:
    if not os.path.isfile(path):
        sys.exit(f"{path} is missing; the unpack task did not produce it")
    data = open(path, "rb").read()
    # wbits 31 writes a gzip wrapper, which is what a server sends.
    packer = zlib.compressobj(9, zlib.DEFLATED, 31)
    packed = len(packer.compress(data) + packer.flush())
    total += packed
    print(f"{os.path.basename(path):<12} {len(data):>12} {packed:>12}")
mib = lambda n: n / (1024 * 1024)
print(f"{'total':<12} {'':>12} {total:>12}  ({mib(total):.3f} MiB, budget {mib(budget):.2f} MiB)")
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as out:
        out.write(f"Web codec module: {total} bytes gzipped ({mib(total):.3f} MiB), budget {budget} bytes ({mib(budget):.2f} MiB).\n")
if total > budget:
    sys.exit(f"The web codec module is {total - budget} bytes over its budget of {budget} bytes gzipped.")
print("PASS")
EOF
