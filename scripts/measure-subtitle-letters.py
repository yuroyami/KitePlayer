#!/usr/bin/env python3
"""
Measures how often each character above ASCII appears in real subtitles of each language, and
writes the tables kiteplayer-core's encoding guess scores a subtitle file against.

A subtitle file with no byte-order mark that is not UTF-8 is in a legacy single-byte table, and
the guess has to say which. It reads the file's high bytes through every table and asks, for each
reading, how likely those characters are in the subtitles of a language that table serves. The
likelihoods come from this script, counted on the OpenSubtitles 2018 corpus that OPUS publishes:

- each character's count, split by whether an ASCII letter touches it on either side, because a
  Latin accent nearly always sits inside a word of ASCII letters and a Cyrillic, Greek, Arabic,
  Hebrew or Thai letter almost never does;
- how many characters are a capital straight after a small letter, because a wrong table turns a
  word into a jumble of capitals and small letters, and real text almost never has one.

A file whose bytes have the shape of a multi-byte East Asian encoding, and that reads as no
language's text one byte at a time, is read through the East Asian tables, and the guess then asks
whether that reading is likelier than the single-byte one. For that it needs the same question
answered for Japanese, Korean and both scripts of Chinese, so the script also counts the
characters of their subtitles: the commonest of them in order, and how often they appear in bands
of that order. Without it, a Thai or Cyrillic line that reads a little unlikely one byte at a time
was taken for Japanese or Chinese, because GBK and Shift_JIS read almost any run of high bytes.

    ./scripts/measure-subtitle-letters.py                  # download, measure, write the table
    ./scripts/measure-subtitle-letters.py --cache DIR      # keep the downloads in DIR
    ./scripts/measure-subtitle-letters.py --held-out DIR   # also write the unmeasured lines to DIR
    ./scripts/measure-subtitle-letters.py --pins           # print the checksums of the downloads

Only the first two million bytes of each language's compressed text are read, which is between
four and six megabytes of dialogue for most languages. Those bytes are pinned by SHA-256 below,
so every run writes the same file, and a download whose checksum differs stops the script. Four
fifths of the lines are measured. The rest are held out, for checking the guess on subtitles the
table never saw. Never edit the output by hand.

Each language is measured in the legacy table its subtitle files were written in, so a character
that table cannot hold is not counted: Romanian is counted with the cedilla letters ş and ţ that
windows-1250 has in place of ș and ț, and Vietnamese with its tones split off as windows-1258
stores them. A letter is counted in lower case wherever its lower case is one character above
ASCII, because a file can be written in capitals. The quotes, dashes and ellipsis of POOLED say
nothing about the language, and the corpus writes its quotes in ASCII besides, so they are not
counted, and the guess gives them one share in every language. English is not measured: its
subtitles hold so few characters above ASCII that what the corpus has of them is mostly stray
bytes, and an English file is read through the other Western languages of windows-1252.
"""

import argparse
import hashlib
import os
import sys
import unicodedata
import urllib.request
import zlib

SOURCE = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/{}.txt.gz"
PREFIX = 2_000_000

# Each language the guess has a table for, with the table its legacy subtitle files use and the
# SHA-256 of the first PREFIX bytes of its corpus file. Belarusian has no corpus file, and English
# is left out, as the description above says.
LANGUAGES = {
    "ar": ("cp1256", "7bd278a55a62462374822c8f4dcda8cede3f88bc28ab8f3d80fcc6acdc6a6c31"),
    "fa": ("cp1256", "7e7c51e47505b31ed7388dcc5f8ca185d7f2d4ad2489e12e79e3cbc44cdc86cc"),
    "ur": ("cp1256", "c0b36948e5c8967c64a343278cc3f7f4cc9260139119621a24f7c902a85259e1"),
    "ru": ("cp1251", "bd58f66b9d88cf164c48cd3430a434709a45e4201b677f5c200c7043a2c8eefc"),
    "uk": ("cp1251", "8b716390ca247aee718d00c991ae64a31f2329070c8ba5590cb80472b543c2fa"),
    "bg": ("cp1251", "45b1b2a66558e07a775fc31ef6042d5bfbbcfd10ff83c789dc79c0696cf6b46d"),
    "sr": ("cp1251", "f14ab72c7ec2983b5b59bdbe48b9e9f67ac5ef6f14be2feb56f1eaca8598ee04"),
    "mk": ("cp1251", "60b18b0b917b571aeb2c10234c61fe6c41d4e2c38b587c5eb2fdb6f295b43b43"),
    "fr": ("cp1252", "a6cbf12d4f24e29a5da6ac0d403da5a09273b4384125a2a9eaee0a09d658fdde"),
    "de": ("cp1252", "6d64727f8af5e4f266e19d5cfeaaedf78fa3f165827600acc463999def00fa78"),
    "es": ("cp1252", "f8af72a495c07d7901549de07d358192a5a9dc27f25d7d7d4e01df2a222dbf4b"),
    "it": ("cp1252", "5b5606d853a980f03a00d7cb0f206c90a2b9f5fa6e0dbdc1650b162b7a0bd13f"),
    "pt": ("cp1252", "762e06d5f8e4f82a072310e88315d9bf3690ec8e2b2afcd55a97e9e03a058bef"),
    "nl": ("cp1252", "af001f2d37cb769513e5d35e6b7a8e4a3b0aec5a7b1bc0eea126f9b626c63926"),
    "sv": ("cp1252", "475420cb9876910f43017021522c2316c2f05713553b78c64a3177224c71e57d"),
    "da": ("cp1252", "5c5a72e478242f9b14c5f1b41d506ff72bf714887d91acb4451e73e6aa9f1c2e"),
    "no": ("cp1252", "1282c7578117902dfbc2b9eaa867138a4b14c4ecf65dfb7331b206cd96c3530e"),
    "fi": ("cp1252", "96cbf151c92173c3de7a89b53889036b13dd51bebe5308753fc6692c088faf79"),
    "is": ("cp1252", "865cb6ee7150e629343d46aecc22d5882fb06180b3b10f7e429a34d150879e8e"),
    "sq": ("cp1252", "427115b8fbf93614d8ad9cb5c9d6ce0bf39d02d059ff053789174cc268bc1100"),
    "ca": ("cp1252", "cd66750a752b5ccf0f3b85c352474b01fe108983119a1b9939b5890944dcffd8"),
    "el": ("cp1253", "f4aaf9e9fcac8cff55e324135c001a63dde589a39377c412a622316eb04e1482"),
    "tr": ("cp1254", "7c530189e55acc5dc670bfac3d07f981e247eee004c9d70592b85d99ef8f778d"),
    "he": ("cp1255", "db5df8df546a5d4a64f4f964fabf63587826eae4c3a5c02c90fdf8eeb16185a9"),
    "pl": ("cp1250", "fbec497aeec2933d9244a52b70c48f868bc50602d4e97bebc3c943ef579d418d"),
    "cs": ("cp1250", "6ea548398c7164b3b25e8cc57f0ba328a5d13a19d2e0a2313c610fd899b03543"),
    "sk": ("cp1250", "2cb3b04d67a759899caa56dba9e8bf7607b5aa83c592009bca2e7e484c527e8b"),
    "hu": ("cp1250", "76e20705324613962b6bb864b3930d5efb7151d03cdc5d625fbda38f82708781"),
    "ro": ("cp1250", "c393cf63040a11103a8be4206bb740a1efffb8a3aea1fcb0161bab08319ec03d"),
    "hr": ("cp1250", "eb5bef99fb90e21569716a8076a0366c3c83fef07ba30d471bcd7c6acc9ac759"),
    "sl": ("cp1250", "8532aa756b89434b1c6ccddc87ad050920a0a9a83e8799a5b9c22f925a1b7299"),
    "lt": ("cp1257", "48a438f6ceafdedd157db00ec21c2ed78379901aaa4b528084ca3e3399a2e4a3"),
    "lv": ("cp1257", "ec65f49ee0b499781112c59edb3bd767355fd4b8eb74688488e22e3afb9aad5b"),
    "et": ("cp1257", "ad8421179347c04c2153d22ae594649032bb0431924979b5df6087944fcc8ee0"),
    "vi": ("cp1258", "e81db6f4f639d2aae55b1da2662ccd0bee35ea45140e61826cff1447a3336300"),
    "th": ("cp874", "8dd2dfb9dfc271ae65bf09d1d6648f39abc2e384a8a05c4a6674ef5e2d8c6b20"),
}

# The East Asian languages, each with the language tag the guess knows it by, the encoding its
# legacy subtitle files use, whose characters alone are counted, and the pinned SHA-256 as above.
EAST_ASIAN = {
    "ja": ("ja", "cp932", "8e971f5f265dd866d735990b4e139eb3060cbb45d89ca0ef3a919b99c1d3e0df"),
    "ko": ("ko", "cp949", "f8978757b745a911b2aa58c0b8b14f09742fe91f0833586ff6b502302dc13df3"),
    "zh_cn": ("zh-Hans", "gbk", "e1f71871a3ed3ae65f2877bd286a19b65c9851f240d3a0847c97327ccd963215"),
    "zh_tw": ("zh-Hant", "big5hkscs", "b0ac4507dfdfae731a9251aeebf697e33f3cde38e9e7bcfad2fdf580ffd99cd5"),
}

# How many bands of the East Asian order are written, each twice the size of the one before, so
# the 1023 commonest characters of each language are kept. They cover between nine tenths of
# traditional Chinese subtitles and all but a third of a percent of Korean ones.
BANDS = 10

# Typographic punctuation every table puts above ASCII, measured once for all languages.
POOLED = " «»–—‘’‚“”„•…‹›"

# A character seen fewer times than this is left out, as the stray bytes of a badly converted
# file rather than a part of the language.
MIN_COUNT = 3

# Where the guess finds the table.
OUTPUT = "kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SubtitleLetters.kt"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The tone marks windows-1258 stores apart from the letter they sit on.
TONES = "̣̀́̃̉"


def fetch(language, cache):
    path = os.path.join(cache, f"{language}.prefix.gz") if cache else None
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    request = urllib.request.Request(
        SOURCE.format(language),
        headers={"Range": f"bytes=0-{PREFIX - 1}", "User-Agent": "KitePlayer measure-subtitle-letters"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    if path:
        os.makedirs(cache, exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    return data


def lines_of(data):
    """The complete lines a gzip prefix holds. The last one may be cut, so it is dropped."""
    text = zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(data).decode("utf-8", "replace")
    lines = text.split("\n")
    return [line for line in lines[:-1] if line.strip()]


def to_1258(text):
    """Vietnamese as windows-1258 holds it: each tone mark the code page has no letter for, apart."""
    out = []
    for ch in unicodedata.normalize("NFC", text):
        try:
            ch.encode("cp1258")
            out.append(ch)
            continue
        except UnicodeEncodeError:
            pass
        decomposed = unicodedata.normalize("NFD", ch)
        base, marks = decomposed[0], decomposed[1:]
        tones = "".join(m for m in marks if m in TONES)
        rest = "".join(m for m in marks if m not in TONES)
        out.append(unicodedata.normalize("NFC", base + rest) + tones)
    return "".join(out)


def legacy(language, text):
    text = unicodedata.normalize("NFC", text)
    if language == "ro":
        text = text.translate(str.maketrans("șțȘȚ", "şţŞŢ"))
    if language == "vi":
        text = to_1258(text)
    return text


def fold(ch):
    """The character a letter is counted as. The guess folds case the same way."""
    lower = ch.lower()
    return lower if len(lower) == 1 and ord(lower) >= 0x80 else ch


def is_ascii_letter(ch):
    return "a" <= ch <= "z" or "A" <= ch <= "Z"


def count(language, encoding, lines):
    """
    How often each character above ASCII appears, apart from ASCII letters and touching one, and how
    many of them are a capital straight after a small letter.
    """
    counts = {}
    capitals = 0
    for line in lines:
        text = legacy(language, line)
        for i, ch in enumerate(text):
            if ord(ch) < 0x80:
                continue
            try:
                encoded = ch.encode(encoding)
            except UnicodeEncodeError:
                continue
            if len(encoded) != 1 or encoded[0] < 0x80:
                continue
            touching = (i > 0 and is_ascii_letter(text[i - 1])) or (i + 1 < len(text) and is_ascii_letter(text[i + 1]))
            pair = counts.setdefault(fold(ch), [0, 0])
            pair[1 if touching else 0] += 1
            if ch.isupper() and i > 0 and text[i - 1].islower():
                capitals += 1
    return counts, capitals


def count_east_asian(encoding, lines):
    """How often each character above ASCII that the encoding holds appears, commonest first."""
    counts = {}
    for line in lines:
        for ch in unicodedata.normalize("NFC", line):
            # A character past the Basic Multilingual Plane is two in a Kotlin string, and none is
            # common enough to make the bands.
            if ord(ch) < 0x80 or ord(ch) > 0xFFFF:
                continue
            try:
                ch.encode(encoding)
            except UnicodeEncodeError:
                continue
            counts[ch] = counts.get(ch, 0) + 1
    return sorted(counts.items(), key=lambda e: (-e[1], e[0]))


def kotlin_char(c):
    printable = c.isprintable() and not unicodedata.category(c).startswith("M") and c not in '"\\$'
    return c if printable else f"\\u{ord(c):04X}"


def measured_lines(corpus, encoding, pin, args):
    """
    The lines of a corpus prefix and how many of them are measured, or None when only the checksums
    are wanted.
    """
    data = fetch(corpus, args.cache)
    digest = hashlib.sha256(data).hexdigest()
    if args.pins:
        print(f'    "{corpus}": ("{encoding}", "{digest}"),')
        return None
    if digest != pin:
        sys.exit(f"{corpus}: the corpus prefix has SHA-256 {digest}, not the pinned {pin}")
    lines = lines_of(data)
    return lines, len(lines) * 4 // 5


def hold_out(directory, corpus, lines):
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, f"{corpus}.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cache", help="keep the downloaded prefixes in this directory")
    parser.add_argument("--held-out", help="write each language's unmeasured lines to this directory")
    parser.add_argument("--pins", action="store_true", help="print the checksums instead of checking them")
    args = parser.parse_args()

    rows = []
    capital_rows = []
    for language, (encoding, pin) in LANGUAGES.items():
        lines = measured_lines(language, encoding, pin, args)
        if lines is None:
            continue
        lines, cut = lines
        counts, capitals = count(language, encoding, lines[:cut])
        entries = sorted(
            ((c, apart, touching) for c, (apart, touching) in counts.items()
             if c not in POOLED and apart + touching >= MIN_COUNT),
            key=lambda e: (-(e[1] + e[2]), e[0]),
        )
        rows.append(f"    // {cut} lines, {sum(a + t for _, a, t in entries)} characters.")
        rows.append(f'    "{language}" to "{" ".join(f"{kotlin_char(c)}{a}/{t}" for c, a, t in entries)}",')
        capital_rows.append(f'    "{language}" to {capitals},')
        if args.held_out:
            hold_out(args.held_out, language, [legacy(language, line) for line in lines[cut:]])
    east_rows = []
    band_rows = []
    for corpus, (language, encoding, pin) in EAST_ASIAN.items():
        lines = measured_lines(corpus, encoding, pin, args)
        if lines is None:
            continue
        lines, cut = lines
        counts = count_east_asian(encoding, lines[:cut])
        kept = (1 << BANDS) - 1
        bands = [sum(n for _, n in counts[(1 << b) - 1:(1 << (b + 1)) - 1]) for b in range(BANDS)]
        bands.append(sum(n for _, n in counts[kept:]))
        east_rows.append(f"    // {cut} lines, {sum(n for _, n in counts)} characters, {len(counts)} of them different.")
        east_rows.append(f'    "{language}" to "{"".join(kotlin_char(c) for c, _ in counts[:kept])}",')
        band_rows.append(f'    "{language}" to intArrayOf({", ".join(str(n) for n in bands)}),')
        if args.held_out:
            hold_out(args.held_out, corpus, lines[cut:])
    if args.pins:
        return

    out = [
        "package io.github.yuroyami.kiteplayer.internal",
        "",
        "// Generated by scripts/measure-subtitle-letters.py from the OpenSubtitles 2018 corpus of OPUS.",
        "// Never edit by hand; run the script.",
        "",
        "/**",
        " * How often each character above ASCII appears in subtitles of each language, as that character",
        " * followed by two counts: apart from any ASCII letter, then touching one on either side. A letter",
        " * is counted in lower case. The characters of [POOLED_PUNCTUATION] are not counted.",
        " */",
        "internal val SUBTITLE_LETTERS: Map<String, String> = mapOf(",
        *rows,
        ")",
        "",
        "/**",
        " * How many of the characters [SUBTITLE_LETTERS] counts for each language are a capital straight",
        " * after a small letter, as in the middle of a word.",
        " */",
        "internal val SUBTITLE_CAPITALS: Map<String, Int> = mapOf(",
        *capital_rows,
        ")",
        "",
        "/** The quotes, dashes and ellipsis, which say nothing about the language a file is in. */",
        f'internal const val POOLED_PUNCTUATION: String = "{"".join(kotlin_char(c) for c in POOLED)}"',
        "",
        "/**",
        f" * The {(1 << BANDS) - 1} characters above ASCII that subtitles of each East Asian language use most,",
        " * commonest first.",
        " */",
        "internal val EAST_ASIAN_CHARACTERS: Map<String, String> = mapOf(",
        *east_rows,
        ")",
        "",
        "/**",
        " * How many times the characters of [EAST_ASIAN_CHARACTERS] appear, in bands of their order that",
        " * double in size: the commonest one, the next two, the next four, and so on. The last number",
        " * counts every other character.",
        " */",
        "internal val EAST_ASIAN_BANDS: Map<String, IntArray> = mapOf(",
        *band_rows,
        ")",
        "",
    ]
    with open(os.path.join(ROOT, OUTPUT), "w", encoding="utf-8") as f:
        f.write("\n".join(out))


if __name__ == "__main__":
    main()
