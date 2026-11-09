#!/usr/bin/env python3
"""Fails when a KDoc block is followed by another one with only white space between (#921, #965).

Kotlin keeps only the last block before a declaration, so the first documents nothing. Move it onto
the declaration it was written for, merge it with the block after it, or make it a plain comment.
"""
import re
import subprocess
import sys

PAIR = re.compile(r'/\*\*(?:(?!\*/).)*\*/\s*/\*\*', re.S)
found = []
for path in subprocess.run(['git', 'ls-files', '*.kt', '*.kts'], capture_output=True, text=True, check=True).stdout.split():
    text = open(path, encoding='utf-8').read()
    for match in PAIR.finditer(text):
        found.append(f"{path}:{text.count(chr(10), 0, match.start()) + 1}")
if found:
    print("A KDoc block is followed by another one, so Kotlin drops it:")
    print("\n".join(found))
    sys.exit(1)
print("No lost KDoc block.")
