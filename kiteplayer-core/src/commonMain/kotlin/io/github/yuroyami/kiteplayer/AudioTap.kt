package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat

/**
 * Sees every block of decoded audio on its way to the speaker.
 *
 * For a level meter, a spectrum or a visualiser: anything that wants the sound the player is
 * actually playing, with the time each block plays at. Attach one with [KitePlayer.attachAudioTap].
 *
 * The engine calls it on the audio feed worker, once per decoded block, after a seek has trimmed
 * the block that straddles its target and before any conversion. So the samples are in the
 * decoder's own rate and layout, and the time is on the media's own axis at any playback speed.
 *
 * That worker also feeds the audio device. A tap that takes long starves the device and the sound
 * breaks up, so copy what you need and return. A tap that throws is detached and reported as
 * [PlaybackWarning.AudioTapFailed], and playback carries on.
 */
public interface AudioTap {

    /**
     * One block of decoded audio that plays at [pts].
     *
     * [interleaved] holds [frames] sample frames of [AudioFormat.channels] floats each, from index
     * zero. It is the engine's own array and it is reused for the next block, so copy what you keep.
     */
    public fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat)

    /**
     * Whatever the tap holds is stale: the player sought, changed the audio track or opened a new
     * audio path, and the next [onAudio] continues from somewhere else. It may arrive on another
     * thread than [onAudio].
     */
    public fun onDiscontinuity() {}
}
