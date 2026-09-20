package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.TrackId

/**
 * Where finished song maps are kept between runs of an application.
 *
 * A scan of a whole song costs seconds of processor time, so a song that was played before should
 * be mapped from its first note rather than scanned again. The visualiser holds the newest maps in
 * memory by itself; a store is what survives the process.
 *
 * The application owns the location, because only it knows which of its directories may be cleared
 * by the system and which are backed up. [SongMapStore.inDirectory] is the ordinary answer; an
 * application with its own database may implement this interface instead.
 *
 * A key is opaque, safe in a file name, and already carries the analysis version, so maps from
 * different versions never collide. The bytes are the visualiser's own format: write them back
 * unchanged, and answer null rather than throwing when an entry is missing or unreadable.
 *
 * Calls happen on a background dispatcher, never on the analysis worker, so they may block.
 */
@AudioVizAuthoringApi
public interface SongMapStore {
    /** The bytes stored under [key], or null when there are none. */
    public suspend fun read(key: String): ByteArray?

    /** Stores [bytes] under [key], replacing what was there. Failure is not an error worth raising. */
    public suspend fun write(key: String, bytes: ByteArray)

    /** Drops [key], because live audio disagreed with the map it held. */
    public suspend fun remove(key: String)

    public companion object {
        /** Keeps nothing. The visualiser's in-memory cache still works. */
        public val None: SongMapStore = object : SongMapStore {
            override suspend fun read(key: String): ByteArray? = null
            override suspend fun write(key: String, bytes: ByteArray) {}
            override suspend fun remove(key: String) {}
        }

        /**
         * One file per map inside [path], which is created when the first map is written.
         *
         * Give it a directory of its own: it deletes the oldest entries when there are more than
         * [maximumEntries]. A map of a four minute song is about ten kilobytes.
         */
        public fun inDirectory(path: String, maximumEntries: Int = 128): SongMapStore {
            require(maximumEntries >= 1) { "a store keeps at least one map" }
            return DirectorySongMapStore(path.trimEnd('/'), maximumEntries)
        }
    }
}

/** One file per map, named after the key, with the oldest deleted once the directory is full. */
internal class DirectorySongMapStore(
    private val directory: String,
    private val maximumEntries: Int,
) : SongMapStore {

    private fun pathOf(key: String) = "$directory/${fileNameOf(key)}"

    override suspend fun read(key: String): ByteArray? = SongMapFiles.read(pathOf(key))

    override suspend fun write(key: String, bytes: ByteArray) {
        if (!SongMapFiles.write(pathOf(key), bytes)) return
        val present = SongMapFiles.oldestFirst(directory, SUFFIX)
        var over = present.size - maximumEntries
        var index = 0
        while (over > 0 && index < present.size) {
            SongMapFiles.delete(present[index])
            index++
            over--
        }
    }

    override suspend fun remove(key: String) {
        SongMapFiles.delete(pathOf(key))
    }

    private companion object {
        const val SUFFIX = ".songmap"

        /** The key holds separators and nothing else unusual, so only those have to go. */
        fun fileNameOf(key: String): String =
            key.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.joinToString("") + SUFFIX
    }
}

/** Reading and writing one file, per platform. Blocking; callers are already off the analysis worker. */
internal expect object SongMapFiles {
    fun read(path: String): ByteArray?

    /** Creates the containing directory when it is missing. False when nothing was written. */
    fun write(path: String, bytes: ByteArray): Boolean

    fun delete(path: String)

    /** Full paths of the files directly in [directory] whose name ends with [suffix], oldest first. */
    fun oldestFirst(directory: String, suffix: String): List<String>
}

/*
 * The stored form of a song map.
 *
 * A hand-written little-endian format, because the module carries no serialization library and the
 * shape is four flat arrays. Every read checks the magic and the analysis version first, so a file
 * from another release, a truncated file and a file that is not a map at all all answer null
 * rather than producing a wrong map.
 */

private const val MAGIC = 0x4B534D31 // "KSM1"

internal fun encodeSongMap(map: SongMap): ByteArray {
    val out = ByteWriter(64 + map.structureCount * 32 + map.keyCount * 28 + map.levelCurve.size * 4)
    out.int(MAGIC)
    out.int(map.version)
    out.int(map.track.value)
    out.long(map.coveredThroughMicros)
    out.byte(if (map.complete) 1 else 0)
    val reference = map.referencePower
    out.byte(if (reference == null) 0 else 1)
    out.long((reference ?: 0.0).toRawBits())
    out.int(map.structureCount)
    for (index in 0 until map.structureCount) {
        val detection = map.structure(index)
        out.int(detection.kind.ordinal)
        out.long(detection.ptsMicros)
        out.long(detection.availableMicros)
        out.int(detection.strength.toRawBits())
        out.int(detection.confidence.toRawBits())
        out.int(detection.surprise.toRawBits())
    }
    out.int(map.keyCount)
    for (index in 0 until map.keyCount) {
        val key = map.key(index)
        out.long(key.startMicros)
        out.long(key.endMicros)
        out.int(key.tonic)
        out.int(key.mode.ordinal)
        out.int(key.confidence.toRawBits())
    }
    out.long(map.curveStartMicros)
    out.int(map.levelCurve.size)
    for (value in map.levelCurve) out.int(value.toRawBits())
    return out.bytes()
}

/** The map [bytes] hold, or null when they are not a map this release can read. */
internal fun decodeSongMap(bytes: ByteArray): SongMap? = try {
    val input = ByteReader(bytes)
    if (input.int() != MAGIC) null else {
        val version = input.int()
        if (version != SongMap.VERSION) null else {
            val track = TrackId(input.int())
            val coveredThrough = input.long()
            val complete = input.byte().toInt() == 1
            val hasReference = input.byte().toInt() == 1
            val reference = Double.fromBits(input.long()).takeIf { hasReference }
            val kinds = AudioEventKind.entries
            val structure = Array(input.count()) {
                val kind = kinds[input.int()]
                AudioDetection(kind, input.long(), input.long(),
                    Float.fromBits(input.int()), Float.fromBits(input.int()), Float.fromBits(input.int()))
            }
            val modes = KeyMode.entries
            val keys = Array(input.count()) {
                KeySegment(input.long(), input.long(), input.int(), modes[input.int()], Float.fromBits(input.int()))
            }
            val curveStart = input.long()
            val curve = FloatArray(input.count()) { Float.fromBits(input.int()) }
            SongMap(version, track, coveredThrough, complete, reference, structure, keys, curve, curveStart)
        }
    }
} catch (failure: IllegalArgumentException) {
    // A truncated or corrupt file is a cache miss, never a crash.
    null
} catch (failure: IndexOutOfBoundsException) {
    null
}

private class ByteWriter(capacity: Int) {
    private var data = ByteArray(capacity.coerceAtLeast(16))
    private var size = 0

    private fun room(more: Int) {
        if (size + more <= data.size) return
        var next = data.size * 2
        while (next < size + more) next *= 2
        data = data.copyOf(next)
    }

    fun byte(value: Int) {
        room(1)
        data[size++] = value.toByte()
    }

    fun int(value: Int) {
        room(4)
        for (shift in 0 until 4) data[size++] = (value ushr (shift * 8)).toByte()
    }

    fun long(value: Long) {
        room(8)
        for (shift in 0 until 8) data[size++] = (value ushr (shift * 8)).toByte()
    }

    fun bytes(): ByteArray = data.copyOf(size)
}

private class ByteReader(private val data: ByteArray) {
    private var at = 0

    fun byte(): Byte {
        require(at < data.size) { "the map ends early" }
        return data[at++]
    }

    fun int(): Int {
        require(at + 4 <= data.size) { "the map ends early" }
        var value = 0
        for (shift in 0 until 4) value = value or ((data[at++].toInt() and 0xFF) shl (shift * 8))
        return value
    }

    fun long(): Long {
        require(at + 8 <= data.size) { "the map ends early" }
        var value = 0L
        for (shift in 0 until 8) value = value or ((data[at++].toLong() and 0xFF) shl (shift * 8))
        return value
    }

    /** A length that a corrupt file cannot turn into an enormous allocation. */
    fun count(): Int {
        val value = int()
        require(value >= 0 && value <= data.size) { "the map declares $value entries it cannot hold" }
        return value
    }
}
