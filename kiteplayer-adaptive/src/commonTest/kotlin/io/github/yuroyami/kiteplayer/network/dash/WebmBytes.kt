package io.github.yuroyami.kiteplayer.network.dash

/** WebM bytes written by hand for the tests: EBML elements, and a whole file whose Cues name its clusters. */
internal object WebmBytes {

    const val SEGMENT = 0x18538067L
    const val CUES = 0x1C53BB6BL
    const val CLUSTER = 0x1F43B675L

    fun element(id: Long, data: ByteArray): ByteArray = id(id) + size(data.size.toLong()) + data

    /** An ID's own bytes, which keep their length marker. */
    fun id(id: Long): ByteArray {
        val length = (64 - id.countLeadingZeroBits() + 7) / 8
        return ByteArray(length) { ((id shr (8 * (length - 1 - it))) and 0xFF).toByte() }
    }

    /** A size in eight bytes, the longest form, which every reader must take. */
    fun size(value: Long): ByteArray = ByteArray(8) { i -> if (i == 0) 0x01 else ((value shr (8 * (7 - i))) and 0xFF).toByte() }

    /** A Segment size of eight bytes of ones after the marker: unknown, as a live muxer writes it. */
    val UNKNOWN_SIZE: ByteArray = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

    fun unsigned(value: Long): ByteArray = ByteArray(8) { ((value shr (8 * (7 - it))) and 0xFF).toByte() }

    fun cuePoint(time: Long, track: Long, cluster: Long): ByteArray =
        element(0xBB, element(0xB3, unsigned(time)) + element(0xB7, element(0xF7, unsigned(track)) + element(0xF1, unsigned(cluster))))

    /** A WebM file, with where its first cluster, each cluster and its Cues are. */
    class File(val bytes: ByteArray, val segmentDataStart: Long, val firstCluster: Long, val clusters: List<LongRange>, val cues: LongRange)

    /**
     * A file of an EBML header, a Segment of known size, a SeekHead that names the Cues, Info with a
     * millisecond timestamp scale and [durationMillis], Tracks, a cluster for each of [clusterMillis]
     * holding [blockBytes] of a block, and the Cues after the clusters, as ffmpeg's WebM muxer writes
     * an on-demand file.
     */
    fun file(clusterMillis: List<Long>, blockBytes: Int, durationMillis: Double): File {
        val ebml = element(0x1A45DFA3, element(0x4282, "webm".encodeToByteArray()))
        val info = element(0x1549A966, element(0x2AD7B1, unsigned(1_000_000)) + element(0x4489, unsigned(durationMillis.toRawBits())))
        val tracks = element(0x1654AE6B, element(0xAE, element(0xD7, unsigned(1))))
        val clusters = clusterMillis.map { element(CLUSTER, element(0xE7, unsigned(it)) + element(0xA3, ByteArray(blockBytes))) }
        fun seekHead(cuesPosition: Long) = element(0x114D9B74, element(0x4DBB, element(0x53AB, id(CUES)) + element(0x53AC, unsigned(cuesPosition))))
        // Positions count from the start of the Segment's data, which begins with the SeekHead.
        val beforeClusters = seekHead(0).size + info.size + tracks.size
        val clusterPositions = clusters.runningFold(beforeClusters.toLong()) { at, cluster -> at + cluster.size }
        val cuesPosition = clusterPositions.last()
        val cues = element(CUES, clusterMillis.indices.fold(ByteArray(0)) { all, i -> all + cuePoint(clusterMillis[i], 1, clusterPositions[i]) })
        val data = seekHead(cuesPosition) + info + tracks + clusters.fold(ByteArray(0)) { all, c -> all + c } + cues
        val segmentHeader = id(SEGMENT) + size(data.size.toLong())
        val dataStart = (ebml.size + segmentHeader.size).toLong()
        return File(
            bytes = ebml + segmentHeader + data,
            segmentDataStart = dataStart,
            firstCluster = dataStart + beforeClusters,
            clusters = clusters.indices.map { (dataStart + clusterPositions[it]) until (dataStart + clusterPositions[it + 1]) },
            cues = (dataStart + cuesPosition) until (dataStart + cuesPosition + cues.size),
        )
    }
}
