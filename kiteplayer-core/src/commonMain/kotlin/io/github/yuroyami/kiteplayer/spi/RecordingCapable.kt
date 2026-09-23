package io.github.yuroyami.kiteplayer.spi

/**
 * A [PlayerMediaSource] that can copy the packets it reads into a file while it plays.
 *
 * Optional. The engine looks for it on the source, and a source without it makes
 * [io.github.yuroyami.kiteplayer.KitePlayer.startRecording] throw.
 *
 * The engine calls these members from its own thread while another thread reads packets, so an
 * implementation guards its recording state itself. The engine ends a recording before it seeks
 * or retires the session, so an implementation never has to put a jump into a file.
 */
public interface RecordingCapable {
    /** The file a recording is being written to now, or null when no recording runs. */
    public val recordingPath: String?

    /**
     * Starts copying the packets of the streams this source reads into a Matroska file at [path],
     * with no re-encode.
     *
     * @throws IllegalStateException when a recording already runs.
     * @throws IllegalArgumentException when the file at [path] cannot be created.
     * @throws UnsupportedOperationException when this platform cannot write the file.
     */
    public fun startRecording(path: String)

    /**
     * Stops the recording and finishes the file. Does nothing when no recording runs. Closing the
     * source does the same.
     *
     * An exception means the file could not be finished. The recording has ended either way.
     */
    public fun stopRecording()
}
