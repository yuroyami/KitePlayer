@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.internal.SubtitleTypesetterRegistry
import io.github.yuroyami.kiteplayer.internal.platformSubtitleTypesetterProviders

/**
 * A typesetting engine for ASS and SSA subtitles, libass in practice.
 *
 * The Kotlin dialogue tier in `kiteplayer-subtitles` reads styles and the common override tags
 * and hands text cues to a platform rasterizer. Full typesetting is a different job: a sign that
 * moves with the camera, a karaoke line filling syllable by syllable, rotated and clipped text.
 * That needs the whole script, every event, and a render per video frame. This interface is that
 * job, expressed in the engine's own vocabulary: events go in, positioned overlay images come out.
 *
 * ## Threading
 *
 * The engine calls every member from one serial lane and never concurrently. An implementation
 * needs no lock of its own. `close` is also called on that lane, after the last render.
 *
 * ## Lifetime of the pixels
 *
 * The images returned by [render] are owned by the caller from that moment on. An implementation
 * must not reuse or mutate their bytes afterwards, because a renderer reads them on its own thread
 * for as long as that overlay is on screen.
 */
public interface SubtitleTypesetter : AutoCloseable {

    /**
     * Starts a fresh track from a container's codec header: the `[Script Info]` and styles
     * sections a Matroska ASS track carries as codec private data. Events arrive through
     * [addEvent]. Any earlier track is discarded; fonts survive.
     */
    public fun openTrack(header: ByteArray)

    /**
     * Starts a fresh track from a whole script, header and events together. This is how an
     * external `.ass` file is loaded. Any earlier track is discarded; fonts survive.
     */
    public fun openDocument(script: ByteArray)

    /**
     * Adds one container event in the Matroska packet form: `ReadOrder,Layer,Style,Name,MarginL,
     * MarginR,MarginV,Effect,Text`. Timing is the packet's, in milliseconds on the media timeline.
     * Events are deduplicated by ReadOrder, so re-feeding after a seek is safe.
     */
    public fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long)

    /** Drops every event of the open track. The header and the fonts stay. Called on a seek. */
    public fun clearEvents()

    /**
     * Adds one font, by file name and bytes, for the engine to shape with. Fonts a container
     * attaches and fonts an application configures both arrive here.
     */
    public fun addFont(name: String, data: ByteArray)

    /**
     * Renders the open track at [timeMillis] for [frame].
     *
     * Returns null when nothing changed since the previous render, which is the common answer
     * during a static line and what keeps a per-frame cadence cheap. Returns an empty list when
     * the picture changed to nothing, and a list of images positioned in the frame's own output
     * pixels otherwise.
     */
    public fun render(timeMillis: Long, frame: TypesetFrame): List<OverlayImage>?

    override fun close()
}

/**
 * The geometry one [SubtitleTypesetter.render] call draws into.
 *
 * [width] and [height] are the renderer's output surface, the space overlays are composited in.
 * [videoWidth] and [videoHeight] are the picture's own display size, which the script's play
 * resolution is scaled against. The margins are the letterbox or pillarbox bars around the fitted
 * picture inside the surface: the typesetter positions text relative to the picture, and events
 * that ask for it may spill into the bars.
 *
 * [fontScale] is the viewer's size multiplier over the authored size. [linePosition] is where the
 * default bottom stack anchors as a fraction of the picture height: 1.0 is the authored bottom
 * edge, 0.5 mid-screen. Both are the same knobs the Kotlin tier honours.
 */
public data class TypesetFrame(
    val width: Int,
    val height: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val marginTop: Int = 0,
    val marginBottom: Int = 0,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    val fontScale: Float = 1f,
    val linePosition: Float = 1f,
) {
    init {
        require(width > 0 && height > 0) { "a typeset frame needs a positive surface, got ${width}x$height" }
        require(videoWidth > 0 && videoHeight > 0) {
            "a typeset frame needs a positive video size, got ${videoWidth}x$videoHeight"
        }
        require(fontScale.isFinite() && fontScale > 0f) { "fontScale must be finite and positive, was $fontScale" }
        require(linePosition.isFinite()) { "linePosition must be finite, was $linePosition" }
    }
}

/**
 * An optional typesetting module's discovery entry. Construction must do no work: providers are
 * instantiated on every player that meets an ASS track, and only [create] may load a library or
 * scan fonts. Returning null from [create] means this build cannot typeset here, for example a
 * desktop JVM with no native library for its operating system; the engine then keeps the Kotlin
 * tier and says so once through a warning.
 */
@KitePlayerLowLevelApi
public interface SubtitleTypesetterProvider {
    /** Stable unique identifier. With several installed, the lowest identifier wins. */
    public val id: String

    /** Creates one typesetter for one player session, or null when this platform build has none. */
    public fun create(): SubtitleTypesetter?
}

/**
 * Registration seam for optional typesetting modules on targets without classpath service
 * discovery. Applications normally only add `kiteplayer-libass` and never call this. Thread-safe.
 * Registering two different providers under one identifier refuses with an error.
 */
@KitePlayerLowLevelApi
public object SubtitleTypesetters {
    private val registry = SubtitleTypesetterRegistry()
    private val discovered: Unit by lazy {
        platformSubtitleTypesetterProviders().forEach(registry::register)
    }

    /** Installs a provider. Re-registering the same instance is harmless. */
    public fun register(provider: SubtitleTypesetterProvider): Unit = registry.register(provider)

    /** The identifiers of every installed provider, lowest first. Empty means the Kotlin tier draws ASS. */
    public fun installed(): List<String> {
        discovered
        return registry.ids()
    }

    internal fun select(): SubtitleTypesetterProvider? {
        discovered
        return registry.first()
    }

    /** Test seam: forgets every registered provider. Platform discovery is not re-run. */
    internal fun resetForTesting(): Unit = registry.clear()
}
