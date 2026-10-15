#!/usr/bin/env python3
"""Gives a Matroska file that ffmpeg made a segment UID and the editions a test needs (#501).

ffmpeg writes chapters but never an ordered edition, a hidden or disabled chapter, or a chapter that
plays a range of another file, so the fixtures of ordered editions and linked files cannot be made
with it alone. This rewrites the file: it sets the SegmentUUID in Info, replaces the Chapters element
with the editions it is given, and moves the seek head and every cue position to match.

    mkv-editions.py IN OUT --uid HEX32 [--editions JSON]

JSON is a list of editions, each with "uid", "name", "default", "hidden", "ordered" and "chapters";
each chapter has "uid", "name", "start" and "end" in seconds, and may have "segment" (the HEX32 UID
of the file it plays), "edition" (the edition UID of that file to play whole), "hidden" and
"enabled". The input must be written without CRC elements (ffmpeg's -write_crc32 0), because a
rewritten element would no longer match its checksum.
"""

import argparse
import json
import sys

EBML_HEADER = 0x1A45DFA3
SEGMENT = 0x18538067
SEEK_HEAD = 0x114D9B74
SEEK = 0x4DBB
SEEK_ID = 0x53AB
SEEK_POSITION = 0x53AC
VOID = 0xEC
CRC32 = 0xBF
INFO = 0x1549A966
SEGMENT_UUID = 0x73A4
TRACKS = 0x1654AE6B
CHAPTERS = 0x1043A770
CLUSTER = 0x1F43B675
CUES = 0x1C53BB6B
CUE_POINT = 0xBB
CUE_TRACK_POSITIONS = 0xB7
CUE_CLUSTER_POSITION = 0xF1
TAGS = 0x1254C367
ATTACHMENTS = 0x1941A469

EDITION_ENTRY = 0x45B9
EDITION_UID = 0x45BC
EDITION_FLAG_HIDDEN = 0x45BD
EDITION_FLAG_DEFAULT = 0x45DB
EDITION_FLAG_ORDERED = 0x45DD
EDITION_DISPLAY = 0x4520
EDITION_STRING = 0x4521
CHAPTER_ATOM = 0xB6
CHAPTER_UID = 0x73C4
CHAPTER_TIME_START = 0x91
CHAPTER_TIME_END = 0x92
CHAPTER_FLAG_HIDDEN = 0x98
CHAPTER_FLAG_ENABLED = 0x4598
CHAPTER_SEGMENT_UUID = 0x6E67
CHAPTER_SEGMENT_EDITION_UID = 0x6EBC
CHAPTER_DISPLAY = 0x80
CHAP_STRING = 0x85
CHAP_LANGUAGE = 0x437C

# Elements whose position the seek head names.
INDEXED = (INFO, TRACKS, CHAPTERS, TAGS, ATTACHMENTS, CUES)


def read_id(data, at):
    first = data[at]
    length = 1
    while length <= 4 and not first & (0x80 >> (length - 1)):
        length += 1
    if length > 4:
        raise ValueError(f"bad element id at {at}")
    return int.from_bytes(data[at:at + length], "big"), at + length


def read_size(data, at):
    first = data[at]
    length = 1
    while length <= 8 and not first & (0x80 >> (length - 1)):
        length += 1
    if length > 8:
        raise ValueError(f"bad element size at {at}")
    value = first & (0xFF >> length)
    for byte in data[at + 1:at + length]:
        value = (value << 8) | byte
    if value == (1 << (7 * length)) - 1:
        raise ValueError(f"an element of unknown size at {at}; the rewrite needs a finished file")
    return value, at + length


def children(data, start, end):
    """The elements in data[start:end] as (id, element start, data start, data end)."""
    at = start
    while at < end:
        element, after_id = read_id(data, at)
        size, body = read_size(data, after_id)
        yield element, at, body, body + size
        at = body + size


def encode_id(element):
    return element.to_bytes((element.bit_length() + 7) // 8, "big")


def encode_size(size, width=None):
    if width is None:
        width = 1
        while size >= (1 << (7 * width)) - 1:
            width += 1
    return ((1 << (7 * width)) | size).to_bytes(width, "big")


def element(element_id, body, size_width=None):
    return encode_id(element_id) + encode_size(len(body), size_width) + body


def uint(element_id, value, width=None):
    if width is None:
        width = max(1, (value.bit_length() + 7) // 8)
    return element(element_id, value.to_bytes(width, "big"))


def text(element_id, value):
    return element(element_id, value.encode("utf-8"))


def chapters_element(editions):
    entries = b""
    for edition in editions:
        body = uint(EDITION_UID, edition["uid"])
        body += uint(EDITION_FLAG_HIDDEN, 1 if edition.get("hidden") else 0)
        body += uint(EDITION_FLAG_DEFAULT, 1 if edition.get("default") else 0)
        body += uint(EDITION_FLAG_ORDERED, 1 if edition.get("ordered") else 0)
        if edition.get("name"):
            body += element(EDITION_DISPLAY, text(EDITION_STRING, edition["name"]))
        for chapter in edition["chapters"]:
            atom = uint(CHAPTER_UID, chapter["uid"])
            atom += uint(CHAPTER_TIME_START, round(chapter["start"] * 1e9))
            if chapter.get("end") is not None:
                atom += uint(CHAPTER_TIME_END, round(chapter["end"] * 1e9))
            atom += uint(CHAPTER_FLAG_HIDDEN, 1 if chapter.get("hidden") else 0)
            atom += uint(CHAPTER_FLAG_ENABLED, 0 if chapter.get("enabled") is False else 1)
            if chapter.get("segment"):
                atom += element(CHAPTER_SEGMENT_UUID, bytes.fromhex(chapter["segment"]))
            if chapter.get("edition") is not None:
                atom += uint(CHAPTER_SEGMENT_EDITION_UID, chapter["edition"])
            if chapter.get("name"):
                atom += element(CHAPTER_DISPLAY, text(CHAP_STRING, chapter["name"]) + text(CHAP_LANGUAGE, "eng"))
            body += element(CHAPTER_ATOM, atom)
        entries += element(EDITION_ENTRY, body)
    return element(CHAPTERS, entries)


def info_with_uid(data, start, end, uid):
    body = b""
    for child, at, _, child_end in children(data, start, end):
        if child in (SEGMENT_UUID, CRC32, VOID):
            continue
        body += data[at:child_end]
    return element(INFO, element(SEGMENT_UUID, uid) + body)


def cues_moved(data, start, end, moved):
    """Cues with every CueClusterPosition taken through [moved], each written eight bytes wide."""

    def rewrite(region_start, region_end, depth):
        out = b""
        for child, at, body, child_end in children(data, region_start, region_end):
            if child == CUE_CLUSTER_POSITION:
                old = int.from_bytes(data[body:child_end], "big")
                if old not in moved:
                    raise ValueError(f"a cue points at {old}, where no cluster starts")
                out += uint(CUE_CLUSTER_POSITION, moved[old], width=8)
            elif child in (CUE_POINT, CUE_TRACK_POSITIONS) and depth < 2:
                out += element(child, rewrite(body, child_end, depth + 1))
            elif child in (CRC32, VOID):
                continue
            else:
                out += data[at:child_end]
        return out

    return element(CUES, rewrite(start, end, 0))


def seek_head(positions):
    body = b""
    for element_id, position in positions:
        entry = element(SEEK_ID, encode_id(element_id)) + uint(SEEK_POSITION, position, width=8)
        body += element(SEEK, entry)
    return element(SEEK_HEAD, body)


def rewrite(data, uid, editions):
    out = b""
    segment = None
    for top, at, body, end in children(data, 0, len(data)):
        if top == EBML_HEADER:
            out += data[at:end]
        elif top == SEGMENT:
            segment = (body, end)
    if segment is None:
        raise ValueError("no Segment element")
    seg_start, seg_end = segment

    # The segment's children in their order, with Chapters replaced, or placed after Tracks.
    parts = []
    for child, at, body, end in children(data, seg_start, seg_end):
        if child in (SEEK_HEAD, VOID, CRC32, CHAPTERS):
            continue
        if child == INFO:
            parts.append((INFO, info_with_uid(data, body, end, uid), None))
        elif child == CUES:
            parts.append((CUES, None, (body, end)))
        else:
            parts.append((child, data[at:end], at - seg_start))
        if child == TRACKS and editions is not None:
            parts.append((CHAPTERS, chapters_element(editions), None))

    indexed = [element_id for element_id, _, _ in parts if element_id in INDEXED]
    head_size = len(seek_head([(element_id, 0) for element_id in indexed]))

    # Lay the segment out once to learn where each cluster moved; the cues come after the clusters
    # in an ffmpeg file, so their own size moves nothing that a cue points at.
    moved = {}
    positions = []
    offset = head_size
    for element_id, encoded, extra in parts:
        if element_id == CLUSTER:
            moved[extra] = offset
        if element_id == CUES:
            encoded = b""
        offset += len(encoded)
    if any(element_id == CUES for element_id, _, _ in parts):
        last_cluster = max((i for i, (e, _, _) in enumerate(parts) if e == CLUSTER), default=-1)
        first_cues = min(i for i, (e, _, _) in enumerate(parts) if e == CUES)
        if first_cues < last_cluster:
            raise ValueError("the cues come before a cluster; this rewrite expects them after")

    body = b""
    offset = head_size
    for element_id, encoded, extra in parts:
        if element_id == CUES:
            encoded = cues_moved(data, extra[0], extra[1], moved)
        if element_id in INDEXED:
            positions.append((element_id, offset))
        body += encoded
        offset += len(encoded)
    head = seek_head(positions)
    assert len(head) == head_size
    return out + element(SEGMENT, head + body, size_width=8)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("source")
    parser.add_argument("target")
    parser.add_argument("--uid", required=True, help="the segment UID, 32 hexadecimal digits")
    parser.add_argument("--editions", help="the editions as JSON; the file keeps no chapters when absent")
    args = parser.parse_args()
    uid = bytes.fromhex(args.uid)
    if len(uid) != 16:
        sys.exit("--uid must be 32 hexadecimal digits")
    editions = json.loads(args.editions) if args.editions else None
    with open(args.source, "rb") as source:
        data = source.read()
    result = rewrite(data, uid, editions)
    with open(args.target, "wb") as target:
        target.write(result)


if __name__ == "__main__":
    main()
