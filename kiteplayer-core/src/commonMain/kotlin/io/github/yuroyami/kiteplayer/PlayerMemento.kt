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
    }

    public companion object {
        /** The version [asProperties] stamps; [fromProperties] refuses any other. */
        public const val FORMAT_VERSION: Int = 1

        /**
         * Reads what [asProperties] wrote.
         *
         * @throws IllegalArgumentException when the version is missing or not [FORMAT_VERSION],
         *         when a required key is missing, or when a value does not parse.
         */
        public fun fromProperties(properties: Map<String, String>): PlayerMemento {
            val version = properties["version"]?.toIntOrNull()
            require(version == FORMAT_VERSION) {
                "unsupported memento format version $version; this build reads version $FORMAT_VERSION"
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
            )
        }
    }
}
