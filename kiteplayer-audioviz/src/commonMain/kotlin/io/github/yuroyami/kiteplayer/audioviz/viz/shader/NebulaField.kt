package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import io.github.yuroyami.kiteplayer.audioviz.viz.vividColour
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thin orchid and acid green filaments braid across deep space. A small white core star lights them
 * from inside, dark dust lanes cut across them, and round stars drift behind them on three depths.
 * The bass swells the gas and thickens the strands. The mids light the green filaments, and the treble
 * adds fine detail and sparks that run along the strands. A kick flares the core and pushes the strands
 * out, a snare sends a comet across, and a hat makes the stars twinkle. Each section folds the gas into
 * a new shape over one bar, and a breakdown draws it in toward the core. A drop sends a white ring out
 * from the core, and every filament it passes stays brighter.
 */
internal class NebulaField : ShaderPreset(
    source = SOURCE,
    name = "Nebula Field",
    bucket = VizEnergy.Calm,
    seed = 19f,
    kit = Kit(seed = 150_474L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 150_474)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Bass, VizProperty.Shape),
        VizDrive(VizDriver.Mid, VizProperty.Brightness),
        VizDrive(VizDriver.Treble, VizProperty.Texture),
        VizDrive(VizDriver.Treble, VizProperty.Spawn),
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.6f)),
        // A comet enters from beyond an edge, so it shows a moment after the snare.
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(2f, delaySeconds = 0.3f)),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.25f)),
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(2f, delaySeconds = 0.5f)),
        VizDrive(VizDriver.Drop, VizProperty.Brightness, VizCurve.Discrete,
            VizResponse.envelope(2f, delaySeconds = 0.5f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(2f, delaySeconds = 0.5f)),
    )

    // Only the hottest parts glow: the core, the crossings and the shock ring. A wider bloom would
    // soften the thin strands the drawing is made of.
    override val post: PostSpec get() = GLOW

    // The shader ignores the camera, so the sparks and comets must too, or they leave the strands.
    override val frontParallax: Float get() = 0f

    private val reach = genes.number("Reach", 0.85f, 1.15f, 1f)
    private val braiding = genes.number("Braid", 0.7f, 1.3f, 1f)
    private val dustWidth = genes.number("Dust", 0.75f, 1.35f, 1f, mostAtMax = false)
    private val starShare = genes.number("Stars", 0.8f, 1.25f, 1f, mostAtMax = false)
    private val corePath = genes.choice("Core path", 2)

    // The processor's copies of the gas and its history, and the sparks that run on them. Internal so
    // a test can hold them to the picture the shader draws.
    internal val gas = GasField()
    internal val past = SpectrumPast()
    internal val sparks = Sparks(SPARKS, seed = 3_119L)
    private val recipes = FoldRecipes(seed = 1_907L)
    private val comets = Travellers(2)
    private val flare = Spring(stiffness = 120f, damping = 0.5f)
    private val keyTurn = Slew(maxPerSecond = 20f)

    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var corePhase = 0f
    private var push = 0f
    private var sinceComet = 99f
    private var cometGlow = 0f
    private var twinkle = 0f
    private var twinkleSeed = 0f
    private var breakdown = false
    private var calm = 0f
    private var charge = 0f
    private var shock = -1f
    private var shockSeconds = 2f
    private var afterglow = 0f
    private var farDrift = 0f
    private var middleDrift = 0f
    private var nearDrift = 0f
    private var light = 1f
    private var gasLight = 1f
    private var filamentLight = 1f
    private var starLight = 1f
    private var motion = 1f
    private var pixelsHigh = 600f

    private val orchid = FloatArray(3)
    private val violet = FloatArray(3)
    private val green = FloatArray(3)
    private var threadColour = Color.White
    private var colourFor: VizPalette? = null
    private var colourTurn = Float.NaN

    init {
        recipes.reset()
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val step = state.stepSeconds
        val frame = state.frame
        val aspect = kit.aspect
        motion = state.motionScale
        bass += (state.bassMotion - bass) * (1f - exp(-dt / 0.12f))
        mid += (state.body - mid) * (1f - exp(-dt / 0.15f))
        treble += (state.air - treble) * (1f - exp(-dt / 0.2f))

        // A breakdown lasts until the next turn or drop, and draws the gas in toward the core.
        if (gestures.breakdown) {
            breakdown = true
        } else if (gestures.turn || gestures.surge) {
            breakdown = false
        }
        calm += ((if (breakdown) 1f else 0f) - calm) * (1f - exp(-step / (gestures.cycleSeconds * 0.5f)))

        // Each turn brings a new fold recipe, which morphs in over one bar.
        if (gestures.turn) recipes.next(gestures.cycleSeconds)
        recipes.advance(step)

        // The three waves inside the recipe drift with the music and stop when it stops.
        gas.waveOne += dt * state.paced(0.9f)
        gas.waveTwo += dt * state.paced(0.7f)
        gas.waveThree += dt * state.paced(0.5f)

        // The core wanders slowly inside the nebula, one lap every eight bars at most.
        corePhase += step * TAU / (8f * gestures.cycleSeconds) * (0.25f + 0.75f * state.drive)
        val eight = corePath.weight(1)
        gas.coreX = CORE_X + 0.22f * sin(corePhase)
        gas.coreY = CORE_Y + 0.1f * (cos(corePhase) * (1f - eight) + sin(2f * corePhase) * eight)

        // A kick flares the core and sends a soft pressure wave out through the gas.
        flare.kick(gestures.kick * 6f)
        flare.advance(dt)
        if (gestures.kick > 0f) {
            gas.pushRadius = 0.04f
            push = gestures.kick
        }
        gas.pushRadius += step * PUSH_SPEED
        push *= exp(-dt / 0.7f)

        // The drop: the future window starts the build-up early, and the surge sends the ring.
        val upcoming = state.future?.nextEvent(AudioEventKind.Drop)
        val building = if (upcoming != null && upcoming.secondsUntil in 0f..BUILD_SECONDS &&
            upcoming.event.detection.confidence >= 0.6f) 1f - upcoming.secondsUntil / BUILD_SECONDS else 0f
        charge += (building - charge) * (1f - exp(-dt / 0.25f))
        if (gestures.surge) {
            shock = 0f
            shockSeconds = gestures.cycleSeconds
            charge = 0f
            afterglow = 1f
        }
        if (shock >= 0f) {
            shock += step / shockSeconds
            if (shock >= 1f) shock = -1f
        } else {
            afterglow = (afterglow - step / (4f * gestures.cycleSeconds)).coerceAtLeast(0f)
        }

        // A snare sends a comet across, at most one a bar.
        sinceComet += dt
        if (gestures.snare > 0f && sinceComet >= gestures.cycleSeconds && frame.audible > 0f) {
            comets.across(random, gestures.cycleSeconds * 0.9f, PathShape.Arc, 0.12f * random.signed(), 0.012f, 0f)
            sinceComet = 0f
        }
        comets.advance(if (frame.held) 0f else dt)

        // A hat lights a new handful of stars.
        if (gestures.hat > 0f) {
            twinkle = maxOf(twinkle, 0.9f * gestures.hat)
            twinkleSeed = random.next() * 97f
        }
        twinkle *= exp(-dt / 0.25f)

        // The stars drift on three depths, the nearer ones faster.
        val drift = dt * state.paced(1f)
        farDrift += drift * 0.006f
        middleDrift += drift * 0.013f
        nearDrift += drift * 0.024f

        // The level moves the light. A silence holds it at about a third instead of letting it go dark.
        val audible = frame.audible
        light = maxOf(state.lightScale * 0.15f + 0.85f * state.lift, state.lightScale * SILENT_LIGHT * (1f - audible))
        gasLight = (1f - 0.45f * calm) * (1f + 0.3f * charge)
        // The mids light the strands. A silence holds them at a middle reading rather than dark.
        filamentLight = 0.7f + 0.7f * (mid * audible + 0.45f * (1f - audible))
        starLight = state.lightScale * (0.6f + 0.4f * light) * (0.85f + 0.5f * calm)

        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - ORCHID_HUE
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyTurn.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, dt)
        updateColours(state.palette, keyTurn.value)

        gas.flare = flare.value.coerceIn(0f, 1.5f)
        gas.blend = 0.35f + 0.25f * bass
        gas.swell = 1f / (1f + 0.28f * bass)
        // A strand narrower than a pixel breaks into dots, so a small canvas draws them wider.
        val fine = (pixelsHigh / 600f).coerceIn(0.6f, 1f)
        gas.sharpness = SHARPNESS * fine * (1f - 0.35f * bass)
        gas.braid = (1.6f + 1.6f * treble) * braiding.value
        gas.inward = 0.3f * calm + 0.1f * charge
        // The push moves the strands, so reduced motion takes it away; the flare is light and stays.
        gas.pushStrength = push * motion
        gas.reach = reach.value
        gas.laneWidth = 0.13f * dustWidth.value
        recipes.pack(gas.fold)
        gas.strandCos = cos(recipes.strand)
        gas.strandSin = sin(recipes.strand)
        gas.along = recipes.along
        gas.across = recipes.across
        past.push(frame.bandsRel)

        val wanted = (SPARKS * ((treble - 0.1f) * 1.6f).coerceIn(0f, 1f) * frame.audible * (1f - calm)).toInt()
        sparks.advance(gas, past, wanted, step * (0.4f + 0.6f * motion), dt, aspect)

        kit.place(0, 0.5f + gas.coreX / (2f * aspect), 0.5f + gas.coreY * 0.5f, parallax = 0f)
        val newest = comets.newest
        if (newest >= 0 && comets.alive[newest]) {
            kit.place(1, comets.x[newest], comets.y[newest], parallax = 0f)
        } else {
            kit.place(1, 0.5f + gas.coreX / (2f * aspect), 0.5f + gas.coreY * 0.5f, parallax = 0f)
        }
        for (index in 0 until 3) {
            if (sparks.alive[index]) {
                kit.place(2 + index, 0.5f + sparks.x[index] / (2f * aspect), 0.5f + sparks.y[index] * 0.5f, parallax = 0f)
            } else {
                kit.place(2 + index, 0.5f + gas.coreX / (2f * aspect), 0.5f + gas.coreY * 0.5f, parallax = 0f)
            }
        }
    }

    override fun shaderSize(width: Float, height: Float) {
        if (height > 0f) pixelsHigh = height
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val aspect = kit.aspect
        program.uniform("uCore", gas.coreX, gas.coreY)
        for (step in 0 until STEPS) {
            val at = step * 12
            val fold = gas.fold
            program.uniform(FOLD_A[step], fold[at], fold[at + 1], fold[at + 2], fold[at + 3])
            program.uniform(FOLD_B[step], fold[at + 4], fold[at + 5], fold[at + 6], fold[at + 7])
            program.uniform(FOLD_C[step], fold[at + 8], fold[at + 9], fold[at + 10], fold[at + 11])
        }
        program.uniform("uWave", gas.waveOne, gas.waveTwo, gas.waveThree)
        program.uniform("uStrand", gas.strandCos, gas.strandSin, gas.along, gas.across)
        program.uniform("uGas", gas.blend, gas.swell, gas.sharpness, gas.braid)
        program.uniform("uPush", gas.pushRadius, gas.pushStrength, gas.inward, gas.flare)
        program.uniform("uNebula", gas.centreX, gas.centreY, gas.reach, 0f)
        program.uniform("uLane", gas.laneA, gas.laneB, gas.laneWidth, gas.laneAge)

        // The ring reaches the farthest corner in one bar. Under reduced motion it does not travel:
        // the whole nebula brightens over the bar instead.
        val farthest = sqrt((aspect + abs(gas.coreX)).let { it * it } + (1f + abs(gas.coreY)).let { it * it }) + 0.05f
        val travelling = shock >= 0f
        val eased = if (travelling) 1f - (1f - shock) * (1f - shock) else 1f
        val radius = if (travelling) farthest * (eased * motion + (1f - motion)) else farthest
        val ring = if (travelling) (1f - smooth(0.7f, 1f, shock)) * motion else 0f
        val behind = SHOCK_GLOW * if (travelling) motion + (1f - motion) * eased else afterglow
        program.uniform("uShock", radius, ring, behind, 0f)
        program.uniform("uLight", light, gasLight, filamentLight, starLight)

        var cometX = 0f
        var cometY = 0f
        cometGlow = 0f
        val newest = comets.newest
        if (newest >= 0 && comets.alive[newest]) {
            val t = comets.progress[newest]
            cometGlow = 1.5f * minOf(t * 6f, (1f - t) * 6f).coerceIn(0f, 1f)
            cometX = (comets.x[newest] - 0.5f) * 2f * aspect
            cometY = (comets.y[newest] - 0.5f) * 2f
        }
        program.uniform("uComet", cometX, cometY, cometGlow, 0f)
        program.uniform("uStarsA", -farDrift, farDrift * 0.25f, -middleDrift, middleDrift * 0.25f)
        program.uniform("uStarsB", -nearDrift, nearDrift * 0.25f, twinkle, twinkleSeed)
        val share = starShare.value
        program.uniform("uStarsC", 1f - 0.16f * share, 1f - 0.13f * share, 1f - 0.1f * share, 0f)
        program.uniform("uOrchid", orchid[0], orchid[1], orchid[2])
        program.uniform("uViolet", violet[0], violet[1], violet[2])
        program.uniform("uGreen", green[0], green[1], green[2])
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val aspect = kit.aspect
        val unit = size.minDimension
        // Sparks: small green beads with a white-hot middle, running along the strands.
        val bead = maxOf(1.3f, unit * 0.005f)
        for (index in 0 until sparks.capacity) {
            if (!sparks.alive[index]) continue
            val alpha = (sparks.alpha[index] * light).coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            val at = Offset((0.5f + sparks.x[index] / (2f * aspect)) * size.width, (0.5f + sparks.y[index] * 0.5f) * size.height)
            drawCircle(threadColour.copy(alpha = alpha), bead, at)
            drawCircle(Color.White.copy(alpha = alpha), bead * 0.55f, at)
        }
        // Comets: a white head and a tail that thins and fades behind it.
        val head = maxOf(2f, unit * 0.01f)
        for (slot in 0 until comets.capacity) {
            if (!comets.alive[slot]) continue
            val t = comets.progress[slot]
            val fade = (minOf(t * 6f, (1f - t) * 6f).coerceIn(0f, 1f) * light).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            var previous = pathAt(slot, t)
            for (segment in 1..TAIL) {
                val next = pathAt(slot, t - segment * 0.016f)
                val share = 1f - segment.toFloat() / (TAIL + 1)
                // The tail is light fading out behind the head, so it is see-through on purpose.
                drawLine(ICE_WHITE.copy(alpha = fade * share * 0.8f), previous, next,
                    strokeWidth = head * 1.4f * share, cap = StrokeCap.Round)
                previous = next
            }
            val at = pathAt(slot, t)
            drawCircle(ICE_WHITE.copy(alpha = fade), head * 1.3f, at)
            drawCircle(Color.White.copy(alpha = fade), head, at)
        }
    }

    /** Where comet [slot] is at progress [t], in pixels, on the same arc [Travellers] follows. */
    private fun DrawScope.pathAt(slot: Int, t: Float): Offset {
        val along = t.coerceIn(0f, 1f)
        val dx = comets.toX[slot] - comets.fromX[slot]
        val dy = comets.toY[slot] - comets.fromY[slot]
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
        val offset = comets.bend[slot] * sin(PI.toFloat() * along)
        val x = comets.fromX[slot] + dx * along - dy / length * offset
        val y = comets.fromY[slot] + dy * along + dx / length * offset
        return Offset(x * size.width, y * size.height)
    }

    /**
     * The two gas colours and the dark tint, turned together by the key. The default palette shows
     * the drawing's own swatches; any other palette replaces them with its own colours.
     */
    private fun updateColours(palette: VizPalette, turn: Float) {
        val degrees = round(turn)
        if (palette === colourFor && degrees == colourTurn) return
        colourFor = palette
        colourTurn = degrees
        val body: Color
        val thin: Color
        val thread: Color
        if (palette.name == VizPalette.Prism.name) {
            body = vividColour(ORCHID_HUE + degrees)
            val violetHue = DEEP_VIOLET_HUE + degrees
            thin = colourOf(DEEP_VIOLET_LIGHTNESS, (mostChroma(DEEP_VIOLET_LIGHTNESS, violetHue) - 0.03f).coerceAtLeast(0f), violetHue)
            thread = vividColour(ACID_GREEN_HUE + degrees)
        } else {
            body = palette.vivid(0f)
            thin = palette.vividAt(0f, DEEP_VIOLET_LIGHTNESS)
            thread = palette.vivid(0.7f)
        }
        body.into(orchid)
        thin.into(violet)
        thread.into(green)
        threadColour = thread
    }

    override fun onReset() {
        gas.reset()
        past.clear()
        recipes.reset()
        sparks.clear()
        comets.clear()
        flare.reset()
        keyTurn.reset()
        bass = 0f
        mid = 0f
        treble = 0f
        corePhase = 0f
        push = 0f
        sinceComet = 99f
        cometGlow = 0f
        twinkle = 0f
        twinkleSeed = 0f
        breakdown = false
        calm = 0f
        charge = 0f
        shock = -1f
        shockSeconds = 2f
        afterglow = 0f
        farDrift = 0f
        middleDrift = 0f
        nearDrift = 0f
        light = 1f
        gasLight = 1f
        filamentLight = 1f
        starLight = 1f
        motion = 1f
        colourFor = null
        colourTurn = Float.NaN
    }

    internal companion object {
        const val STEPS = 6
        const val SPARKS = 24
        private const val TAIL = 10

        // The swatches, as Oklch hues and a lightness for the dark tint.
        const val ORCHID_HUE = 320f
        const val ACID_GREEN_HUE = 145f
        private const val DEEP_VIOLET_HUE = 300f
        private const val DEEP_VIOLET_LIGHTNESS = 0.32f
        private val ICE_WHITE = Color(0xFFB7F0FB)

        /** How far the key may turn both colours, in degrees. */
        private const val KEY_TURN = 30f

        /** Where the core's path is centred, in the shader's units: half the screen height is one. */
        private const val CORE_X = -0.15f
        private const val CORE_Y = 0f

        /** How fast a kick's pressure wave travels, in the same units a second. */
        private const val PUSH_SPEED = 1.4f

        /** How long before a drop the build-up may start, in seconds. */
        private const val BUILD_SECONDS = 1.5f

        /** The share of the light a silence keeps. */
        private const val SILENT_LIGHT = 0.33f

        /** How much brighter the gas stays behind the shock ring. */
        private const val SHOCK_GLOW = 0.6f

        // Numbers the shader source repeats as literals, for the processor's copy of the gas.
        const val SHARPNESS = 16f
        const val SEAM = 0.02f
        const val WAVE_Y = 1.6f
        const val WAVE_X = 1.9f
        const val WAVE_XY = 1.2f
        const val PUSH_WIDTH = 30f
        const val PUSH_DEPTH = 0.1f
        const val NOISE_X = 11.3f
        const val NOISE_Y = 5.7f

        private val GLOW = PostSpec(bloom = 0.3f, bloomRadius = 0.025f, threshold = 0.82f, vignette = 0.22f,
            grain = 0f, glitch = false, aberration = 0f)

        private val FOLD_A = Array(STEPS) { "uFoldA$it" }
        private val FOLD_B = Array(STEPS) { "uFoldB$it" }
        private val FOLD_C = Array(STEPS) { "uFoldC$it" }

        const val SOURCE: String = """
uniform float2 uCore;
uniform float4 uFoldA0;
uniform float4 uFoldB0;
uniform float4 uFoldC0;
uniform float4 uFoldA1;
uniform float4 uFoldB1;
uniform float4 uFoldC1;
uniform float4 uFoldA2;
uniform float4 uFoldB2;
uniform float4 uFoldC2;
uniform float4 uFoldA3;
uniform float4 uFoldB3;
uniform float4 uFoldC3;
uniform float4 uFoldA4;
uniform float4 uFoldB4;
uniform float4 uFoldC4;
uniform float4 uFoldA5;
uniform float4 uFoldB5;
uniform float4 uFoldC5;
uniform float3 uWave;
uniform float4 uStrand;
uniform float4 uGas;
uniform float4 uPush;
uniform float4 uNebula;
uniform float4 uLane;
uniform float4 uShock;
uniform float4 uLight;
uniform float4 uComet;
uniform float4 uStarsA;
uniform float4 uStarsB;
uniform float4 uStarsC;
uniform float3 uOrchid;
uniform float3 uViolet;
uniform float3 uGreen;

// One fold step: turn and scale, a mirror whose seam is rounded, then a box fold that brings
// whatever lies past its limit back inside, again without a crease.
float2 foldStep(float2 q, float4 turn, float4 shift, float4 fold) {
    q = float2(turn.x * q.x + turn.y * q.y, turn.z * q.x + turn.w * q.y) + shift.xy;
    if (fold.y > 0.0) {
        float d = dot(q, shift.zw) - fold.x;
        q += shift.zw * ((sqrt(d * d + 0.02) - d) * fold.y);
    }
    if (fold.w > 0.0) {
        float2 held = q / sqrt(1.0 + (q / fold.z) * (q / fold.z));
        q = mix(q, 2.0 * held - q, fold.w);
    }
    return q;
}

// A round star in each grid cell that holds one. The grid slides by drift pixels.
float starPlane(float2 position, float2 drift, float cell, float keep, float size, float salt) {
    float2 grid = (position + drift) / cell;
    float2 at = floor(grid);
    float h = hash21(at + salt);
    if (h < keep) {
        return 0.0;
    }
    float2 centre = float2(0.2 + 0.6 * fract(h * 37.1), 0.2 + 0.6 * fract(h * 91.7));
    float d = length((grid - at - centre) * cell);
    float radius = size * (0.6 + 0.8 * fract(h * 13.7));
    float glint = max(1.0 + uStarsB.z * (hash21(at + uStarsB.w) - 0.4) * 2.5, 0.2);
    return (1.0 - smoothstep(radius - 0.4, radius + 0.8, d)) * (0.3 + 0.7 * fract(h * 7.3)) * glint;
}

half4 main(float2 position) {
    float2 p = centred(position);
    float2 c = p - uCore;
    float r = length(c);
    // The music reshapes the gas round the core: bass swells it, a kick's pressure wave pushes
    // the strands out a little, and a breakdown draws them in.
    float front = r - uPush.x;
    float pushed = uPush.y * exp(-front * front * 30.0);
    float2 g = uCore + c * (uGas.y * (1.0 + uPush.z) * (1.0 - 0.1 * pushed));
    // Six fold steps, then three moving sine waves read in the folded space. The result moves
    // where the noise is read; the noise itself is never folded, so no mirror seam can show.
    float2 q = foldStep(g, uFoldA0, uFoldB0, uFoldC0);
    q = foldStep(q, uFoldA1, uFoldB1, uFoldC1);
    q = foldStep(q, uFoldA2, uFoldB2, uFoldC2);
    q = foldStep(q, uFoldA3, uFoldB3, uFoldC3);
    q = foldStep(q, uFoldA4, uFoldB4, uFoldC4);
    q = foldStep(q, uFoldA5, uFoldB5, uFoldC5);
    float w1 = sin(q.y * 1.6 + uWave.x);
    float w2 = sin(q.x * 1.9 - uWave.y);
    float w3 = sin((q.x + q.y) * 1.2 + uWave.z);
    float2 wave = float2(w1 + 0.5 * w3, w2 - 0.5 * w3);
    float2 u = g - uNebula.xy + uGas.x * wave;
    // Into the strands' own frame, stretched along them, so the noise draws long filaments.
    float2 f = float2(u.x * uStrand.x + u.y * uStrand.y, u.y * uStrand.x - u.x * uStrand.y);
    float2 s = float2(f.x * uStrand.z, f.y * uStrand.w) + float2(11.3, 5.7);
    float n = fbm(s);
    // Ridges: one minus the distance from the middle value, raised to a power, leaves thin strands.
    float v = (n - 0.5) * uGas.z;
    float strand = pow(max(1.0 - abs(v), 0.0), 4.0);
    float shoulder = pow(max(1.0 - abs(v) * 0.35, 0.0), 3.0);
    float wisp = pow(max(1.0 - abs(fract(n * 3.0 + 0.5) * 2.0 - 1.0), 0.0), 16.0);
    // The green is read at a slightly shifted level that wanders from side to side, so it runs
    // beside the orchid line and now and then crosses it.
    float braid = clamp((noise2(s * float2(2.0, 1.0) + float2(3.1, 1.3)) * 2.0 - 1.0) * uGas.w, -1.0, 1.0);
    float thread = pow(max(1.0 - abs((n - 0.5 - 0.05 * braid) * uGas.z * 1.15), 0.0), 8.0);

    // The nebula is a wide band along the strands, held still while the strands move inside it. Two
    // curved dust lanes cross it, bent by the spectrum's past, with ragged edges.
    float2 e = g - uNebula.xy + 0.25 * uGas.x * wave;
    float2 fe = float2(e.x * uStrand.x + e.y * uStrand.y, e.y * uStrand.x - e.x * uStrand.y);
    float env = 1.0 - smoothstep(0.25 * uNebula.z, 1.1 * uNebula.z, length(float2(fe.x * 0.45, fe.y * 1.25)));
    float past = history(clamp(0.5 + 0.45 * fe.y, 0.0, 1.0), uLane.w);
    float laneA = abs(fe.x - uLane.x + 0.35 * fe.y + 0.5 * fe.y * fe.y + 0.25 * w2 + 0.9 * (past - 0.3) + 0.08 * braid);
    float laneB = abs(fe.x - uLane.y - 0.3 * fe.y - 0.4 * fe.y * fe.y + 0.2 * w1 - 0.75 * (past - 0.3) + 0.08 * braid);
    float dust = min(smoothstep(0.3 * uLane.z, uLane.z, laneA), smoothstep(0.2 * uLane.z, 0.7 * uLane.z, laneB));
    dust = mix(1.0, dust, env);

    // Light: the core lights the gas from inside; a comet and the shock add to it.
    float lit = (1.0 + 1.4 * uPush.w) / (1.0 + 5.0 * r * r);
    float2 toComet = p - uComet.xy;
    lit += uComet.z * exp(-dot(toComet, toComet) * 9.0);
    float ringGap = r - uShock.x;
    float ringWidth = max(1.5, uResolution.y * 0.003) * 2.0 / uResolution.y;
    float ring = uShock.y * exp(-ringGap * ringGap / (ringWidth * ringWidth));
    float behind = uShock.z * (1.0 - smoothstep(-0.03, 0.03, ringGap));
    float shine = env * dust * (0.7 + 0.6 * lit) * uLight.y;
    // Behind the ring the gas keeps its colours at full strength; only the ring front turns it white.
    float light = shine * (1.0 + behind);

    float3 colour = uViolet * (0.08 * shoulder + 0.6 * wisp) + (uOrchid * strand + uGreen * thread) * uLight.z;
    colour *= min(light, 1.0);
    float white = smoothstep(0.55, 0.95, strand) * smoothstep(0.45, 0.9, thread) * min(light, 1.0)
        + max(shine - 1.0, 0.0) * max(strand, thread)
        + ring * (2.0 * env * dust * max(strand, thread) + 0.35 + 0.65 * env);
    colour = mix(colour, float3(1.0), clamp(white, 0.0, 1.0));

    // The core: a small white star with a tight halo that a kick widens.
    float pixels = r * uResolution.y * 0.5;
    float size = max(1.5, uResolution.y * 0.012) * (1.0 + 0.5 * uPush.w);
    float core = 1.0 - smoothstep(size - 0.5, size + 1.0, pixels);
    core += (0.6 + 0.5 * uPush.w) * exp(-pixels / (size * 2.5));
    colour = mix(colour, float3(1.0), clamp(core, 0.0, 1.0));
    colour *= uLight.x;

    // Stars on three depths, behind the gas and hidden by the dust.
    float star = 0.55 * starPlane(position, uStarsA.xy * uResolution.y, uResolution.y * 0.05, uStarsC.x, max(0.7, uResolution.y * 0.0015), 19.0);
    star += 0.8 * starPlane(position, uStarsA.zw * uResolution.y, uResolution.y * 0.075, uStarsC.y, max(0.75, uResolution.y * 0.002), 41.0);
    star += starPlane(position, uStarsB.xy * uResolution.y, uResolution.y * 0.11, uStarsC.z, max(0.8, uResolution.y * 0.0028), 67.0);
    star *= uLight.w * dust;
    colour += float3(star) * (1.0 - clamp(max(colour.r, max(colour.g, colour.b)), 0.0, 1.0));
    return half4(clamp(colour, 0.0, 1.0), 1.0);
}
"""
    }

    /**
     * The shader's gas on the processor, so the sparks can find the strands and run along them. Its
     * numbers are the ones handed to the shader each frame, and [evaluate] repeats the shader's steps.
     */
    internal class GasField {
        var coreX = 0f
        var coreY = 0f
        val fold = FloatArray(STEPS * 12)
        var waveOne = 0f
        var waveTwo = 0f
        var waveThree = 0f
        var strandCos = 1f
        var strandSin = 0f
        var along = 0.66f
        var across = 8.6f
        var blend = 0.35f
        var swell = 1f
        var sharpness = SHARPNESS
        var braid = 1.6f
        var pushRadius = 0f
        var pushStrength = 0f
        var inward = 0f
        var flare = 0f
        var centreX = 0f
        var centreY = 0f
        var reach = 1f
        var laneA = -0.2f
        var laneB = 0.7f
        var laneWidth = 0.13f
        var laneAge = 0.05f

        /** The last point's distance from a strand's crest, in ridge units: 0 on the crest, 1 at its edge. */
        var offset = 0f
            private set

        /** How much of the last point shows: inside the nebula and outside the dust. 0 when not asked for. */
        var visible = 0f
            private set

        /** Reads the gas at [x], [y] in the shader's units. With [past], also works out [visible]. */
        fun evaluate(x: Float, y: Float, past: SpectrumPast?) {
            val cx = x - coreX
            val cy = y - coreY
            val r = sqrt(cx * cx + cy * cy)
            val front = r - pushRadius
            val pushed = pushStrength * exp(-front * front * PUSH_WIDTH)
            val scale = swell * (1f + inward) * (1f - PUSH_DEPTH * pushed)
            val gx = coreX + cx * scale
            val gy = coreY + cy * scale
            var qx = gx
            var qy = gy
            for (step in 0 until STEPS) {
                val at = step * 12
                val turnedX = fold[at] * qx + fold[at + 1] * qy + fold[at + 4]
                val turnedY = fold[at + 2] * qx + fold[at + 3] * qy + fold[at + 5]
                qx = turnedX
                qy = turnedY
                val mirror = fold[at + 9]
                if (mirror > 0f) {
                    val d = qx * fold[at + 6] + qy * fold[at + 7] - fold[at + 8]
                    val push = (sqrt(d * d + SEAM) - d) * mirror
                    qx += fold[at + 6] * push
                    qy += fold[at + 7] * push
                }
                val box = fold[at + 11]
                if (box > 0f) {
                    val limit = fold[at + 10]
                    val heldX = qx / sqrt(1f + (qx / limit) * (qx / limit))
                    val heldY = qy / sqrt(1f + (qy / limit) * (qy / limit))
                    qx += (2f * heldX - qx - qx) * box
                    qy += (2f * heldY - qy - qy) * box
                }
            }
            val w1 = sin(qy * WAVE_Y + waveOne)
            val w2 = sin(qx * WAVE_X - waveTwo)
            val w3 = sin((qx + qy) * WAVE_XY + waveThree)
            val waveX = w1 + 0.5f * w3
            val waveY = w2 - 0.5f * w3
            val ux = gx - centreX + blend * waveX
            val uy = gy - centreY + blend * waveY
            val fx = ux * strandCos + uy * strandSin
            val fy = uy * strandCos - ux * strandSin
            val n = fbm(fx * along + NOISE_X, fy * across + NOISE_Y)
            offset = (n - 0.5f) * sharpness
            if (past == null) {
                visible = 0f
                return
            }
            val ex = gx - centreX + 0.25f * blend * waveX
            val ey = gy - centreY + 0.25f * blend * waveY
            val hx = ex * strandCos + ey * strandSin
            val hy = ey * strandCos - ex * strandSin
            val spread = sqrt((hx * 0.45f) * (hx * 0.45f) + (hy * 1.25f) * (hy * 1.25f))
            val inside = 1f - smooth(0.25f * reach, 1.1f * reach, spread)
            val then = past.at((0.5f + 0.45f * hy).coerceIn(0f, 1f), laneAge)
            val sx = fx * along + NOISE_X
            val sy = fy * across + NOISE_Y
            val wander = ((noise2(sx * 2f + 3.1f, sy + 1.3f) * 2f - 1f) * braid).coerceIn(-1f, 1f)
            val a = abs(hx - laneA + 0.35f * hy + 0.5f * hy * hy + 0.25f * w2 + 0.9f * (then - 0.3f) + 0.08f * wander)
            val b = abs(hx - laneB - 0.3f * hy - 0.4f * hy * hy + 0.2f * w1 - 0.75f * (then - 0.3f) + 0.08f * wander)
            val clear = minOf(smooth(0.3f * laneWidth, laneWidth, a), smooth(0.2f * laneWidth, 0.7f * laneWidth, b))
            visible = inside * (1f + (clear - 1f) * inside)
        }

        fun reset() {
            waveOne = 0f
            waveTwo = 0f
            waveThree = 0f
            pushRadius = 0f
            pushStrength = 0f
            inward = 0f
            flare = 0f
        }
    }

    /**
     * The shader's history of the spectrum, kept on the processor as well, so the sparks see the dust
     * lanes where the shader draws them. Written once a frame, the same way the shader's strip is.
     */
    internal class SpectrumPast {
        private val rows = Array(ShaderLibrary.HISTORY) { FloatArray(ShaderLibrary.BANDS) }
        private var next = 0
        private var fresh = true

        fun push(values: FloatArray) {
            val row = rows[next]
            for (pixel in 0 until ShaderLibrary.BANDS) {
                val read = sample(values, pixel.toFloat() / (ShaderLibrary.BANDS - 1))
                // The strip holds eight bits a value.
                row[pixel] = (read.coerceIn(0f, 1f) * 255f + 0.5f).toInt() / 255f
            }
            if (fresh) {
                for (other in rows) if (other !== row) row.copyInto(other)
            }
            fresh = false
            next = (next + 1) % ShaderLibrary.HISTORY
        }

        /** The spectrum at [where], 0 to 1, as it was [age] ago, 0 now and 1 about four seconds back. */
        fun at(where: Float, age: Float): Float {
            val row = next - 1f - age.coerceIn(0f, 1f) * (ShaderLibrary.HISTORY - 2)
            val lower = floor(row)
            val x = where.coerceIn(0f, 1f) * (ShaderLibrary.BANDS - 1)
            val older = read(lower.toInt(), x)
            val newer = read(lower.toInt() + 1, x)
            return older + (newer - older) * (row - lower)
        }

        private fun read(row: Int, x: Float): Float {
            val values = rows[((row % ShaderLibrary.HISTORY) + ShaderLibrary.HISTORY) % ShaderLibrary.HISTORY]
            val left = x.toInt().coerceIn(0, ShaderLibrary.BANDS - 1)
            val right = (left + 1).coerceAtMost(ShaderLibrary.BANDS - 1)
            return values[left] + (values[right] - values[left]) * (x - left)
        }

        private fun sample(from: FloatArray, at: Float): Float {
            if (from.isEmpty()) return 0f
            if (from.size == 1) return from[0]
            val scaled = at.coerceIn(0f, 1f) * (from.size - 1)
            val lower = scaled.toInt().coerceIn(0, from.size - 2)
            return from[lower] + (from[lower + 1] - from[lower]) * (scaled - lower)
        }

        fun clear() {
            for (row in rows) row.fill(0f)
            next = 0
            fresh = true
        }
    }

    /**
     * The six fold steps that shape the gas, and the change from one recipe to the next over one bar.
     *
     * A recipe takes one of a few orders of mirror, box fold, rotate and scale known to look good, with
     * new numbers every time. Each step is kept as numbers a person can read (angles, a scale, a shift,
     * a mirror plane and weights) and blended as those, so a rotation never passes through nothing on
     * its way to the next one. [pack] turns them into the matrices and normals the shader reads.
     */
    internal class FoldRecipes(seed: Long) {
        private val random = Rng(seed)
        private val from = FloatArray(PARTS)
        private val to = FloatArray(PARTS)
        private val now = FloatArray(PARTS)
        private var progress = 1f
        private var seconds = 2f

        /** The strands' direction in radians, and how far the noise is stretched along and across them. */
        val strand: Float get() = now[STRAND]
        val along: Float get() = now[STRAND + 1]
        val across: Float get() = now[STRAND + 2]

        fun reset() {
            random.reset()
            draw(to, null)
            to.copyInto(from)
            to.copyInto(now)
            progress = 1f
        }

        /** Starts the change to a new recipe, which takes [seconds] of music. */
        fun next(seconds: Float) {
            now.copyInto(from)
            draw(to, from)
            progress = 0f
            this.seconds = seconds.coerceAtLeast(0.5f)
        }

        fun advance(step: Float) {
            if (progress >= 1f) return
            progress = (progress + step / seconds).coerceAtMost(1f)
            val eased = progress * progress * (3f - 2f * progress)
            for (index in 0 until PARTS) {
                val angle = index == STRAND || (index < STEPS_PARTS && (index % PER_STEP == 0 || index % PER_STEP == 4))
                val gap = if (angle) turnBetween(from[index], to[index]) else to[index] - from[index]
                now[index] = from[index] + gap * eased
            }
        }

        /** Writes each step as the shader reads it: a 2x2 matrix, a shift and a mirror normal, then the fold numbers. */
        fun pack(into: FloatArray) {
            for (step in 0 until STEPS) {
                val at = step * PER_STEP
                val out = step * 12
                val scale = now[at + 1]
                val c = cos(now[at]) * scale
                val s = sin(now[at]) * scale
                into[out] = c
                into[out + 1] = -s
                into[out + 2] = s
                into[out + 3] = c
                into[out + 4] = now[at + 2]
                into[out + 5] = now[at + 3]
                into[out + 6] = cos(now[at + 4])
                into[out + 7] = sin(now[at + 4])
                into[out + 8] = now[at + 5]
                into[out + 9] = now[at + 6]
                into[out + 10] = now[at + 7]
                into[out + 11] = now[at + 8]
            }
        }

        private fun draw(into: FloatArray, previous: FloatArray?) {
            val kinds = TEMPLATES[(random.next() * TEMPLATES.size).toInt().coerceIn(0, TEMPLATES.size - 1)]
            for (step in 0 until STEPS) {
                val at = step * PER_STEP
                val kind = kinds[step]
                into[at] = if (kind == ROTATE) random.signed() * 1.2f else 0f
                into[at + 1] = if (kind == SCALE) 1.1f + 0.35f * random.next() else 1f
                into[at + 2] = if (kind == SCALE) random.signed() * 0.35f else 0f
                into[at + 3] = if (kind == SCALE) random.signed() * 0.35f else 0f
                // A step that is not a mirror keeps the plane it had, so a mirror fading out does not swing.
                into[at + 4] = if (kind == MIRROR) random.next() * TAU else previous?.get(at + 4) ?: 0f
                into[at + 5] = if (kind == MIRROR) random.signed() * 0.3f else previous?.get(at + 5) ?: 0f
                into[at + 6] = if (kind == MIRROR) 1f else 0f
                into[at + 7] = if (kind == BOX) 0.5f + 0.6f * random.next() else previous?.get(at + 7) ?: 0.8f
                into[at + 8] = if (kind == BOX) 1f else 0f
            }
            // The strands turn by a clear amount at every change, so the new shape reads at a glance.
            val was = previous?.get(STRAND) ?: 0.35f
            var angle = -1.1f + 2.2f * random.next()
            var tries = 0
            while (previous != null && abs(angle - was) < 0.6f && tries < 16) {
                angle = -1.1f + 2.2f * random.next()
                tries++
            }
            into[STRAND] = angle
            into[STRAND + 1] = 0.55f + 0.25f * random.next()
            into[STRAND + 2] = 7.5f + 2.2f * random.next()
        }

        private fun turnBetween(a: Float, b: Float): Float {
            var gap = (b - a) % TAU
            if (gap > PI.toFloat()) gap -= TAU
            if (gap < -PI.toFloat()) gap += TAU
            return gap
        }

        private companion object {
            const val MIRROR = 0
            const val BOX = 1
            const val ROTATE = 2
            const val SCALE = 3

            // Per step: rotation, scale, shift x and y, mirror plane angle and offset, mirror weight,
            // box limit, box weight. Then the strands' angle and their two stretches.
            const val PER_STEP = 9
            const val STEPS_PARTS = STEPS * PER_STEP
            const val STRAND = STEPS_PARTS
            const val PARTS = STEPS_PARTS + 3

            /** Orders of operations that each hold a mirror, a box fold, a rotation and a scale. */
            val TEMPLATES = arrayOf(
                intArrayOf(ROTATE, MIRROR, BOX, SCALE, ROTATE, MIRROR),
                intArrayOf(MIRROR, ROTATE, MIRROR, SCALE, BOX, ROTATE),
                intArrayOf(BOX, ROTATE, MIRROR, ROTATE, SCALE, MIRROR),
                intArrayOf(MIRROR, MIRROR, ROTATE, BOX, SCALE, ROTATE),
                intArrayOf(ROTATE, BOX, MIRROR, SCALE, MIRROR, ROTATE),
            )
        }
    }

    /**
     * Beads of light that run along the strands, like beads on a wire. Each one is found on a strand of
     * the processor's copy of the gas, follows it for a while and fades out when it runs off its strand,
     * into the dust or out of the nebula.
     */
    internal class Sparks(val capacity: Int, seed: Long) {
        /** Where each spark is, in the shader's units. */
        val x = FloatArray(capacity)
        val y = FloatArray(capacity)
        val alpha = FloatArray(capacity)
        val alive = BooleanArray(capacity)
        private val leaving = BooleanArray(capacity)
        private val heading = FloatArray(capacity)
        private val speed = FloatArray(capacity)
        private val life = FloatArray(capacity)
        private val random = Rng(seed)
        private var budget = 0f

        /**
         * Moves every spark [move] seconds along its strand, fades them over [fade] seconds, and starts
         * new ones until [wanted] are running. [aspect] is the screen's width over its height.
         */
        fun advance(gas: GasField, past: SpectrumPast, wanted: Int, move: Float, fade: Float, aspect: Float) {
            var staying = 0
            for (index in 0 until capacity) {
                if (!alive[index]) continue
                if (!leaving[index]) {
                    if (move > 0f) run(index, gas, move)
                    life[index] -= move
                    gas.evaluate(x[index], y[index], past)
                    val lost = abs(gas.offset) > 0.3f || gas.visible < 0.2f ||
                        abs(x[index]) > aspect || abs(y[index]) > 1f
                    if (life[index] <= 0f || lost || staying >= wanted) leaving[index] = true else staying++
                }
                if (leaving[index]) {
                    alpha[index] -= fade / FADE_OUT
                    if (alpha[index] <= 0f) {
                        alpha[index] = 0f
                        alive[index] = false
                    }
                } else {
                    alpha[index] = (alpha[index] + fade / FADE_IN).coerceAtMost(1f)
                }
            }
            budget = (budget + move * PER_SECOND).coerceAtMost(2f)
            while (staying < wanted && budget >= 1f) {
                budget -= 1f
                if (spawn(gas, past, aspect)) staying++
            }
        }

        /** One step along the strand, at right angles to the way its value climbs, then back onto its crest. */
        private fun run(index: Int, gas: GasField, seconds: Float) {
            val px = x[index]
            val py = y[index]
            gas.evaluate(px, py, null)
            val v = gas.offset
            gas.evaluate(px + PROBE, py, null)
            val gx = (gas.offset - v) / PROBE
            gas.evaluate(px, py + PROBE, null)
            val gy = (gas.offset - v) / PROBE
            val squared = gx * gx + gy * gy
            if (squared < 1e-6f) return
            val length = sqrt(squared)
            val pull = (v / squared).coerceIn(-MOST_PULL / length, MOST_PULL / length)
            val distance = speed[index] * seconds * heading[index]
            x[index] = px - gx * pull - gy / length * distance
            y[index] = py - gy * pull + gx / length * distance
        }

        private fun spawn(gas: GasField, past: SpectrumPast, aspect: Float): Boolean {
            var slot = -1
            for (index in 0 until capacity) {
                if (!alive[index]) {
                    slot = index
                    break
                }
            }
            if (slot < 0) return false
            repeat(TRIES) {
                var px = gas.centreX + random.signed() * minOf(aspect, 1.6f) * 0.85f
                var py = gas.centreY + random.signed() * 0.7f
                // Newton steps onto the nearest crest.
                repeat(4) {
                    gas.evaluate(px, py, null)
                    val v = gas.offset
                    gas.evaluate(px + PROBE, py, null)
                    val gx = (gas.offset - v) / PROBE
                    gas.evaluate(px, py + PROBE, null)
                    val gy = (gas.offset - v) / PROBE
                    val squared = gx * gx + gy * gy
                    if (squared > 1e-6f) {
                        val length = sqrt(squared)
                        val pull = (v / squared).coerceIn(-0.05f / length, 0.05f / length)
                        px -= gx * pull
                        py -= gy * pull
                    }
                }
                gas.evaluate(px, py, past)
                if (abs(gas.offset) < 0.08f && gas.visible > 0.35f && abs(px) < aspect * 0.97f && abs(py) < 0.97f) {
                    x[slot] = px
                    y[slot] = py
                    alpha[slot] = 0f
                    alive[slot] = true
                    leaving[slot] = false
                    heading[slot] = if (random.next() < 0.5f) -1f else 1f
                    speed[slot] = 0.12f + 0.16f * random.next()
                    life[slot] = 1.2f + 2.3f * random.next()
                    return true
                }
            }
            return false
        }

        fun clear() {
            alive.fill(false)
            leaving.fill(false)
            alpha.fill(0f)
            random.reset()
            budget = 0f
        }

        private companion object {
            const val FADE_IN = 0.2f
            const val FADE_OUT = 0.3f
            const val PER_SECOND = 12f
            const val TRIES = 6
            const val PROBE = 0.004f
            const val MOST_PULL = 0.02f
        }
    }
}

private fun Color.into(out: FloatArray) {
    out[0] = red
    out[1] = green
    out[2] = blue
}

private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun fract(x: Float): Float = x - floor(x)

// The shader library's noise, number for number, so the processor finds the strands the shader draws.
private fun hash21(x: Float, y: Float): Float {
    var px = fract(x * 123.34f)
    var py = fract(y * 456.21f)
    val d = px * (px + 45.32f) + py * (py + 45.32f)
    px += d
    py += d
    return fract(px * py)
}

private fun noise2(x: Float, y: Float): Float {
    val cellX = floor(x)
    val cellY = floor(y)
    var intoX = x - cellX
    var intoY = y - cellY
    intoX = intoX * intoX * (3f - 2f * intoX)
    intoY = intoY * intoY * (3f - 2f * intoY)
    val a = hash21(cellX, cellY)
    val b = hash21(cellX + 1f, cellY)
    val c = hash21(cellX, cellY + 1f)
    val d = hash21(cellX + 1f, cellY + 1f)
    val top = a + (b - a) * intoX
    val bottom = c + (d - c) * intoX
    return top + (bottom - top) * intoY
}

private fun fbm(x: Float, y: Float): Float {
    var total = 0f
    var weight = 0.5f
    var px = x
    var py = y
    for (octave in 0 until 5) {
        total += noise2(px, py) * weight
        px *= 2.02f
        py *= 2.02f
        weight *= 0.5f
    }
    return total
}

