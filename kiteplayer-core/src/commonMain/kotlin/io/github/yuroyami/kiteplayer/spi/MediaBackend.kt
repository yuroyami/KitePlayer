package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.MediaItem

/**
 * Everything needed to read and decode one media item, from one place.
 *
 * A backend is asked to open a media item and answers with a [BackendSession]: the packet cursor plus
 * the decoder factories that go with it. The engine never assembles those pieces itself, and that is
 * the whole point of this interface.
 *
 * The shape it replaces was a bag of independent factories. It could not build a generic session,
 * because it had no audio decoder factory and no subtitle decoder factory, so the one working pipeline
 * in the repository reached the decoders by downcasting the source to the FFmpeg implementation. A
 * downcast in the engine is a backend the engine cannot replace.
 *
 * Nothing is discovered. Kotlin/Native has no classpath service lookup, so there is no such thing as
 * "the platform default found on the classpath": whoever creates the player passes a backend in, or
 * the pipeline cannot be built and says so.
 */
public interface MediaBackend {
    /**
     * Opens [media] and returns the session that reads and decodes it.
     *
     * Called on the engine's demux worker, so the implementation may block on I/O the way libavformat
     * does. Throwing is how a backend refuses: the engine turns the failure into a typed
     * [io.github.yuroyami.kiteplayer.PlaybackError].
     */
    public suspend fun open(media: MediaItem): BackendSession
}

/**
 * One opened media item: the cursor over its packets, and the decoders that can consume them.
 *
 * The factory lists are ordered best first and may be empty, which is how a backend says it cannot
 * decode a kind of stream at all. The engine tries them in order for each selected stream and
 * deselects a stream whose every candidate refuses.
 *
 * Closing the session closes the source and everything the backend opened with it. Decoders the engine
 * created from the factories are the engine's to close, and it closes them before this.
 */
public interface BackendSession : AutoCloseable {
    public val source: PlayerMediaSource
    public val videoDecoders: List<VideoDecoderFactory>
    public val audioDecoders: List<AudioDecoderFactory>

    /**
     * Always empty from every backend here, and read by nothing.
     *
     * The engine's subtitle handler has an empty body this run, so a factory offered here would never be
     * asked for a decoder. Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public val subtitleDecoders: List<SubtitleDecoderFactory>
}
