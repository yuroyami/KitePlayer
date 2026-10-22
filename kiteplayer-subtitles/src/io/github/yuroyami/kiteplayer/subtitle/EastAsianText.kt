package io.github.yuroyami.kiteplayer.subtitle

/**
 * Decodes the multi-byte East Asian encodings that subtitle files still arrive in.
 *
 * Shift_JIS, EUC-JP, GBK, Big5 and EUC-KR, each decoded the way the WHATWG Encoding Standard says a
 * browser decodes it, from tables generated out of the standard's own index files. The tables are
 * Kotlin, so a file reads the same on every target. Most targets have no decoder of their own for
 * these, and the platforms that have one disagree with each other on some codes.
 *
 * GBK is read with the standard's gb18030 decoder, which also reads the four-byte sequences of
 * GB18030. Big5 includes the Hong Kong supplementary characters. A byte sequence that the encoding
 * cannot read becomes U+FFFD. Each table is expanded on first use, into about 100 KB at most.
 */
public object EastAsianText {

    /**
     * Decodes [bytes] as [encoding], or answers null when [encoding] is not one of the five.
     *
     * The names are `Shift_JIS`, `EUC-JP`, `GBK`, `Big5` and `EUC-KR`, as the standard spells them,
     * in any letter case. The engine's detector names the encoding with one of them.
     */
    public fun decode(bytes: ByteArray, encoding: String): String? = when (encoding.lowercase()) {
        "shift_jis" -> decodeShiftJis(bytes)
        "euc-jp" -> decodeEucJp(bytes)
        "gbk" -> decodeGb18030(bytes)
        "big5" -> decodeBig5(bytes)
        "euc-kr" -> decodeEucKr(bytes)
        else -> null
    }
}

private const val REPLACEMENT = '\uFFFD'

internal val jis0208: IntArray by lazy { expandTable(JIS0208_PACKED, JIS0208_SIZE) }
internal val jis0212: IntArray by lazy { expandTable(JIS0212_PACKED, JIS0212_SIZE) }
internal val gb18030: IntArray by lazy { expandTable(GB18030_PACKED, GB18030_SIZE) }
internal val big5: IntArray by lazy { expandTable(BIG5_PACKED, BIG5_SIZE) }
internal val eucKr: IntArray by lazy { expandTable(EUC_KR_PACKED, EUC_KR_SIZE) }

/** The code point at [pointer], or 0 when the pointer is outside the table or maps to nothing. */
private fun IntArray.at(pointer: Int): Int = if (pointer in indices) this[pointer] else 0

private fun ByteArray.at(index: Int): Int = this[index].toInt() and 0xFF

/** Appends one code point, as a surrogate pair when it lies above the Basic Multilingual Plane. */
private fun StringBuilder.appendUnicode(codePoint: Int) {
    if (codePoint < 0x10000) {
        append(codePoint.toChar())
    } else {
        append((0xD800 + ((codePoint - 0x10000) shr 10)).toChar())
        append((0xDC00 + ((codePoint - 0x10000) and 0x3FF)).toChar())
    }
}

/**
 * Expands one packed table into an array indexed by pointer, where 0 means "no code point".
 *
 * The packed form and its tokens are described in scripts/generate-east-asian-tables.py, which
 * writes it. Characters outside the six-bit alphabet are line breaks and mean nothing.
 */
internal fun expandTable(packed: String, size: Int): IntArray {
    val table = IntArray(size)
    val bits = PackedBits(packed)
    var pointer = 0
    var last = 0
    while (pointer < size) {
        when {
            bits.take(1) == 1 -> repeat(bits.gamma()) {
                last += 1
                table[pointer++] = last
            }
            bits.take(1) == 1 -> {
                last += 2 + bits.take(4)
                table[pointer++] = last
            }
            bits.take(1) == 1 -> {
                last += bits.take(8) - 128
                table[pointer++] = last
            }
            bits.take(1) == 1 -> {
                last = 0x4E00 + bits.take(15)
                table[pointer++] = last
            }
            bits.take(1) == 1 -> {
                last = bits.take(18)
                table[pointer++] = last
            }
            else -> pointer += bits.gamma()
        }
    }
    return table
}

/** Reads the bits of a packed table, most significant first, six to a character. */
private class PackedBits(private val text: String) {
    private var index = 0
    private var sextet = 0
    private var left = 0

    fun take(count: Int): Int {
        var value = 0
        repeat(count) {
            if (left == 0) {
                do {
                    sextet = sextetOf(text[index++])
                } while (sextet < 0)
                left = 6
            }
            left--
            value = (value shl 1) or ((sextet shr left) and 1)
        }
        return value
    }

    /** An Elias gamma code: k zero bits, then k + 1 bits that read as the number. */
    fun gamma(): Int {
        var zeros = 0
        while (take(1) == 0) zeros++
        return (1 shl zeros) or take(zeros)
    }

    private fun sextetOf(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '-' -> 62
        '_' -> 63
        else -> -1
    }
}

/*
 * The five decoders follow the standard's decoder algorithms step for step. One rule they share:
 * when a lead byte is followed by a byte that does not complete it, the pair is one error, and a
 * following ASCII byte is read again on its own, so a broken character never swallows a newline.
 */

private fun decodeShiftJis(bytes: ByteArray): String {
    val table = jis0208
    val out = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val lead = bytes.at(i++)
        when (lead) {
            in 0x00..0x80 -> out.append(lead.toChar())
            in 0xA1..0xDF -> out.append((0xFF61 - 0xA1 + lead).toChar())
            in 0x81..0x9F, in 0xE0..0xFC -> {
                if (i == bytes.size) {
                    out.append(REPLACEMENT)
                    break
                }
                val trail = bytes.at(i++)
                val offset = if (trail < 0x7F) 0x40 else 0x41
                val leadOffset = if (lead < 0xA0) 0x81 else 0xC1
                val pointer = if (trail in 0x40..0x7E || trail in 0x80..0xFC) {
                    (lead - leadOffset) * 188 + trail - offset
                } else {
                    -1
                }
                val codePoint = when (pointer) {
                    // The user-defined area, which the standard maps onto the private use area.
                    in 8836..10715 -> 0xE000 - 8836 + pointer
                    else -> table.at(pointer)
                }
                if (codePoint != 0) {
                    out.appendUnicode(codePoint)
                } else {
                    if (trail < 0x80) i--
                    out.append(REPLACEMENT)
                }
            }
            else -> out.append(REPLACEMENT)
        }
    }
    return out.toString()
}

private fun decodeEucJp(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val lead = bytes.at(i++)
        if (lead < 0x80) {
            out.append(lead.toChar())
            continue
        }
        if (lead != 0x8E && lead != 0x8F && lead !in 0xA1..0xFE) {
            out.append(REPLACEMENT)
            continue
        }
        if (i == bytes.size) {
            out.append(REPLACEMENT)
            break
        }
        var row = lead
        var cell = bytes.at(i++)
        if (lead == 0x8E && cell in 0xA1..0xDF) {
            out.append((0xFF61 - 0xA1 + cell).toChar())
            continue
        }
        var table = jis0208
        if (lead == 0x8F && cell in 0xA1..0xFE) {
            // JIS X 0212 takes three bytes: the prefix, then the row, then the cell.
            if (i == bytes.size) {
                out.append(REPLACEMENT)
                break
            }
            table = jis0212
            row = cell
            cell = bytes.at(i++)
        }
        val codePoint = if (row in 0xA1..0xFE && cell in 0xA1..0xFE) {
            table.at((row - 0xA1) * 94 + cell - 0xA1)
        } else {
            0
        }
        if (codePoint != 0) {
            out.appendUnicode(codePoint)
        } else {
            if (cell < 0x80) i--
            out.append(REPLACEMENT)
        }
    }
    return out.toString()
}

private fun decodeGb18030(bytes: ByteArray): String {
    val table = gb18030
    val out = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val first = bytes.at(i++)
        when (first) {
            in 0x00..0x7F -> out.append(first.toChar())
            0x80 -> out.append('\u20AC')
            0xFF -> out.append(REPLACEMENT)
            else -> {
                if (i == bytes.size) {
                    out.append(REPLACEMENT)
                    break
                }
                val second = bytes.at(i)
                if (second in 0x30..0x39) {
                    // Four bytes: a lead, a digit, a lead-range byte, a digit. A sequence that
                    // breaks off before the end of the file is one error, and its bytes after the
                    // first are read again.
                    if (i + 1 == bytes.size) {
                        out.append(REPLACEMENT)
                        break
                    }
                    val third = bytes.at(i + 1)
                    if (third !in 0x81..0xFE) {
                        out.append(REPLACEMENT)
                        continue
                    }
                    if (i + 2 == bytes.size) {
                        out.append(REPLACEMENT)
                        break
                    }
                    val fourth = bytes.at(i + 2)
                    if (fourth !in 0x30..0x39) {
                        out.append(REPLACEMENT)
                        continue
                    }
                    i += 3
                    val codePoint = gb18030RangesCodePoint(
                        (first - 0x81) * 12600 + (second - 0x30) * 1260 + (third - 0x81) * 10 + fourth - 0x30,
                    )
                    if (codePoint < 0) out.append(REPLACEMENT) else out.appendUnicode(codePoint)
                    continue
                }
                i++
                val offset = if (second < 0x7F) 0x40 else 0x41
                val pointer = if (second in 0x40..0x7E || second in 0x80..0xFE) {
                    (first - 0x81) * 190 + second - offset
                } else {
                    -1
                }
                val codePoint = table.at(pointer)
                if (codePoint != 0) {
                    out.appendUnicode(codePoint)
                } else {
                    if (second < 0x80) i--
                    out.append(REPLACEMENT)
                }
            }
        }
    }
    return out.toString()
}

/** The code point of a four-byte GB18030 pointer, or -1 when the pointer names none. */
private fun gb18030RangesCodePoint(pointer: Int): Int {
    if ((pointer > 39419 && pointer < 189000) || pointer > 1237575) return -1
    if (pointer == 7457) return 0xE7C7
    if (pointer >= 189000) return 0x10000 + pointer - 189000
    // The last range that starts at or before the pointer. The first one starts at pointer 0.
    var start = 0
    var k = 0
    while (k < GB18030_RANGES.size && GB18030_RANGES[k] <= pointer) {
        start = k
        k += 2
    }
    return GB18030_RANGES[start + 1] + pointer - GB18030_RANGES[start]
}

private fun decodeBig5(bytes: ByteArray): String {
    val table = big5
    val out = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val lead = bytes.at(i++)
        when (lead) {
            in 0x00..0x7F -> out.append(lead.toChar())
            in 0x81..0xFE -> {
                if (i == bytes.size) {
                    out.append(REPLACEMENT)
                    break
                }
                val trail = bytes.at(i++)
                val offset = if (trail < 0x7F) 0x40 else 0x62
                val pointer = if (trail in 0x40..0x7E || trail in 0xA1..0xFE) {
                    (lead - 0x81) * 157 + trail - offset
                } else {
                    -1
                }
                // Four codes decode to a letter and a combining mark, which no table entry can hold.
                when (pointer) {
                    1133 -> out.append("\u00CA\u0304")
                    1135 -> out.append("\u00CA\u030C")
                    1164 -> out.append("\u00EA\u0304")
                    1166 -> out.append("\u00EA\u030C")
                    else -> {
                        val codePoint = table.at(pointer)
                        if (codePoint != 0) {
                            out.appendUnicode(codePoint)
                        } else {
                            if (trail < 0x80) i--
                            out.append(REPLACEMENT)
                        }
                    }
                }
            }
            else -> out.append(REPLACEMENT)
        }
    }
    return out.toString()
}

private fun decodeEucKr(bytes: ByteArray): String {
    val table = eucKr
    val out = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val lead = bytes.at(i++)
        when (lead) {
            in 0x00..0x7F -> out.append(lead.toChar())
            in 0x81..0xFE -> {
                if (i == bytes.size) {
                    out.append(REPLACEMENT)
                    break
                }
                val trail = bytes.at(i++)
                val pointer = if (trail in 0x41..0xFE) (lead - 0x81) * 190 + trail - 0x41 else -1
                val codePoint = table.at(pointer)
                if (codePoint != 0) {
                    out.appendUnicode(codePoint)
                } else {
                    if (trail < 0x80) i--
                    out.append(REPLACEMENT)
                }
            }
            else -> out.append(REPLACEMENT)
        }
    }
    return out.toString()
}
