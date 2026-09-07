package io.github.yuroyami.kiteplayer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Everything needed to come back to where playback was: the queue, the item, the position and
 * every setting. Taken with [KitePlayer.memento], handed back to [KitePlayer.restore]. A value
 * the application stores however it likes; no serialisation is imposed. [asProperties] is a flat
 * string form for applications that keep key-value text, and [fromProperties] reads it back.
 *
 * What the text form cannot carry: [MediaItem.io] factories, which nobody can store,
 * [MediaItem.externalSubtitles] and [MediaItem.videoFilter]. [asProperties] drops all three, and
 * an item that needs one is rebuilt by the application before [KitePlayer.restore]. Headers, raw
 * open options, the format hint and the start position are strings and travel.
 *
 * Tracks are remembered by LANGUAGE rather than by id, because ids belong to one open of one
 * container and a memento outlives both.
 */
public data class PlayerMemento(
    val queue: List<MediaItem>,
    val queueIndex: Int,
    val position: Duration,
    val speed: Double,
    val preservePitch: Boolean,
    val volume: Float,
    val muted: Boolean,
    val loop: LoopMode,
    val shuffle: Boolean,
    val subtitleDelay: Duration,
    val audioDelay: Duration,
    /** The language of the audio track that was playing, or null when unknown or none. */
    val audioLanguage: String?,
    /** The language of the subtitle track that was showing, or null when unknown or none. */
    val subtitleLanguage: String?,
    /** True when the media had subtitle tracks and none was selected: the viewer turned them off. */
    val subtitlesOff: Boolean,
    /** The language of the second subtitle track that was showing, or null when none was. */
    val secondarySubtitleLanguage: String? = null,
    /** Stereo balance. An accessibility setting, so it travels. */
    val balance: Float = 0f,
    val equalizer: EqualizerSettings = EqualizerSettings.Flat,
    /** Subtitle size, place and styling. Accessibility settings, so they travel. */
    val subtitleScale: Float = 1f,
    val subtitlePosition: Float = 1f,
    val subtitleStyle: io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride? = null,
    val videoScale: VideoScale = VideoScale.Fit,
    val videoTransform: VideoTransform = VideoTransform.Identity,
    val videoAdjustments: VideoAdjustments = VideoAdjustments.Identity,
    val renderQuality: RenderQuality = RenderQuality.Off,
    /** False when the viewer was playing sound only. */
    val videoEnabled: Boolean = true,
) {

    /**
     * Flat string pairs, version-stamped. Keys: `version`, `queue.size`, then per item
     * `queue.N.uri`, `queue.N.formatHint`, `queue.N.startPosition` (microseconds),
     * `queue.N.header.<name>` and `queue.N.option.<key>`; then one key per setting, durations
     * in microseconds, and `audioLanguage` and `subtitleLanguage` only when known.
     */
    public fun asProperties(): Map<String, String> = buildMap {
        put("version", FORMAT_VERSION.toString())
        put("queue.size", queue.size.toString())
        queue.forEachIndexed { n, item ->
            put("queue.$n.uri", item.uri)
            item.formatHint?.let { put("queue.$n.formatHint", it) }
            item.startPosition?.let { put("queue.$n.startPosition", it.inWholeMicroseconds.toString()) }
            item.headers.forEach { (name, value) -> put("queue.$n.header.$name", value) }
            item.openOptions.forEach { (key, value) -> put("queue.$n.option.$key", value) }
        }
        put("queueIndex", queueIndex.toString())
        put("position", position.inWholeMicroseconds.toString())
        put("speed", speed.toString())
        put("preservePitch", preservePitch.toString())
        put("volume", volume.toString())
        put("muted", muted.toString())
        put("loop", loop.name)
        put("shuffle", shuffle.toString())
        put("subtitleDelay", subtitleDelay.inWholeMicroseconds.toString())
        put("audioDelay", audioDelay.inWholeMicroseconds.toString())
        audioLanguage?.let { put("audioLanguage", it) }
        subtitleLanguage?.let { put("subtitleLanguage", it) }
        put("subtitlesOff", subtitlesOff.toString())
        secondarySubtitleLanguage?.let { put("secondarySubtitleLanguage", it) }
        put("balance", balance.toString())
        put("equalizer.preampDb", equalizer.preampDb.toString())
        put("equalizer.gainsDb", equalizer.gainsDb.joinToString(" "))
        put("subtitleScale", subtitleScale.toString())
        put("subtitlePosition", subtitlePosition.toString())
        put("videoScale", videoScale.name)
        put("videoEnabled", videoEnabled.toString())
        put("transform.zoom", videoTransform.zoom.toString())
        put("transform.panX", videoTransform.panX.toString())
        put("transform.panY", videoTransform.panY.toString())
        videoTransform.aspectOverride?.let { put("transform.aspectOverride", it.toString()) }
        put("adjust.brightness", videoAdjustments.brightness.toString())
        put("adjust.contrast", videoAdjustments.contrast.toString())
        put("adjust.saturation", videoAdjustments.saturation.toString())
        put("adjust.hueDegrees", videoAdjustments.hueDegrees.toString())
        put("quality.dither", renderQuality.dither.toString())
        put("quality.deband", renderQuality.deband.toString())
        put("quality.debandThreshold", renderQuality.debandThreshold.toString())
        put("quality.debandRange", renderQuality.debandRange.toString())
        put("quality.debandGrain", renderQuality.debandGrain.toString())
        put("quality.scaler", renderQuality.scaler.name)
        put("quality.linearLight", renderQuality.linearLight.toString())
        subtitleStyle?.let { style ->
            put("subtitleStyle.present", "true")
            put("subtitleStyle.backgroundPaddingPx", style.backgroundPaddingPx.toString())
            style.fontFamily?.let { put("subtitleStyle.fontFamily", it) }
            style.fontSizePx?.let { put("subtitleStyle.fontSizePx", it.toString()) }
            style.primaryColor?.let { put("subtitleStyle.primaryColor", it.toString()) }
            style.outlineColor?.let { put("subtitleStyle.outlineColor", it.toString()) }
            style.outlineWidthPx?.let { put("subtitleStyle.outlineWidthPx", it.toString()) }
            style.shadowColor?.let { put("subtitleStyle.shadowColor", it.toString()) }
            style.shadowOffsetPx?.let { put("subtitleStyle.shadowOffsetPx", it.toString()) }
            style.backgroundColor?.let { put("subtitleStyle.backgroundColor", it.toString()) }
            style.bold?.let { put("subtitleStyle.bold", it.toString()) }
            style.italic?.let { put("subtitleStyle.italic", it.toString()) }
        }
    }

    public companion object {
        /** The version [asProperties] stamps. [fromProperties] also reads every older one. */
        public const val FORMAT_VERSION: Int = 2

        /**
         * Reads what [asProperties] wrote.
         *
         * @throws IllegalArgumentException when the version is missing or not [FORMAT_VERSION],
         *         when a required key is missing, or when a value does not parse.
         */
        public fun fromProperties(properties: Map<String, String>): PlayerMemento {
            val version = properties["version"]?.toIntOrNull()
            // Version 1 knew nothing about balance, the equaliser or any picture and subtitle
            // setting. It reads back with the defaults for those, which is what a player that had
            // never been told about them would have had anyway.
            require(version != null && version in 1..FORMAT_VERSION) {
                "unsupported memento format version $version; this build reads 1 to $FORMAT_VERSION"
            }
            fun need(key: String): String = requireNotNull(properties[key]) { "memento is missing $key" }
            fun tagged(prefix: String): Map<String, String> = properties
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { (key, _) -> key.removePrefix(prefix) }

            val size = need("queue.size").toInt()
            require(size >= 0) { "queue.size must not be negative, was $size" }
            val queue = List(size) { n ->
                MediaItem(
                    uri = need("queue.$n.uri"),
                    headers = tagged("queue.$n.header."),
                    startPosition = properties["queue.$n.startPosition"]?.toLong()?.microseconds,
                    formatHint = properties["queue.$n.formatHint"],
                    openOptions = tagged("queue.$n.option."),
                )
            }
            return PlayerMemento(
                queue = queue,
                queueIndex = need("queueIndex").toInt(),
                position = need("position").toLong().microseconds,
                speed = need("speed").toDouble(),
                preservePitch = need("preservePitch").toBooleanStrict(),
                volume = need("volume").toFloat(),
                muted = need("muted").toBooleanStrict(),
                loop = LoopMode.valueOf(need("loop")),
                shuffle = need("shuffle").toBooleanStrict(),
                subtitleDelay = need("subtitleDelay").toLong().microseconds,
                audioDelay = need("audioDelay").toLong().microseconds,
                audioLanguage = properties["audioLanguage"],
                subtitleLanguage = properties["subtitleLanguage"],
                subtitlesOff = need("subtitlesOff").toBooleanStrict(),
                secondarySubtitleLanguage = properties["secondarySubtitleLanguage"],
                balance = properties["balance"]?.toFloat() ?: 0f,
                equalizer = EqualizerSettings(
                    gainsDb = properties["equalizer.gainsDb"]
                        ?.split(" ")?.filter { it.isNotBlank() }?.map { it.toFloat() }
                        ?: EqualizerSettings.Flat.gainsDb,
                    preampDb = properties["equalizer.preampDb"]?.toFloat() ?: 0f,
                ),
                subtitleScale = properties["subtitleScale"]?.toFloat() ?: 1f,
                subtitlePosition = properties["subtitlePosition"]?.toFloat() ?: 1f,
                subtitleStyle = subtitleStyleFrom(properties),
                videoScale = properties["videoScale"]?.let { VideoScale.valueOf(it) } ?: VideoScale.Fit,
                videoTransform = VideoTransform(
                    aspectOverride = properties["transform.aspectOverride"]?.toFloat(),
                    zoom = properties["transform.zoom"]?.toFloat() ?: 1f,
                    panX = properties["transform.panX"]?.toFloat() ?: 0f,
                    panY = properties["transform.panY"]?.toFloat() ?: 0f,
                ),
                videoAdjustments = VideoAdjustments(
                    brightness = properties["adjust.brightness"]?.toFloat() ?: 0f,
                    contrast = properties["adjust.contrast"]?.toFloat() ?: 1f,
                    saturation = properties["adjust.saturation"]?.toFloat() ?: 1f,
                    hueDegrees = properties["adjust.hueDegrees"]?.toFloat() ?: 0f,
                ),
                renderQuality = RenderQuality(
                    dither = properties["quality.dither"]?.toBooleanStrict() ?: false,
                    deband = properties["quality.deband"]?.toBooleanStrict() ?: false,
                    debandThreshold = properties["quality.debandThreshold"]?.toFloat() ?: 48f,
                    debandRange = properties["quality.debandRange"]?.toFloat() ?: 16f,
                    debandGrain = properties["quality.debandGrain"]?.toFloat() ?: 48f,
                    scaler = properties["quality.scaler"]?.let { VideoScaler.valueOf(it) } ?: VideoScaler.Bilinear,
                    linearLight = properties["quality.linearLight"]?.toBooleanStrict() ?: false,
                ),
                videoEnabled = properties["videoEnabled"]?.toBooleanStrict() ?: true,
            )
        }

        /** Null unless a style was actually stored, so an absent one stays absent. */
        private fun subtitleStyleFrom(
            properties: Map<String, String>,
        ): io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride? {
            if (properties["subtitleStyle.present"]?.toBooleanStrictOrNull() != true) return null
            return io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride(
                fontFamily = properties["subtitleStyle.fontFamily"],
                fontSizePx = properties["subtitleStyle.fontSizePx"]?.toFloat(),
                primaryColor = properties["subtitleStyle.primaryColor"]?.toInt(),
                outlineColor = properties["subtitleStyle.outlineColor"]?.toInt(),
                outlineWidthPx = properties["subtitleStyle.outlineWidthPx"]?.toFloat(),
                shadowColor = properties["subtitleStyle.shadowColor"]?.toInt(),
                shadowOffsetPx = properties["subtitleStyle.shadowOffsetPx"]?.toFloat(),
                backgroundColor = properties["subtitleStyle.backgroundColor"]?.toInt(),
                backgroundPaddingPx = properties["subtitleStyle.backgroundPaddingPx"]?.toFloat() ?: 4f,
                bold = properties["subtitleStyle.bold"]?.toBooleanStrict(),
                italic = properties["subtitleStyle.italic"]?.toBooleanStrict(),
            )
        }
    }
}
