#!/usr/bin/env python3
"""Reproduce the authored font embedded in LibassColorTestFont.kt, using only Python's stdlib.

The fixture has empty .notdef/space glyphs and one four-point rectangle for printable ASCII.
It only supplies font selection and metrics to ASS vector drawings. It is not a text/shaping font.
No glyph outline, metric, name table or font binary was imported from another font.
Authored test fixture, licensed under the repository Apache-2.0 license.

OpenType table layout: https://learn.microsoft.com/en-us/typography/opentype/spec/otff
Run this file to print the hex fixture. Pass a path to save the same bytes for a direct module probe.
The Gradle build reads the checked-in Kotlin bytes and never runs this script.
"""
import hashlib
import struct
import sys
from pathlib import Path


def u16(*values):
    return struct.pack('>' + 'H' * len(values), *values)


def i16(*values):
    return struct.pack('>' + 'h' * len(values), *values)


def u32(*values):
    return struct.pack('>' + 'I' * len(values), *values)


def padded(data):
    return data + b'\0' * (-len(data) % 4)


def checksum(data):
    data = padded(data)
    return sum(struct.unpack('>' + 'I' * (len(data) // 4), data)) & 0xffffffff


def font_bytes():
    # Font units and all coordinates are chosen for this fixture, independent of system fonts.
    head = (u32(0x10000, 0x10000, 0, 0x5f0f3cf5) + u16(3, 1000) + b'\0' * 16
            + i16(0, 0, 500, 700) + u16(0, 8) + i16(2, 0, 0))
    hhea = (u32(0x10000) + i16(800, -200, 0) + u16(600)
            + i16(0, 100, 500, 1, 0, 0, 0, 0, 0, 0, 0) + u16(3))
    maxp = u32(0x10000) + u16(3, 4, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
    os2 = (u16(0) + i16(600) + u16(400, 5, 0)
           + i16(650, 600, 0, 75, 650, 600, 0, 350, 50, 300, 0)
           + bytes([2, 11, 5, 9, 2, 2, 2, 2, 2, 4])
           + u32(1, 0, 0, 0) + b'KITE' + u16(0x40, 32, 126)
           + i16(800, -200, 0) + u16(800, 200))
    # Two empty glyphs followed by the rectangle (0,0), (500,0), (500,700), (0,700).
    glyf = i16(1, 0, 0, 500, 700) + u16(3, 0) + bytes([1, 1, 1, 1]) + i16(0, 500, 0, -500, 0, 0, 700, 0)
    loca = u16(0, 0, 0, len(glyf) // 2)
    hmtx = u16(600, 0, 500, 0, 600, 0)
    glyphs = u16(1, *([2] * 94))
    cmap4 = (u16(4, 32 + len(glyphs), 0, 4, 4, 1, 0) + u16(126, 0xffff, 0)
             + u16(32, 0xffff) + i16(0, 1) + u16(4, 0) + glyphs)
    cmap = u16(0, 1, 3, 1) + u32(12) + cmap4
    strings = {
        0: 'Authored for the KitePlayer vector color test',
        1: 'KiteColorBox',
        2: 'Regular',
        3: 'KiteColorBox-1',
        4: 'KiteColorBox Regular',
        5: 'Version 1.000',
        6: 'KiteColorBox-Regular',
    }
    records, storage = bytearray(), bytearray()
    for key, value in strings.items():
        encoded = value.encode('utf-16-be')
        records.extend(u16(3, 1, 0x409, key, len(encoded), len(storage)))
        storage.extend(encoded)
    name = u16(0, len(strings), 6 + len(records)) + records + storage
    post = u32(0x30000, 0) + i16(-75, 50) + u32(0, 0, 0, 0, 0)
    tables = {'OS/2': os2, 'cmap': cmap, 'glyf': glyf, 'head': head, 'hhea': hhea,
              'hmtx': hmtx, 'loca': loca, 'maxp': maxp, 'name': name, 'post': post}
    assert (len(head), len(hhea), len(maxp), len(os2), len(post)) == (54, 36, 32, 78, 32)
    count = len(tables)
    power = 1 << (count.bit_length() - 1)
    directory = bytearray(u32(0x10000) + u16(count, power * 16, power.bit_length() - 1, (count - power) * 16))
    offset = 12 + 16 * count
    payload = bytearray()
    head_offset = None
    for tag, data in sorted(tables.items()):
        directory.extend(tag.encode('ascii') + u32(checksum(data), offset, len(data)))
        if tag == 'head':
            head_offset = offset
        payload.extend(padded(data))
        offset += len(padded(data))
    result = directory + payload
    adjustment = (0xb1b0afba - checksum(result)) & 0xffffffff
    result[head_offset + 8:head_offset + 12] = u32(adjustment)
    assert checksum(result) == 0xb1b0afba
    return bytes(result)


if __name__ == '__main__':
    data = font_bytes()
    if len(sys.argv) > 1:
        Path(sys.argv[1]).write_bytes(data)
    print(data.hex())
    print(f'{len(data)} bytes SHA256 {hashlib.sha256(data).hexdigest()}', file=sys.stderr)
