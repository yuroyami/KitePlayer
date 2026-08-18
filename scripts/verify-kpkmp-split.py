#!/usr/bin/env python3
"""Proves the KPKMP split lost nothing.

The pilot document was 18,546 lines in one file and agents measurably stopped reading it: a
2026-08-18 audit found six register rows listed as open that the code had already closed, and the
agent auditing them checked twelve of sixty items and asserted the rest. It was split by TENSE on
that date, into one file that is always read and one that never is. The split was mechanical, every
line moved verbatim, and this script is what makes that claim checkable rather than a promise in a
header.

It compares the two files against the last single-file version in git history and fails if any
non-blank line, or any register id, stopped resolving.

    python3 scripts/verify-kpkmp-split.py [<git-ref-of-single-file-version>]

Exit 0 means nothing was lost. Anything else names what went missing.
"""
import re
import subprocess
import sys
from pathlib import Path

FILES = [
    "KPKMP-FUTURE.md",  # what is true and what is ahead; read every session
    "KPKMP-PAST.md",    # what already happened; read almost never
]
# The families the registers actually use. Kept explicit rather than clever: a regex that matches
# too much would hide a loss by matching prose.
ID_PATTERN = re.compile(
    r"\b(?:SOL-[A-Z]+\d+|F-[A-Z]+\d+|X-\d+|W-\d+|B\d-\d+|I-\d+|KV-\d+|KD-\d+"
    r"|PAR-\d+|KC-[A-Z0-9]+|KP-[A-Z]+|AGW-\d+)\b"
)
DEFAULT_REF = "4de779b:KPKMP.md"
ALLOWED_EDITS = Path(__file__).with_name("kpkmp-split-allowed-edits.txt")


def allowed_edits() -> set:
    """Lines the split deliberately changed, so a real loss stays visible among them."""
    if not ALLOWED_EDITS.is_file():
        return set()
    # "= " prefixed lines are entries; the rest is commentary. The prefix matters: some allowed
    # lines are markdown headings that start with "#", and a plain comment rule would swallow them.
    return {
        line[2:].strip()
        for line in ALLOWED_EDITS.read_text().splitlines()
        if line.startswith("= ")
    }


def read_original(ref: str) -> str:
    try:
        return subprocess.run(
            ["git", "show", ref], capture_output=True, text=True, check=True
        ).stdout
    except subprocess.CalledProcessError as failure:
        sys.exit(f"cannot read {ref}: {failure.stderr.strip()}")


def main() -> int:
    ref = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REF
    original = read_original(ref)

    missing_files = [name for name in FILES if not Path(name).is_file()]
    if missing_files:
        sys.exit(f"missing: {', '.join(missing_files)}")
    union = "\n".join(Path(name).read_text() for name in FILES)

    # 1. Every non-blank line survives. Compared stripped, because only content is promised;
    #    a moved line may sit at a different indent inside its new file.
    union_lines = {line.strip() for line in union.splitlines() if line.strip()}
    edited = allowed_edits()
    lost = [
        line.strip()
        for line in original.splitlines()
        if line.strip() and line.strip() not in union_lines and line.strip() not in edited
    ]

    # 2. Every register id still resolves. This is the one that matters for the registers:
    #    a row whose id vanished is a row nobody can look up again.
    original_ids = set(ID_PATTERN.findall(original))
    union_ids = set(ID_PATTERN.findall(union))
    lost_ids = sorted(original_ids - union_ids)

    print(f"reference:      {ref}")
    print(f"original lines: {len(original.splitlines())}")
    print(f"union lines:    {len(union.splitlines())} across {len(FILES)} files")
    print(f"register ids:   {len(original_ids)} before, {len(union_ids)} after")
    print(f"lines edited:   {len(edited)} (allowed, see {ALLOWED_EDITS.name})")
    print(f"lines lost:     {len(lost)}")
    print(f"ids lost:       {len(lost_ids)}")

    if lost:
        print("\nLOST LINES (first 20):")
        for line in lost[:20]:
            print(f"  {line[:110]}")
    if lost_ids:
        print(f"\nLOST IDS: {', '.join(lost_ids)}")

    if lost or lost_ids:
        print("\nFAILED: the split lost something. Restore from the reference and redo it.")
        return 1
    print("\nOK: every line and every register id survived the split.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
