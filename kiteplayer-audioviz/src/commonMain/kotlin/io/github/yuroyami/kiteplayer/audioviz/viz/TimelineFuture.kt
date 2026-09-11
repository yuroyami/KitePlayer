package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.SpectrumTimeline

/**
 * Lets a drawing see the audio that has been analysed but has not been played yet.
 *
 * A player hands audio to the sound device before anyone hears it, and the device holds it for a
 * fraction of a second. That gap is usually a nuisance: it is why a visualiser has to queue its
 * analyses instead of drawing them at once. Read the other way it is a gift, because everything
 * still in the queue is music the listener has not reached. A drawing can be already moving when
 * a beat arrives rather than starting when it does, which is what a person listening does.
 *
 * [positionMicros] is asked on every frame, so hand it the player's own clock.
 */
@AudioVizAuthoringApi
public fun SpectrumTimeline.asFuture(positionMicros: () -> Long): VizFuture = object : VizFuture {
    override fun at(secondsAhead: Float): SpectrumFrame? = ahead(positionMicros(), secondsAhead)

    override val nextOnsetSeconds: Float get() = nextOnsetSeconds(positionMicros())
}
