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

    python3 scripts/verify-kpkmp-split.py [<before-ref>] [<after-ref>]

Exit 0 means nothing was lost. Anything else names what went missing.

**This is a proof about the MIGRATION, not an invariant on the files.** Both sides default to git
refs, and the after-side defaults to the commit the split finished at rather than to the working
tree. That distinction is the whole point and it was got wrong first: reading the working tree made
every later edit to either document report as a LOST LINE, so the first ordinary day of work after
the split turned this red for updating a register row exactly as RULE ONE demands. A check that
goes red for correct work is a check people learn to skip, which is the disease the split was
performed to cure. Pinned to two commits, it answers its one question, stays answerable forever,
and never argues with the documents being alive.

Pass an after-ref of `WORKTREE` to compare against the files on disk, which is what a future
document surgery would use while it is in progress.
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
DEFAULT_BEFORE_REF = "4de779b:KPKMP.md"
# The commit the split finished at: the two files plus the deliberate standalone edits, and nothing
# from any later day's work.
DEFAULT_AFTER_REF = "0521ebf"
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


def read_side(name: str, after_ref: str) -> str:
    """One half of the split, from a commit by default and from disk only when asked."""
    if after_ref == "WORKTREE":
        if not Path(name).is_file():
            sys.exit(f"missing: {name}")
        return Path(name).read_text()
    return read_original(f"{after_ref}:{name}")


def main() -> int:
    before_ref = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BEFORE_REF
    after_ref = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_AFTER_REF
    original = read_original(before_ref)
    union = "\n".join(read_side(name, after_ref) for name in FILES)

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

    print(f"before:         {before_ref}")
    print(f"after:          {after_ref}")
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
