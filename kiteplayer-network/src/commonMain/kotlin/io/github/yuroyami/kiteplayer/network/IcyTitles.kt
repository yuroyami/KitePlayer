package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.TextEncodings

/** The header a reader sends to ask a Shoutcast or Icecast station for its song titles (#423). */
internal const val ICY_METADATA_HEADER = "Icy-MetaData"

/** The header a station answers with: how many audio bytes come between two title blocks. */
internal const val ICY_METAINT_HEADER = "icy-metaint"

/**
 * The song titles of one Shoutcast or Icecast response (#423). The station puts a title block after
 * every [interval] audio bytes: one byte that says how many sixteens of bytes follow, then the
 * block, `StreamTitle='Artist - Song';StreamUrl='...';`, padded with NUL. A block of length zero
 * means the title did not change, which is most of them.
 *
 * The reader takes each block out where it falls and keeps the audio bytes, so the demuxer never
 * sees a block. A change of the fields, decoded with the player's encoding guess, is held for the
 * reader's `takeTags`, which hands it over once.
 */
internal class IcyTitles(val interval: Int) {

    init {
        require(interval > 0) { "a title block interval must be positive, was $interval" }
    }

    /** Audio bytes left before the next block. */
    var untilBlock: Int = interval
        private set

    private var last: Map<String, String>? = null
    private var pending: Map<String, String>? = null

    /** Counts [count] audio bytes handed over. */
    fun consumed(count: Int) {
        untilBlock -= count
        check(untilBlock >= 0) { "read past a title block" }
    }

    /** Takes in the block whose bytes are [block], which follows its length byte, and starts the next interval. */
    fun block(block: ByteArray) {
        untilBlock = interval
        if (block.isEmpty()) return
        val fields = parseIcyBlock(block)
        if (fields.isEmpty() || fields == last) return
        last = fields
        pending = fields
    }

    /** The fields of the last block that changed them, once, or null. */
    fun take(): Map<String, String>? = pending.also { pending = null }

    /** The interval begins again, on a new response. The last title stands, so an unchanged one is not reported again. */
    fun restart() {
        untilBlock = interval
    }
}

/**
 * The fields of one title block: `Key='value';` pairs, where a value may hold an apostrophe, as a
 * song called Don't Stop does, and ends only at `';`. The bytes are decoded with the player's own
 * guess, because a station often sends windows-1251 or another legacy table rather than UTF-8.
 */
internal fun parseIcyBlock(block: ByteArray): Map<String, String> {
    var end = block.size
    while (end > 0 && block[end - 1] == 0.toByte()) end--
    if (end == 0) return emptyMap()
    val text = TextEncodings.decode(block.copyOf(end))
    val fields = LinkedHashMap<String, String>()
    var at = 0
    while (at < text.length) {
        val equals = text.indexOf('=', at)
        if (equals < 0) break
        val key = text.substring(at, equals).trim()
        var valueStart = equals + 1
        val value: String
        if (valueStart < text.length && text[valueStart] == '\'') {
            valueStart++
            val close = text.indexOf("';", valueStart).let { if (it < 0) text.lastIndexOf('\'').takeIf { q -> q >= valueStart } ?: text.length else it }
            value = text.substring(valueStart, close)
            at = (close + 2).coerceAtMost(text.length)
        } else {
            val close = text.indexOf(';', valueStart).let { if (it < 0) text.length else it }
            value = text.substring(valueStart, close)
            at = close + 1
        }
        if (key.isNotEmpty()) fields[key] = value.trim()
    }
    return fields
}
