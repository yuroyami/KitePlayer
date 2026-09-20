package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

/**
 * An audio value a drawing reads.
 *
 * Each driver has one source, one range and one rule for what a drawing sees when it is missing.
 * The energy drivers are heights under the one shared gain, so a fixed level means the same thing
 * in every song.
 */
@AudioVizAuthoringApi
public enum class VizDriver {
    /** Fast overall height of the mix. Zero in silence. */
    Level,

    /** Slow overall height, with a 100 ms rise and a 2 s fall. Zero in silence. */
    SlowLevel,

    /** The fast height of each band and the falling caps above them. All zero in silence. */
    Bands,

    /** Fast height below 250 Hz. Zero in silence. */
    Bass,

    /** Fast height from 250 Hz to 2 kHz. Zero in silence. */
    Mid,

    /** Fast height above 2 kHz. Zero in silence. */
    Treble,

    /** The waveform traces. A flat line before a complete window. */
    Waveform,

    /** How wide the stereo image is. Zero, which is mono, when it is not known. */
    Width,

    /** Where the weight of the spectrum sits and how spread out it is. Zero when not known. */
    Timbre,

    /** The key's hue, weighted by its confidence. Weight zero leaves the palette its own colours. */
    Key,

    /** Section liveliness: how loud and how busy, over bars. Zero, which is calm, at the start. */
    Mood,

    /** Low transient hits and the pulse they leave. Nothing without hits. */
    LowHit,

    /** Body transient hits and the pulse they leave. Nothing without hits. */
    BodyHit,

    /** High transient hits and the pulse they leave. Nothing without hits. */
    HighHit,

    /** Broadband onset hits and the pulse they leave. Nothing without hits. */
    Onset,

    /** The usable pulse rate and phase. Without one, visual cycles run free at a rate set by [Mood]. */
    Pulse,

    /** An accepted section boundary. Without one the scene holds. */
    Section,

    /** An accepted drop. Without one the scene holds. */
    Drop,

    /** An accepted breakdown. Without one the scene holds. */
    Breakdown,
}

/**
 * A part of the picture that a driver moves.
 *
 * [Shape], [Spawn], [Camera] and [Cut] are measured as a change of the picture that the driver
 * causes. The qualification does not tell a camera move from a change of shape.
 */
@AudioVizAuthoringApi
public enum class VizProperty {
    /** How big shapes are, or how much of the screen they cover. */
    Size,

    /** How much light the picture gives, including glow. */
    Brightness,

    /** Hue and saturation. */
    Colour,

    /** How fast things move. */
    Speed,

    /** Where things are, or the shape they take. */
    Shape,

    /** New things appear: particles, rings, splashes. */
    Spawn,

    /** The view pans, zooms, turns or shakes. */
    Camera,

    /** A discrete change of state: a direction flip, a jump, a new recipe. */
    Cut,

    /** Fine detail over the picture. */
    Texture,
}

/** The fixed transform from a driver to a property. Nothing here rescales itself as a song plays. */
@AudioVizAuthoringApi
public sealed class VizCurve {
    /** The property follows the driver in proportion over the driver's whole range. */
    public data object Linear : VizCurve()

    /** For a hit: the response scales with the hit's strength. */
    public data object Scaled : VizCurve()

    /** For an event: a response whose size does not follow the event's strength. */
    public data object Discrete : VizCurve()

    /** A declared artistic range: nothing below [from], all of it above [to]. */
    public class Range(public val from: Float, public val to: Float) : VizCurve() {
        override fun toString(): String = "Range($from, $to)"
    }

    /** On above a fixed height, off below it. */
    public class Threshold(public val level: Float) : VizCurve() {
        override fun toString(): String = "Threshold($level)"
    }
}

/** How a drawing's own timing shapes a response. This is on top of the analysis envelopes. */
@AudioVizAuthoringApi
public enum class VizResponseKind {
    /** The property follows the driver in the same frame. */
    Direct,

    /** A first-order rise and fall in the drawing. */
    Envelope,

    /** Damped physics, such as a spring. */
    Spring,

    /** The driver sets a rate and the property is its running sum. */
    Rate,

    /** Spawned things live for a while and then go. */
    Lifetime,
}

/**
 * The timing of one response: what shapes it, how long it takes, and any deliberate wait.
 *
 * [seconds] is the rise constant of an envelope, the settle time of a spring, or the lifetime of
 * spawned things. [delaySeconds] is a wait the drawing adds on purpose, such as holding a
 * response for the next visual cycle. Every other response starts in the frame that carries the
 * change.
 */
@AudioVizAuthoringApi
public class VizResponse(
    public val kind: VizResponseKind,
    public val seconds: Float = 0f,
    public val delaySeconds: Float = 0f,
) {
    override fun toString(): String = "${kind.name}($seconds, delay=$delaySeconds)"

    public companion object {
        public val Direct: VizResponse = VizResponse(VizResponseKind.Direct)
        public val Rate: VizResponse = VizResponse(VizResponseKind.Rate)
        public fun envelope(seconds: Float, delaySeconds: Float = 0f): VizResponse =
            VizResponse(VizResponseKind.Envelope, seconds, delaySeconds)
        public fun spring(seconds: Float, delaySeconds: Float = 0f): VizResponse =
            VizResponse(VizResponseKind.Spring, seconds, delaySeconds)
        public fun lifetime(seconds: Float, delaySeconds: Float = 0f): VizResponse =
            VizResponse(VizResponseKind.Lifetime, seconds, delaySeconds)
    }
}

/** One driver moving one property, through one fixed curve, with one response timing. */
@AudioVizAuthoringApi
public class VizDrive(
    public val driver: VizDriver,
    public val property: VizProperty,
    public val curve: VizCurve = VizCurve.Linear,
    public val response: VizResponse = VizResponse.Direct,
) {
    override fun toString(): String = "${driver.name} -> ${property.name} $curve $response"
}

/** What a drawing needs from the host to draw as intended. */
@AudioVizAuthoringApi
public enum class VizNeed {
    /** The whole picture is one runtime shader. Without them the drawing shows its stand-in. */
    RuntimeShader,

    /** A ground, a detail layer or a per-pixel warp uses runtime shaders. The rest draws without them. */
    ShaderLayers,

    /** The drawing feeds its frames back through an offscreen buffer. */
    EchoBuffer,

    /** That buffer runs at a reduced scale by design, because the drawing is soft. */
    SoftBuffer,
}

/** What a drawing does when there is nothing to hear. */
@AudioVizAuthoringApi
public enum class VizSilence {
    /** A restrained motion continues, well below what the music produces. */
    Idle,

    /** The picture settles and holds. */
    Still,

    /** The picture fades towards the background. */
    Fade,
}

/** What a drawing can give up under load. An empty list means the cost is fixed. */
@AudioVizAuthoringApi
public enum class VizQualityControl {
    /** The echo buffer may shrink while the sharp front stays at the output's resolution. */
    EchoResolution,

    /** Spawned things are drawn from a fixed pool, so their number has a ceiling. */
    BoundedPool,
}

/**
 * What a drawing does with the audio, in a form a test and a menu can read.
 *
 * The drives say which audio value moves which part of the picture, through which fixed curve and
 * with which timing. `DriverInjectionTest` renders the drawing twice, with one driver changed, and
 * checks that the declared part answers. A driver that moves the picture as much as the declared
 * ones and is not listed fails the same test, so a declaration cannot hide a response.
 */
@AudioVizAuthoringApi
public class VizMapping(
    public val drives: List<VizDrive>,
    public val needs: Set<VizNeed> = emptySet(),
    public val silence: VizSilence = VizSilence.Idle,
    public val quality: List<VizQualityControl> = emptyList(),
    /** Seconds of silence after which the silence comparison starts. */
    public val silenceSettleSeconds: Float = 2f,
) {
    /** The drives of one driver, in declaration order. */
    public fun of(driver: VizDriver): List<VizDrive> = drives.filter { it.driver == driver }

    /** Every driver this drawing declares. */
    public val drivers: Set<VizDriver> get() = drives.mapTo(LinkedHashSet()) { it.driver }
}
