package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Playlists
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The time of day of an HLS stream's positions (#444), from the `EXT-X-PROGRAM-DATE-TIME` tags of
 * its media playlists. FFmpeg's HLS reader ignores the tag, so the HLS path hands this every media
 * playlist its reader serves, with [read], and every address FFmpeg opens, with [opened].
 *
 * The first segment FFmpeg opens starts the position timeline, because FFmpeg's start time is the
 * first timestamp it reads. Each later segment of that playlist starts where the one before it
 * ends; across a gap in the sequence numbers, which a late reload of a live playlist leaves, it
 * starts as far after the last known one as their dates say. A position's time of day is its
 * segment's date plus the offset into the segment.
 *
 * A segment is known by its address, resolved as FFmpeg resolves it. A playlist that names its
 * segments through variables, `EXT-X-DEFINE`, matches no address FFmpeg opens, so it gives no time
 * of day yet.
 *
 * Read by the engine's thread and the callers' while FFmpeg's thread writes, so every member takes
 * the lock. Positions are microseconds of the source's timeline, and times of day are
 * microseconds since 1970 UTC.
 */
internal class HlsTimeOfDay {
    private val lock = SynchronizedObject()

    /** Each media playlist read before the anchor was found, by the address it was read from, oldest first. */
    private val unanchored = LinkedHashMap<String, DatedPlaylist>()

    /** The playlist the first opened segment came from, once one has opened. */
    private var anchorPlaylist: String? = null

    /** The anchored playlist's segments from the anchor on, in order, with their starts. */
    private val placed = ArrayList<PlacedSegment>()

    /** The anchored playlist as it was last read, for the span it lists. */
    private var latest: DatedPlaylist? = null

    /** Takes the media playlist at [address], whose segments' addresses resolve against [base]. */
    fun read(address: String, text: String, base: String = address) {
        val playlist = readDatedPlaylist(text, base) ?: return
        if (playlist.segments.none { it.dateMicros != null }) return
        synchronized(lock) {
            val anchored = anchorPlaylist
            when {
                anchored == null -> {
                    unanchored.remove(address)
                    unanchored[address] = playlist
                    // A stream has a playlist for each rendition, a few at most.
                    while (unanchored.size > MAX_PLAYLISTS) unanchored.remove(unanchored.keys.first())
                }
                anchored == address -> extend(playlist)
            }
        }
    }

    /** FFmpeg opened [address]. The first segment of a dated playlist anchors the timeline at zero. */
    fun opened(address: String) {
        synchronized(lock) {
            if (anchorPlaylist != null) return
            for ((key, playlist) in unanchored) {
                val first = playlist.segments.firstOrNull { it.address == address } ?: continue
                anchorAt(key, playlist, first.sequence)
                return
            }
        }
    }

    /**
     * Anchors on the first segment of the playlist at [address], for a playlist FFmpeg reads with
     * no related opens to report. Only a playlist that has ended starts there: a live one starts a
     * few segments from its end, which only [opened] can say.
     */
    fun anchorAtStart(address: String) {
        synchronized(lock) {
            if (anchorPlaylist != null) return
            val playlist = unanchored[address]?.takeIf { it.ended } ?: return
            val first = playlist.segments.firstOrNull() ?: return
            anchorAt(address, playlist, first.sequence)
        }
    }

    /** The time of day of [positionMicros], or null where the stream states none. */
    fun timeOfDayAt(positionMicros: Long): Long? = synchronized(lock) {
        if (placed.isEmpty()) return@synchronized null
        // The last segment that starts at or before the position, or the first before the start.
        var low = 0
        var high = placed.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (placed[middle].startMicros <= positionMicros) low = middle else high = middle - 1
        }
        val segment = placed[low]
        val date = segment.dateMicros ?: return@synchronized null
        val offset = positionMicros - segment.startMicros
        // A position past a segment that is not the last belongs to a gap no date covers.
        if (low < placed.size - 1 && offset > segment.durationMicros) return@synchronized null
        date + offset
    }

    /** The position whose time of day is [timeOfDayMicros], or null where the stream lists none. */
    fun positionAt(timeOfDayMicros: Long): Long? = synchronized(lock) {
        for (index in placed.indices.reversed()) {
            val segment = placed[index]
            val date = segment.dateMicros ?: continue
            val offset = timeOfDayMicros - date
            // The end of the last segment is the end of what the stream lists, and still a position.
            val inside = offset >= 0 && (offset < segment.durationMicros || (index == placed.size - 1 && offset == segment.durationMicros))
            if (inside) return@synchronized segment.startMicros + offset
        }
        null
    }

    /** The earliest and the latest moment the anchored playlist lists, as it was last read. */
    fun span(): LongRange? = synchronized(lock) {
        val segments = latest?.segments ?: return@synchronized null
        val first = segments.firstNotNullOfOrNull { it.dateMicros } ?: return@synchronized null
        val last = segments.lastOrNull { it.dateMicros != null } ?: return@synchronized null
        val lastDate = last.dateMicros ?: return@synchronized null
        first..(lastDate + last.durationMicros)
    }

    private fun anchorAt(address: String, playlist: DatedPlaylist, sequence: Long) {
        anchorPlaylist = address
        unanchored.clear()
        var start = 0L
        for (segment in playlist.segments) {
            if (segment.sequence < sequence) continue
            placed += PlacedSegment(segment.sequence, start, segment.durationMicros, segment.dateMicros)
            start += segment.durationMicros
        }
        latest = playlist
    }

    /** Places the segments of a reload of the anchored playlist that come after the last one placed. */
    private fun extend(playlist: DatedPlaylist) {
        latest = playlist
        for (segment in playlist.segments) {
            val last = placed.lastOrNull() ?: return
            if (segment.sequence <= last.sequence) continue
            val start = if (segment.sequence == last.sequence + 1) {
                last.startMicros + last.durationMicros
            } else {
                // A gap: the segments between left the playlist before a reload saw them.
                val lastDate = last.dateMicros
                val date = segment.dateMicros
                if (lastDate == null || date == null) return
                last.startMicros + (date - lastDate)
            }
            placed += PlacedSegment(segment.sequence, start, segment.durationMicros, segment.dateMicros)
        }
        // A day of two second segments is about 43,000; the oldest leave first.
        if (placed.size > MAX_PLACED) placed.subList(0, placed.size - MAX_PLACED).clear()
    }

    private class PlacedSegment(val sequence: Long, val startMicros: Long, val durationMicros: Long, val dateMicros: Long?)

    private companion object {
        const val MAX_PLAYLISTS = 16
        const val MAX_PLACED = 100_000
    }
}

/** One segment of a media playlist: its sequence number, address, length and date, if it has one. */
internal class DatedSegment(val sequence: Long, val address: String, val durationMicros: Long, val dateMicros: Long?)

/** A media playlist's segments, and whether it has ended, as `EXT-X-ENDLIST` says. */
internal class DatedPlaylist(val segments: List<DatedSegment>, val ended: Boolean)

/**
 * The segments of the media playlist [text], their addresses resolved against [base] as FFmpeg
 * resolves them, or null when [text] is a master playlist or no playlist at all. A segment without
 * a date of its own takes the date of the segment before it plus that segment's length, as RFC
 * 8216's clients do, except after `EXT-X-DISCONTINUITY`, where the media may have jumped.
 */
internal fun readDatedPlaylist(text: String, base: String): DatedPlaylist? {
    var started = false
    var sequence = 0L
    var duration: Long? = null
    var ownDate: Long? = null
    var carried: Long? = null
    var discontinuity = false
    var ended = false
    val segments = ArrayList<DatedSegment>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (!started) {
            if (!line.removePrefix("﻿").startsWith("#EXTM3U")) return null
            started = true
            continue
        }
        when {
            line.startsWith("#EXT-X-STREAM-INF") -> return null
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1_000_000).toLong() }
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> ownDate = parseProgramDateTimeMicros(line.substringAfter(':'))
            line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
            line == "#EXT-X-ENDLIST" -> ended = true
            line.startsWith("#") -> Unit
            else -> {
                val length = duration ?: 0L
                val date = ownDate ?: carried.takeIf { !discontinuity }
                segments += DatedSegment(sequence, Playlists.resolve(base, line), length, date)
                carried = date?.plus(length)
                sequence++
                duration = null
                ownDate = null
                discontinuity = false
            }
        }
    }
    return if (started) DatedPlaylist(segments, ended) else null
}

/**
 * An `EXT-X-PROGRAM-DATE-TIME` value, ISO 8601 as RFC 8216 asks, such as
 * `2026-10-07T21:34:00.000Z` or `2026-10-07T23:34:00+02:00`, in microseconds since 1970 UTC, or
 * null for a value that is not one. A zone written without its colon, `+0200`, is read too,
 * because packagers write it, and a time with no zone is read as UTC.
 */
internal fun parseProgramDateTimeMicros(raw: String): Long? {
    val match = PROGRAM_DATE_TIME.matchEntire(raw.trim()) ?: return null
    val g = match.groupValues
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val seconds = daysFromCivil(g[1].toLong(), month, day) * 86_400 + hour * 3_600L + minute * 60L + second
    val fraction = g[7].takeIf { it.isNotEmpty() }?.let { it.take(6).padEnd(6, '0').toLong() } ?: 0L
    val zone = g[8]
    val offset = if (zone.isEmpty() || zone == "Z" || zone == "z") {
        0L
    } else {
        (if (zone.startsWith('-')) -1 else 1) * (g[9].toLong() * 3_600 + g[10].toLong() * 60)
    }
    return (seconds - offset) * 1_000_000 + fraction
}

/** The days from 1970-01-01 to the proleptic Gregorian [year]-[month]-[day]. */
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

private val PROGRAM_DATE_TIME = Regex(
    """(-?\d{4,})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:[.,](\d+))?(Z|z|[+-](\d{2}):?(\d{2}))?""",
)
