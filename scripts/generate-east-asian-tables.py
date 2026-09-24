#!/usr/bin/env python3
"""
Writes the East Asian decoding tables of kiteplayer-subtitles from the WHATWG Encoding Standard.

kiteplayer-subtitles decodes Shift_JIS, EUC-JP, GBK, Big5 and EUC-KR in pure Kotlin, so a subtitle
file reads the same on every target. Each decoder turns a byte sequence into a pointer, as the
standard's decoder algorithms say, and looks the pointer up in a table. The tables are the
standard's index files. This script packs them into EastAsianTables.kt.

    ./scripts/generate-east-asian-tables.py            # download the pinned files, write the tables
    ./scripts/generate-east-asian-tables.py --from DIR # read the index files from DIR instead

The index files are pinned by commit and by SHA-256 below, so every run writes the same file. A
file whose checksum differs stops the script. Never edit the output by hand.

The packed format
-----------------
A table is one entry per pointer, from pointer 0 to the largest pointer the index lists. An entry
is a code point, or nothing. The entries become a stream of bits, most significant bit first,
written six bits to a character in the alphabet below. Line breaks between characters mean
nothing. "last" is the code point of the last entry that had one, and starts at 0.

    1 g        run: the next g entries are last+1, last+2, ... last+g
    01 x4      step: the next entry is last + 2 + x
    001 x8     near: the next entry is last + x - 128
    0001 x15   ideograph: the next entry is 0x4E00 + x
    00001 x18  any: the next entry is x
    00000 g    hole: the next g entries have no code point, and last stays

xN is an N-bit number. g is an Elias gamma code: k zero bits, a one bit, then k more bits, which
together read as the number 2^k + those k bits.
"""

import argparse
import hashlib
import os
import subprocess
import sys

COMMIT = "a985b62a9b45c17da3e17a9f0a0b4e30c34c4a8a"
SOURCE = f"https://raw.githubusercontent.com/whatwg/encoding/{COMMIT}"

# The index files, with the SHA-256 of each as downloaded from SOURCE.
INDEXES = {
    "jis0208": "341dcde7e8b984e9c7bbf5ed75c8da7c6087d47083a1a2b3ed558bfd5bef9468",
    "jis0212": "6ac698a473822b282ff4f4d4f17873b970612ef9bd232e03941854c09b67c7ee",
    "gb18030": "746b3c55f1a8ec4b90b451f384437a17fd37cd51cd668456a28071a758d10784",
    "gb18030-ranges": "874c6b6f6f74cf7d427ad228d5b41ddd9354fffd92a2259bf429f86e6baa7a1e",
    "big5": "08e24270c8e95d998c994c03f907e972480dc01f58743e078654cc466203c8ff",
    "euc-kr": "89af20dd867c84cefb710b1790229786cfef2bf11916361a210d81b90381e267",
}

# Kotlin names of the pointer tables, in output order.
TABLES = [("jis0208", "JIS0208"), ("jis0212", "JIS0212"), ("gb18030", "GB18030"),
          ("big5", "BIG5"), ("euc-kr", "EUC_KR")]

ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
LINE = 100

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTPUT = os.path.join(ROOT, "kiteplayer-subtitles", "src", "commonMain", "kotlin", "io", "github",
                      "yuroyami", "kiteplayer", "subtitle", "EastAsianTables.kt")


def read_index(name, directory):
    file_name = f"index-{name}.txt"
    if directory:
        with open(os.path.join(directory, file_name), "rb") as f:
            data = f.read()
    else:
        url = f"{SOURCE}/{file_name}"
        data = subprocess.run(["curl", "-sSfL", url], check=True, capture_output=True).stdout
    digest = hashlib.sha256(data).hexdigest()
    if digest != INDEXES[name]:
        sys.exit(f"{file_name}: SHA-256 is {digest}, the pinned file has {INDEXES[name]}")
    pairs = []
    for line in data.decode("utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        pointer, code_point = line.split("\t")[:2]
        pairs.append((int(pointer), int(code_point, 16)))
    return pairs


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, value, count):
        for shift in range(count - 1, -1, -1):
            self.bits.append((value >> shift) & 1)

    def gamma(self, n):
        k = n.bit_length() - 1
        self.put(0, k)
        self.put(n, k + 1)

    def text(self):
        padded = self.bits + [0] * (-len(self.bits) % 6)
        return "".join(ALPHABET[int("".join(map(str, padded[i:i + 6])), 2)]
                       for i in range(0, len(padded), 6))


def pack(entries):
    """Packs a list of code points, 0 for none, into the format the module docstring describes."""
    out = Bits()
    last = 0
    i = 0
    while i < len(entries):
        if entries[i] == 0:
            n = 0
            while i + n < len(entries) and entries[i + n] == 0:
                n += 1
            out.put(0, 5)
            out.gamma(n)
            i += n
            continue
        n = 0
        while i + n < len(entries) and entries[i + n] == last + 1 + n:
            n += 1
        if n > 0:
            out.put(1, 1)
            out.gamma(n)
            last += n
            i += n
            continue
        code_point = entries[i]
        delta = code_point - last
        if 2 <= delta <= 17:
            out.put(0b01, 2)
            out.put(delta - 2, 4)
        elif -128 <= delta <= 127:
            out.put(0b001, 3)
            out.put(delta + 128, 8)
        elif 0x4E00 <= code_point < 0x4E00 + (1 << 15):
            out.put(0b0001, 4)
            out.put(code_point - 0x4E00, 15)
        else:
            assert code_point < (1 << 18), hex(code_point)
            out.put(0b00001, 5)
            out.put(code_point, 18)
        last = code_point
        i += 1
    return out.text()


def unpack(text, size):
    """The reader, as the Kotlin side implements it, to prove the packing before anything is written."""
    values = [ALPHABET.index(c) for c in text if c in ALPHABET]
    stream = iter(bit for v in values for bit in ((v >> s) & 1 for s in range(5, -1, -1)))

    def take(count):
        value = 0
        for _ in range(count):
            value = (value << 1) | next(stream)
        return value

    def gamma():
        zeros = 0
        while next(stream) == 0:
            zeros += 1
        return (1 << zeros) | take(zeros)

    table = [0] * size
    pointer = 0
    last = 0
    while pointer < size:
        if take(1):
            for _ in range(gamma()):
                last += 1
                table[pointer] = last
                pointer += 1
        elif take(1):
            last += 2 + take(4)
            table[pointer] = last
            pointer += 1
        elif take(1):
            last += take(8) - 128
            table[pointer] = last
            pointer += 1
        elif take(1):
            last = 0x4E00 + take(15)
            table[pointer] = last
            pointer += 1
        elif take(1):
            last = take(18)
            table[pointer] = last
            pointer += 1
        else:
            pointer += gamma()
    assert pointer == size, (pointer, size)
    return table


def checksum(table):
    """The same sum the Kotlin test computes over an expanded table."""
    value = 0
    for entry in table:
        value = (value * 31 + entry) & 0x7FFFFFFF
    return value


def raw_string(text):
    return '"""\n' + "\n".join(text[i:i + LINE] for i in range(0, len(text), LINE)) + '\n"""'


HEADER = f"""/*
 * Generated by scripts/generate-east-asian-tables.py. Do not edit this file: change the script and
 * run it again.
 *
 * The tables are the index files of the WHATWG Encoding Standard, https://encoding.spec.whatwg.org/,
 * taken from https://github.com/whatwg/encoding at commit {COMMIT}.
 * Each pointer table is packed as the script describes, and EastAsianText.kt reads it back.
 *
 * Copyright © WHATWG (Apple, Google, Mozilla, Microsoft).
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 *    conditions and the following disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific prior written
 *    permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.github.yuroyami.kiteplayer.subtitle
"""


def main():
    parser = argparse.ArgumentParser(description="Write the East Asian decoding tables.")
    parser.add_argument("--from", dest="directory", help="read the index files from this directory")
    args = parser.parse_args()

    parts = [HEADER]
    for name, kotlin in TABLES:
        pairs = read_index(name, args.directory)
        size = max(pointer for pointer, _ in pairs) + 1
        entries = [0] * size
        for pointer, code_point in pairs:
            entries[pointer] = code_point
        packed = pack(entries)
        if unpack(packed, size) != entries:
            sys.exit(f"index-{name}.txt does not survive packing")
        parts.append(
            f"\n/** index-{name}.txt: {len(pairs)} code points over {size} pointers. */\n"
            f"internal const val {kotlin}_SIZE: Int = {size}\n"
            f"internal const val {kotlin}_ENTRIES: Int = {len(pairs)}\n"
            f"internal const val {kotlin}_CHECKSUM: Int = {checksum(entries)}\n"
            f"internal val {kotlin}_PACKED: String = {raw_string(packed)}\n"
        )

    ranges = read_index("gb18030-ranges", args.directory)
    rows = [", ".join(f"{pointer}, 0x{code_point:04X}" for pointer, code_point in ranges[i:i + 6])
            for i in range(0, len(ranges), 6)]
    parts.append(
        "\n/** index-gb18030-ranges.txt as pointer and code point pairs, for four-byte GB18030. */\n"
        "internal val GB18030_RANGES: IntArray = intArrayOf(\n"
        + "".join(f"    {row},\n" for row in rows)
        + ")\n"
    )

    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(parts))
    print(f"wrote {os.path.relpath(OUTPUT, ROOT)}, {os.path.getsize(OUTPUT)} bytes")


if __name__ == "__main__":
    main()
