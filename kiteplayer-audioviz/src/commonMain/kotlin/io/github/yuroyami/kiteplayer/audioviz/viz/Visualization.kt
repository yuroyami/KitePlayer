package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Detail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Ground
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec

/** The kinds of drawing, kept apart because they feel different to watch. */
public enum class VizFamily {
    /** Literal pictures of the spectrum: bars, traces, meters. */
    BarsAndWaves,

    /** Geometry that answers the beat: spikes, rings, rotation. */
    Battery,

    /** Soft, slow and smeared. These lean on the trail buffer. */
    Ambience,

    /** Abstract fields and flows, further from the spectrum than the rest. */
    Plenoptic,

    /** Colour fields: plasma, cloud and haze. */
    Alchemy,

    /** Straight readings of the signal, drawn as colour rather than as height. */
    MusicalColors,

    /** Drawn in perspective, with the camera moving through the scene. */
    Immersion,

    /** Built on the feedback loop: light adds up, and every mark leaves an echo that moves. */
    Acid,

    /** Built on the per pixel warp: the picture folds through a shape of its own, frame after frame. */
    Warp,

    /** Scenes found by walking a ray out from the eye for every pixel. No geometry, only distances. */
    Raymarch,

    /** A simulated liquid or smoke. The music stirs it, and it keeps moving after the push is over. */
    Fluid,
}

/**
 * How busy a drawing is, so something choosing for the listener can match it to the music.
 *
 * A slow cloud under a drum solo looks broken, and a strobe under a piano ballad looks rude.
 */
public enum class VizEnergy {
    /** Slow, soft, few edges. Right for an intro or a breakdown. */
    Calm,

    /** The everyday middle. Right for most of most songs. */
    Mid,

    /** Fast, hard, bright. Right for a chorus or a drop. */
    High,
}

/**
 * Feedback settings for calm music and for lively music, blended between as the mood moves.
 *
 * A fixed trail makes a drawing look the same through a whole album. Long soft
 * echoes suit a quiet passage and read as smeared mush under a drum break, and the short hard
 * echoes that suit the drum break look lifeless under the quiet passage. So both are declared
 * and the surface picks the mixture.
 *
 * The one rule of the feedback loop still applies at both ends: whatever comes back must total
 * less than one, or the picture multiplies itself and the screen is white inside a second.
 */
@AudioVizAuthoringApi
public class MoodSpec(
    public val calmTrail: Float,
    public val livelyTrail: Float,
    public val calmZoom: Float = 1f,
    public val livelyZoom: Float = 1f,
    public val calmSpin: Float = 0f,
    public val livelySpin: Float = 0f,
    /** How much of the trail's budget goes to the wide, faint copies. */
    public val calmBloomShare: Float = 0.4f,
    public val livelyBloomShare: Float = 0.4f,
    /** How hard the per-pixel warp bends the returning frame. */
    public val calmWarp: Float = 0f,
    public val livelyWarp: Float = 0f,
    /** How far the echoes stream each second, across and down, as shares of the screen. */
    public val calmDriftX: Float = 0f,
    public val livelyDriftX: Float = 0f,
    public val calmDriftY: Float = 0f,
    public val livelyDriftY: Float = 0f,
) {
    public fun trail(mood: Float): Float = mix(calmTrail, livelyTrail, mood).coerceIn(0f, 0.995f)

    public fun zoom(mood: Float): Float = mix(calmZoom, livelyZoom, mood)

    public fun spin(mood: Float): Float = mix(calmSpin, livelySpin, mood)

    public fun bloomShare(mood: Float): Float = mix(calmBloomShare, livelyBloomShare, mood)

    public fun warp(mood: Float): Float = mix(calmWarp, livelyWarp, mood)

    public fun driftX(mood: Float): Float = mix(calmDriftX, livelyDriftX, mood)

    public fun driftY(mood: Float): Float = mix(calmDriftY, livelyDriftY, mood)

    private fun mix(calm: Float, lively: Float, mood: Float): Float =
        calm + (lively - calm) * mood.coerceIn(0f, 1f)
}

/**
 * How the last frame comes back this frame: zoomed ([zoomX], [zoomY] per sixtieth of a second),
 * turned ([spin], radians a second) about a centre that can move, and drifted (shares of the
 * screen a second). [copy] adds a second, fainter, turned or mirrored copy.
 */
@AudioVizAuthoringApi
public class EchoFrame(
    public val zoomX: Float = 1f,
    public val zoomY: Float = zoomX,
    public val spin: Float = 0f,
    public val driftX: Float = 0f,
    public val driftY: Float = 0f,
    public val centreX: Float = 0.5f,
    public val centreY: Float = 0.5f,
    public val copy: EchoCopy? = null,
)

/**
 * A second copy of the last frame, scaled by [zoom], turned by [angle] radians and mirrored.
 * [share] is taken out of the trail, so what comes back still totals less than one.
 */
@AudioVizAuthoringApi
public class EchoCopy(
    public val zoom: Float = 1f,
    public val angle: Float = 0f,
    public val mirrorX: Boolean = false,
    public val mirrorY: Boolean = false,
    public val share: Float = 0.3f,
)

/** How the echo layer is laid over the ground: covering it, or adding its light to it. */
@AudioVizAuthoringApi
public enum class EchoBlend { Over, Add }

/**
 * Everything a drawing needs to know about this instant.
 *
 * Two clocks live here on purpose. [timeSeconds] is the wall clock and never stops. [musicTime]
 * only moves while there is something to listen to, so anything driven by it stands still in a
 * silence instead of drifting on alone.
 */
@AudioVizAuthoringApi
public class VizRenderState(
    /** The analysed audio, already lined up with what is audible. */
    public val frame: SpectrumFrame,
    /** Seconds since this visualiser started. Use it for anything that must never stop. */
    public val timeSeconds: Float,
    /** Seconds since the previous drawn frame. Move particles by this, not by a fixed step. */
    public val deltaSeconds: Float,
    public val palette: VizPalette,
    /**
     * Seconds of music, not of clock: it advances with how much is playing. Prefer this for
     * anything whose speed should belong to the song.
     */
    public val musicTime: Float = timeSeconds,
    /** The queued audio that has been analysed but not yet heard, when a player supplies one. */
    public val future: VizFuture? = null,
) {
    /** Calm at 0, lively at 1. */
    public val mood: Float get() = frame.mood

    /** The other way round, because half the uses want it that way. */
    public val calm: Float get() = 1f - frame.mood

    /** How loud this moment is for this song, 0 to 1. */
    public val energy: Float get() = frame.energy

    /**
     * How hard the music is pushing: loud for this song AND busy.
     *
     * Use this for speed. [energy] alone is not enough, because it is measured against the song's
     * own range, so the loudest moment of a lullaby scores the same as the loudest moment of a
     * drum track. That is right for deciding how big to draw something, and wrong for deciding
     * how fast to fly: it would send the camera hurtling through a ballad. Mixing in [mood], which
     * knows how often things are happening, keeps a calm song calm while still letting it breathe.
     */
    public val drive: Float get() = (0.30f * frame.energy + 0.35f * frame.mood +
        0.20f * frame.density + 0.15f * frame.kickPulse).coerceIn(0f, 1f)

    /** Weight and attack are separate from section mood: a kick moves the picture NOW. */
    public val bassMotion: Float get() = (0.68f * frame.bassRel + 0.32f * frame.kickPulse).coerceIn(0f, 1f)

    /** Sustained voices/instruments bend surfaces instead of triggering a drum flash. */
    public val body: Float get() = (0.7f * frame.midRel + 0.3f * frame.loudShort).coerceIn(0f, 1f)

    /** Fine detail follows high frequencies and short cymbal envelopes. */
    public val air: Float get() = (0.65f * frame.trebleRel + 0.35f * frame.hatPulse).coerceIn(0f, 1f)

    /** Texture density, independent of how loud the track was mastered. */
    public val texture: Float get() = (0.35f * frame.density + 0.35f * frame.flatness +
        0.20f * frame.energy + 0.10f * frame.width).coerceIn(0f, 1f)

    /** Audible presence; quiet sustained music should still occupy a scene. */
    public val presence: Float get() = ((frame.level - 0.08f) / 0.32f).coerceIn(0f, 1f)

    /**
     * A speed, scaled by how lively the music is.
     *
     * This is the most important line in the library. How fast something moves is the strongest
     * signal a viewer has for how busy the music is, so a rate that ignores the music makes a ballad and a drum track look alike
     * however carefully the sizes are mapped. A slow idle drift keeps silence alive; short
     * energy and kick envelopes respond immediately while section mood changes more gradually.
     */
    public fun paced(base: Float): Float = base * frame.motionRate

    /** The frame that will be audible [seconds] from now, or this one when nothing is queued. */
    public fun ahead(seconds: Float): SpectrumFrame = future?.at(seconds) ?: frame

    /** Seconds until the next onset that is already in the queue, or -1 when it is not known. */
    public val nextOnsetIn: Float get() = future?.nextOnsetSeconds ?: -1f

    /**
     * How close the next onset is, 1 right on it and 0 further away than [window].
     *
     * A drawing that swells over this is already moving when the beat arrives, the way a dancer
     * is. Falls back to the beat the tempo tracker predicts when no queue is attached, and to
     * nothing at all when there is no tempo either.
     */
    public fun anticipation(window: Float = 0.15f): Float {
        val queued = nextOnsetIn
        val seconds = if (queued >= 0f) queued else frame.beatInSeconds
        if (seconds < 0f || seconds > window) return 0f
        return 1f - seconds / window
    }

    /** The value that [fraction] of this frame's bars sit below. See [SpectrumFrame.bandPercentile]. */
    public fun percentile(fraction: Float): Float = frame.bandPercentile(fraction)
}

/** A window onto audio that has been analysed but not yet played. */
@AudioVizAuthoringApi
public interface VizFuture {
    /** The frame [secondsAhead] from what is audible now, or null when the queue is too short. */
    public fun at(secondsAhead: Float): SpectrumFrame?

    /** Seconds until the next queued onset, or -1 when there is none in the queue. */
    public val nextOnsetSeconds: Float
}

/**
 * One drawing.
 *
 * Instances hold their own state, so a visualisation with particles or trails keeps them between
 * frames and clears them in [reset]. Create one per use rather than sharing.
 *
 * [draw] is declared on [DrawScope] so a body reads like ordinary canvas code. Call it as
 * `with(visualization) { draw(state) }` from inside a canvas.
 */
public interface Visualization {

    public val name: String

    public val family: VizFamily

    /** How busy this drawing is, so something choosing for the listener can match it to the music. */
    public val bucket: VizEnergy get() = VizEnergy.Mid

    /** Different settings for calm and lively music. Overrides [trail], [feedbackZoom], [feedbackSpin]. */
    public val moodSpec: MoodSpec? get() = null

    /**
     * How the previous frame is bent on its way back, per pixel.
     *
     * Null keeps the plain behaviour: the frame returns scaled and turned, which is all a whole
     * picture can be put through in one go. A [WarpSpec] lets every pixel work out where it came
     * from separately, which is what turns the feedback loop from an echo into a shape.
     *
     * Declare it as a property rather than a getter. The shader behind it is compiled once and
     * kept, and a getter would build a new one sixty times a second.
     */
    public val warp: WarpSpec? get() = null

    /**
     * How much of the previous frame survives, 0 to just under 1.
     *
     * Zero clears the canvas every frame. Higher values leave the last frame faintly behind, which
     * is what turns a moving dot into a streak. Above about 0.95 the picture never fully clears.
     */
    public val trail: Float get() = moodSpec?.trail(0.5f) ?: 0f

    /**
     * How much the surviving frame grows before the new one is drawn on top. 1 leaves it alone.
     *
     * This is the difference between a smear and a trip. A frame that comes back slightly larger
     * every time turns every mark into a tunnel rushing at the viewer, because the mark's own echo
     * keeps expanding behind it. Below 1 the echoes fall inward instead, which reads as being
     * pulled down a drain. Small numbers go a long way: 1.02 is already strong.
     */
    public val feedbackZoom: Float get() = moodSpec?.zoom(0.5f) ?: 1f

    /** Radians per second the surviving frame turns by. Echoes spiral instead of expanding straight. */
    public val feedbackSpin: Float get() = moodSpec?.spin(0.5f) ?: 0f

    /**
     * Extra copies of the surviving frame at slightly different sizes, which reads as light bleed.
     *
     * A real blur means sampling every pixel's neighbours. Drawing the same frame two or three
     * times, each a little larger and fainter, costs three blits instead and looks close enough
     * once it is moving. Zero is off.
     */
    public val bloom: Int get() = 0

    /** The trail for this mood. The surface calls this rather than reading [trail]. */
    public fun trailAt(mood: Float): Float = moodSpec?.trail(mood) ?: trail

    /** The feedback zoom for this mood. */
    public fun feedbackZoomAt(mood: Float): Float = moodSpec?.zoom(mood) ?: feedbackZoom

    /** The feedback spin for this mood. */
    public fun feedbackSpinAt(mood: Float): Float = moodSpec?.spin(mood) ?: feedbackSpin

    /** How much of the trail's budget the wide copies take, for this mood. */
    public fun bloomShareAt(mood: Float): Float = moodSpec?.bloomShare(mood) ?: 0.4f

    /**
     * The finishing pass over this drawing: glow around bright parts, darker corners, a little
     * grain. Drawings that already glow through the feedback loop get a gentler version, so the two
     * do not stack up into a haze.
     */
    public val post: PostSpec get() = when {
        bloom > 0 && bucket == VizEnergy.Calm -> PostSpec.SoftCalm
        bloom > 0 -> PostSpec.Soft
        bucket == VizEnergy.Calm -> PostSpec.DefaultCalm
        else -> PostSpec.Default
    }

    /** Settings a person may change while it runs. Most drawings have none. */
    public val params: List<VizParam> get() = emptyList()

    /** A live field drawn under everything else. Null keeps the plain background colour. */
    public val ground: Ground? get() = null

    /** A fine moving texture added over everything, the smallest of the three scales. Null for none. */
    public val detail: Detail? get() = null

    /** True for drawings that paint every pixel themselves, such as the full-screen shaders. */
    public val paintsWholeScreen: Boolean get() = false

    /** How the echo layer is laid over the ground. */
    public val echoBlend: EchoBlend get() = EchoBlend.Over

    /** How the last frame comes back this frame. The default reads the zoom, spin and drift for the mood. */
    public fun echo(state: VizRenderState): EchoFrame {
        val mood = state.frame.mood
        val zoom = feedbackZoomAt(mood)
        return EchoFrame(
            zoomX = zoom,
            spin = feedbackSpinAt(mood),
            driftX = moodSpec?.driftX(mood) ?: 0f,
            driftY = moodSpec?.driftY(mood) ?: 0f,
        )
    }

    /** Where this drawing's actors are on screen, as shares. The first is the main one. */
    public val anchors: List<Anchor> get() = emptyList()

    /** The parts of the recipe the song changes. Null for a drawing with none. */
    public val genes: Genes? get() = null

    /** Drawn after the echo layer, straight on the canvas, for things that must stay sharp. */
    public fun DrawScope.drawFront(state: VizRenderState) {}

    /**
     * The echo layer as it stood a frame ago, handed over just before [draw], or null when there is no
     * echo buffer yet. Most drawings ignore it; one that breaks the picture apart cuts its pieces from it.
     */
    public fun onPreviousFrame(picture: ImageBitmap?) {}

    /**
     * The ways this drawing can arrive. The director picks among these and what suits the music. A
     * drawing with no trail has no echoes to inherit, so the hand-off is only on offer to drawings
     * that feed their frames back.
     */
    public val transitions: List<VizTransition>
        get() = if (moodSpec != null || trail > 0f || warp != null) VizTransition.entries else WITHOUT_ECHOES

    /** Drops any carried state. Called when this drawing is shown. */
    public fun reset() {}

    public fun DrawScope.draw(state: VizRenderState)
}

private val WITHOUT_ECHOES: List<VizTransition> = VizTransition.entries - VizTransition.WarpHandoff

/**
 * Resets a drawing and what its warp keeps between frames, so a drawing shown a second time starts
 * the way it did the first time rather than from the music it last heard.
 */
internal fun Visualization.restart() {
    reset()
    warp?.restart()
}
