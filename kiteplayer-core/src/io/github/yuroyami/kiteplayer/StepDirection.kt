package io.github.yuroyami.kiteplayer

/** Which way [KitePlayer.stepFrame] moves a paused picture. */
public enum class StepDirection {
    /** To the next decoded frame, which is already queued, so no seek is needed. */
    Forward,

    /**
     * To the last frame before the one on screen. The engine seeks to the keyframe before it and
     * decodes forward, so in a file with keyframes far apart one step can decode every frame
     * between two keyframes.
     */
    Backward,
}
