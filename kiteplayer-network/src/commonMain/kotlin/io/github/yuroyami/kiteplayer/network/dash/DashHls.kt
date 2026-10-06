package io.github.yuroyami.kiteplayer.network.dash

/**
 * One media segment with its place in time: [startMicros] and [durationMicros] are Period time,
 * or presentation time in a plan that joins Periods (#403).
 */
internal class DashTimedSegment(
    val url: String,
    /** The bytes of [url] that hold the segment, or null for all of it. */
    val range: LongRange?,
    /** The DASH segment number, which the HLS media sequence follows. */
    val number: Long,
    val startMicros: Long,
    val durationMicros: Long,
    /** The segment's own initialization, in a plan that joins Periods, whose initializations differ. */
    val initializationUrl: String? = null,
    val initializationRange: LongRange? = null,
    /** Whether a new Period begins with this segment. */
    val discontinuity: Boolean = false,
)

/**
 * Whether the set is encrypted: it, or one of its representations, has a `ContentProtection`
 * element. Digital rights management is out of scope, so such a set is never played (#404).
 */
internal val DashAdaptationSet.isProtected: Boolean get() = contentProtectionSchemes.isNotEmpty()

/** A representation's segments in order, after its initialization, if it has one. */
internal class DashTimedPlan(
    val initializationUrl: String?,
    val initializationRange: LongRange?,
    val segments: List<DashTimedSegment>,
)

/** What a representation is to HLS: a variant with a picture, a sound rendition, or a subtitle rendition. */
internal enum class DashHlsRole { Video, Audio, Subtitles }

/**
 * How a subtitle set reaches FFmpeg, whose HLS reader takes subtitles only as WebVTT text: as it
 * is, or converted to WebVTT by the reader, from TTML or from MP4 samples (#402).
 */
internal enum class DashSubtitleFormat { WebVtt, Ttml, Mp4 }

/**
 * One representation that the HLS stand-in names, and the address of its media playlist. The two
 * indexes find it again in a live manifest that was fetched anew.
 */
internal class DashHlsTrack(
    val address: String,
    val role: DashHlsRole,
    val setIndex: Int,
    val representationIndex: Int,
    val set: DashAdaptationSet,
    val representation: DashRepresentation,
    /** For a subtitle rendition, the form its segments arrive in. */
    val subtitleFormat: DashSubtitleFormat? = null,
)

/**
 * A DASH presentation as HLS (#295): the master playlist that names every [tracks] entry, and
 * the tracks, whose media playlists are written on demand with [DashHls.mediaPlaylist].
 */
internal class DashHlsPresentation(val masterAddress: String, val master: String, val tracks: List<DashHlsTrack>) {
    private val byAddress = tracks.associateBy { it.address }

    fun track(address: String): DashHlsTrack? = byAddress[address]
}

/**
 * Writes the HLS playlists that stand in for a DASH presentation (#295): each video
 * representation becomes a variant, each audio set an `EXT-X-MEDIA` rendition of one group, each
 * subtitle set a subtitle rendition, and FFmpeg's HLS demuxer plays them through the player's own
 * HLS path, which already selects, switches and steps variants. No byte of the media changes. A
 * TTML or MP4 subtitle set is the exception: its segments are served as WebVTT, which is the only
 * form FFmpeg's HLS reader takes subtitles in (#402).
 *
 * The playlists name each other under a host that cannot resolve, `kite-dash.invalid` (RFC 2606),
 * and the reader that serves them answers those addresses itself. Every other address is a
 * segment the manifest named, absolute and already checked against its [DashUrlPolicy].
 */
internal object DashHls {

    /** The reserved host the stand-in playlists live under. */
    const val HOST: String = "kite-dash.invalid"

    /**
     * Whether HLS can carry [period]: a set with a picture or sound, every such representation
     * in fragmented MP4, MPEG-TS or WebM with segment addressing of some kind. A subtitle set does
     * not decide it; one in a form the reader cannot convert is left out of the stand-in. An
     * encrypted set does not decide it either, because the stand-in leaves it out (#404).
     */
    fun carries(period: DashPeriod): Boolean {
        val media = period.adaptationSets.filter { !it.isProtected && (roleOf(it) == DashHlsRole.Video || roleOf(it) == DashHlsRole.Audio) }
        if (media.isEmpty()) return false
        return media.all { set -> set.representations.isNotEmpty() && set.representations.all { carriable(set, it) } }
    }

    /** Whether HLS can carry every Period of [manifest], which then play joined (#403). */
    fun carries(manifest: DashManifest): Boolean = manifest.periods.isNotEmpty() && manifest.periods.all(::carries)

    /**
     * The stand-in for [period]. Video sets give variants, in ascending bandwidth. With no video, the
     * representations of the first audio set are the variants, and the other audio sets are not
     * offered. Each audio set offers its highest bandwidth representation as its rendition. A
     * [live] presentation offers no subtitle file that has no segments, because such a file has
     * no length to give. An encrypted set is not offered at all (#404).
     *
     * A rendition is named by its set's `Label`, or else by its language and roles. Its DASH roles
     * become what HLS says of it: `caption` the accessibility characteristic of subtitles that
     * describe sound, `description` that of sound that describes the picture, `easyreader` the
     * easy-to-read one, and `forced-subtitle` a forced rendition. The first audio set whose role is
     * `main` is the default, or else the first that is neither a description nor a commentary, and
     * an audio rendition states its channels.
     */
    fun presentation(period: DashPeriod, live: Boolean = false): DashHlsPresentation {
        require(carries(period)) { "HLS cannot carry this Period" }
        val root = "https://$HOST"
        val tracks = mutableListOf<DashHlsTrack>()
        fun track(role: DashHlsRole, setIndex: Int, set: DashAdaptationSet, index: Int, format: DashSubtitleFormat? = null) =
            DashHlsTrack("$root/$setIndex-$index.m3u8", role, setIndex, index, set, set.representations[index], format)

        // An encrypted set keeps its place in the numbering, which the playlists look sets up by.
        val sets = period.adaptationSets.withIndex().filter { !it.value.isProtected }
        val videoSets = sets.filter { roleOf(it.value) == DashHlsRole.Video }
        val audioSets = sets.filter { roleOf(it.value) == DashHlsRole.Audio }
        val textSets = sets.filter { roleOf(it.value) == DashHlsRole.Subtitles }

        val variants: List<DashHlsTrack>
        val audio: List<DashHlsTrack>
        if (videoSets.isNotEmpty()) {
            variants = videoSets.flatMap { (setIndex, set) ->
                set.representations.indices.map { track(DashHlsRole.Video, setIndex, set, it) }
            }.sortedBy { it.representation.bandwidth }
            audio = audioSets.map { (setIndex, set) ->
                track(DashHlsRole.Audio, setIndex, set, set.representations.indices.maxBy { set.representations[it].bandwidth })
            }
        } else {
            val (setIndex, set) = audioSets.first()
            variants = set.representations.indices.map { track(DashHlsRole.Audio, setIndex, set, it) }
                .sortedBy { it.representation.bandwidth }
            audio = emptyList()
        }
        val subtitles = textSets.mapNotNull { (setIndex, set) ->
            val rep = set.representations.firstOrNull() ?: return@mapNotNull null
            val format = subtitleFormat(set) ?: return@mapNotNull null
            val segmented = rep.segmentTemplate != null || rep.segmentList?.segments?.isNotEmpty() == true ||
                (format == DashSubtitleFormat.Mp4 && rep.segmentBase != null)
            if (live && !segmented) null else track(DashHlsRole.Subtitles, setIndex, set, 0, format)
        }
        tracks += variants
        tracks += audio
        tracks += subtitles

        val master = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            // With no set marked main, a description or a commentary is nobody's default sound.
            val mainAudio = audio.indexOfFirst { "main" in it.set.roles }.takeIf { it >= 0 }
                ?: audio.indexOfFirst { track -> track.set.roles.none { it == "description" || it == "commentary" } }.coerceAtLeast(0)
            val audioNames = names("audio", audio)
            for ((index, rendition) in audio.withIndex()) {
                append(rendition("AUDIO", AUDIO_GROUP, rendition, audioNames[index], isDefault = index == mainAudio))
            }
            val subtitleNames = names("subtitles", subtitles)
            for ((index, rendition) in subtitles.withIndex()) {
                append(rendition("SUBTITLES", SUBTITLE_GROUP, rendition, subtitleNames[index], isDefault = false))
            }
            val audioBandwidth = audio.maxOfOrNull { it.representation.bandwidth } ?: 0L
            val audioCodecs = audio.mapNotNull { it.representation.codecs }.distinct()
            for (variant in variants) {
                val rep = variant.representation
                val attributes = mutableListOf("BANDWIDTH=${rep.bandwidth + audioBandwidth}")
                val codecs = (listOfNotNull(rep.codecs) + audioCodecs).distinct()
                if (codecs.isNotEmpty()) attributes += "CODECS=\"${codecs.joinToString(",")}\""
                if (rep.width != null && rep.height != null) attributes += "RESOLUTION=${rep.width}x${rep.height}"
                rep.frameRate?.let { attributes += "FRAME-RATE=${decimal(it, 3)}" }
                // The variant choice reads the range here as it does in an HLS master (#447).
                rep.videoRange?.let { attributes += "VIDEO-RANGE=$it" }
                if (audio.isNotEmpty()) attributes += "AUDIO=\"$AUDIO_GROUP\""
                if (subtitles.isNotEmpty()) attributes += "SUBTITLES=\"$SUBTITLE_GROUP\""
                append("#EXT-X-STREAM-INF:").append(attributes.joinToString(",")).append('\n')
                append(variant.address).append('\n')
            }
        }
        return DashHlsPresentation("$root/master.m3u8", master, tracks)
    }

    /**
     * The media playlist of [plan]. A live one has no end, so FFmpeg loads it again for the
     * segments that arrive; [sequence] numbers its first segment.
     */
    fun mediaPlaylist(plan: DashTimedPlan, live: Boolean, sequence: Long = plan.segments.firstOrNull()?.number ?: 0L): String =
        buildString {
            val longest = plan.segments.maxOfOrNull { it.durationMicros } ?: 1_000_000L
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append((longest + 999_999) / 1_000_000).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(sequence).append('\n')
            if (!live) append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            fun map(url: String, range: LongRange?) {
                append("#EXT-X-MAP:URI=\"").append(url).append('"')
                range?.let { append(",BYTERANGE=\"").append(byteRange(it)).append('"') }
                append('\n')
            }
            // A plan that joins Periods names each segment's initialization, and a new one where it changes.
            val ownInitializations = plan.segments.any { it.initializationUrl != null }
            if (!ownInitializations) plan.initializationUrl?.let { map(it, plan.initializationRange) }
            var current: String? = null
            for (segment in plan.segments) {
                if (segment.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                val init = segment.initializationUrl
                if (ownInitializations && init != null && "$init#${segment.initializationRange}" != current) {
                    map(init, segment.initializationRange)
                    current = "$init#${segment.initializationRange}"
                }
                append("#EXTINF:").append(seconds(segment.durationMicros)).append(",\n")
                segment.range?.let { append("#EXT-X-BYTERANGE:").append(byteRange(it)).append('\n') }
                append(segment.url).append('\n')
            }
            if (!live) append("#EXT-X-ENDLIST\n")
        }

    /** Which part [set] plays, or null for a set the stand-in does not carry, such as thumbnails. */
    fun roleOf(set: DashAdaptationSet): DashHlsRole? {
        val types = listOfNotNull(set.contentType) +
            listOfNotNull(set.mimeType) + set.representations.mapNotNull { it.mimeType }
        return when {
            types.any { it == "video" || it.startsWith("video/") } -> DashHlsRole.Video
            types.any { it == "audio" || it.startsWith("audio/") } -> DashHlsRole.Audio
            subtitleFormat(set) != null -> DashHlsRole.Subtitles
            else -> null
        }
    }

    /**
     * The form [set]'s subtitles arrive in: WebVTT files or segments, TTML documents
     * (`application/ttml+xml`), or MP4 segments of TTML (`stpp`) or WebVTT (`wvtt`) samples. Null
     * for a set that is not subtitles, or whose form the reader cannot convert.
     */
    fun subtitleFormat(set: DashAdaptationSet): DashSubtitleFormat? {
        val types = (listOfNotNull(set.mimeType) + set.representations.mapNotNull { it.mimeType }).map { it.lowercase() }
        val codecs = set.representations.mapNotNull { it.codecs?.lowercase() }
        return when {
            types.any { it == "text/vtt" } -> DashSubtitleFormat.WebVtt
            types.any { it == "application/ttml+xml" } -> DashSubtitleFormat.Ttml
            (types.any { it == "application/mp4" } || set.contentType == "text") &&
                codecs.any { it.startsWith("stpp") || it.startsWith("wvtt") } -> DashSubtitleFormat.Mp4
            else -> null
        }
    }

    /**
     * Fragmented MP4, MPEG-TS or WebM, with segments that a playlist can name. The HLS
     * specification names only the first two, but FFmpeg's HLS reader probes each playlist's
     * segments for their format, so it reads WebM as well (#401).
     */
    private fun carriable(set: DashAdaptationSet, rep: DashRepresentation): Boolean {
        val mime = (rep.mimeType ?: set.mimeType)?.lowercase()
        val container = mime in MP4_TYPES || mime in TS_TYPES || mime in WEBM_TYPES ||
            (mime == null && rep.segmentTemplate?.media?.substringBefore('?')?.lowercase()?.let { media ->
                CARRIED_EXTENSIONS.any { media.endsWith(it) }
            } == true)
        val addressed = rep.segmentTemplate != null || rep.segmentBase != null || rep.segmentList?.segments?.isNotEmpty() == true
        return container && addressed
    }

    /**
     * The name of each of [tracks]: its set's label, or else its language, or [kind], and the words
     * of its roles. A name is unique in its group, so names that would repeat are counted.
     */
    private fun names(kind: String, tracks: List<DashHlsTrack>): List<String> {
        val bases = tracks.map { track ->
            track.set.label ?: (listOf(track.set.lang ?: kind) + track.set.roles.mapNotNull { ROLE_WORDS[it.lowercase()] }).joinToString(" ")
        }
        val seen = HashMap<String, Int>()
        return bases.map { base ->
            if (bases.count { it == base } == 1) return@map base
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            "$base $count"
        }
    }

    private fun rendition(type: String, group: String, track: DashHlsTrack, name: String, isDefault: Boolean): String {
        val set = track.set
        val language = set.lang
        val roles = set.roles.map { it.lowercase() }
        val attributes = mutableListOf("TYPE=$type", "GROUP-ID=\"$group\"", "NAME=\"${quoted(name)}\"")
        if (language != null) attributes += "LANGUAGE=\"${quoted(language)}\""
        attributes += "DEFAULT=${if (isDefault) "YES" else "NO"}"
        attributes += "AUTOSELECT=YES"
        if (type == "SUBTITLES" && "forced-subtitle" in roles) attributes += "FORCED=YES"
        val characteristics = roles.mapNotNull { CHARACTERISTICS[it] }.distinct()
        if (characteristics.isNotEmpty()) attributes += "CHARACTERISTICS=\"${characteristics.joinToString(",")}\""
        if (type == "AUDIO") track.representation.audioChannels?.let { attributes += "CHANNELS=\"$it\"" }
        attributes += "URI=\"${track.address}\""
        return "#EXT-X-MEDIA:" + attributes.joinToString(",") + "\n"
    }

    /** [text] with what cannot stand inside an HLS quoted string, a quote or a line break, replaced. */
    private fun quoted(text: String): String = text.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')

    /** The words a rendition's made-up name gives its DASH roles. */
    private val ROLE_WORDS = mapOf(
        "commentary" to "commentary", "description" to "description", "caption" to "captions",
        "forced-subtitle" to "forced", "dub" to "dub", "alternate" to "alternate",
        "supplementary" to "supplementary", "sign" to "sign language", "easyreader" to "easy reader",
    )

    /** The HLS characteristics (RFC 8216bis, 4.4.6.1) of the DASH roles that have one. */
    private val CHARACTERISTICS = mapOf(
        "caption" to "public.accessibility.describes-music-and-sound",
        "description" to "public.accessibility.describes-video",
        "easyreader" to "public.easy-to-read",
    )

    /** An HLS byte range, `length@offset`. */
    private fun byteRange(range: LongRange): String = "${range.last - range.first + 1}@${range.first}"

    /** Microseconds as seconds with six decimals, with no locale anywhere near it. */
    private fun seconds(micros: Long): String =
        "${micros / 1_000_000}.${(micros % 1_000_000).toString().padStart(6, '0')}"

    private fun decimal(value: Double, places: Int): String {
        var scale = 1L
        repeat(places) { scale *= 10 }
        val scaled = kotlin.math.round(value * scale).toLong()
        return "${scaled / scale}.${(scaled % scale).toString().padStart(places, '0')}"
    }

    private const val AUDIO_GROUP = "audio"
    private const val SUBTITLE_GROUP = "subtitles"
    private val MP4_TYPES = setOf("video/mp4", "audio/mp4")
    private val TS_TYPES = setOf("video/mp2t", "audio/mp2t")
    private val WEBM_TYPES = setOf("video/webm", "audio/webm", "video/x-matroska", "audio/x-matroska")
    private val CARRIED_EXTENSIONS = listOf(".m4s", ".mp4", ".m4v", ".m4a", ".cmfv", ".cmfa", ".ts", ".webm", ".weba", ".mkv", ".mka")

    /** Whether [rep] is WebM or Matroska, whose single files an index of `Cues` describes instead of a `sidx`. */
    fun isWebm(set: DashAdaptationSet, rep: DashRepresentation): Boolean {
        val mime = (rep.mimeType ?: set.mimeType)?.lowercase()
        if (mime != null) return mime in WEBM_TYPES
        val path = rep.baseUrl.substringBefore('#').substringBefore('?').lowercase()
        return listOf(".webm", ".weba", ".mkv", ".mka").any { path.endsWith(it) }
    }
}
