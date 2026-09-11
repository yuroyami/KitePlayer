package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.yuroyami.kiteplayer.audioviz.viz.TraceGain
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import androidx.compose.ui.graphics.BlendMode
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glyph
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.strip
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import kotlin.math.PI

/*
 * Drawings built for one end of the scale.
 *
 * Everything else here tries to suit any music. These do not, and that is the point: each one
 * would be wrong for the other end, which is what makes them worth having once something is
 * choosing on the listener's behalf. A strobing polygon under a piano intro is rude, and a single
 * slow orb under a drum break looks broken.
 */

/**
 * One orb, breathing, drifting round the screen once every four bars. Dotted shells turn slowly round
 * it, motes circle it, and a faint wisp crosses the sky every bar. It answers the section more than the
 * beat, and under a drum track it would look out of place.
 */
internal class Breath : Layered(
    name = "Breath",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Calm,
    kit = Kit(
        seed = 306L,
        groundKind = GroundKind.Fog,
        detailKind = DetailKind.Specks,
        detailStrength = 0.5f,
        camera = Camera2D(wander = 0.05f, punch = 0.04f, roll = 0.04f, shake = 0f, cuts = false, seed = 306),
    ),
) {
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.86f, livelyTrail = 0.78f, calmSpin = 0.1f, livelySpin = 0.3f)

    private val shells = genes.choice("Shells", 3, start = 2)
    private val route = genes.choice("Path", 3)
    private val motes = genes.choice("Motes", 3, start = 1)
    private val lean = genes.number("Hue lean", -0.15f, 0.15f, 0f)

    private val swell = Envelope(attackPerSecond = 1.2f, releasePerSecond = 0.7f)
    private var lap = 0f
    private var direction = 1f
    private var orbX = 0.5f
    private var orbY = 0.5f
    private var turn = 0f
    private var widen = 0f
    private val moteAngle = FloatArray(MOTES) { it * 2.3999632f }
    private val wisps = Comets(size = 0.02f)
    private val mesh = TriangleMesh(maxVertices = (MOTES + 3 * DOTS) * 7 + 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (gestures.phrase) direction = -direction
        lap += dt / (gestures.barSeconds * 4f) * direction * TAU
        var x = 0f
        var y = 0f
        for (option in 0 until 3) {
            val share = route.weight(option)
            if (share <= 0f) continue
            x += share * when (option) {
                0 -> 0.36f * cos(lap)
                1 -> 0.38f * sin(lap)
                else -> 0.42f * cos(lap)
            }
            y += share * when (option) {
                0 -> 0.32f * sin(lap)
                1 -> 0.28f * sin(2f * lap)
                else -> 0.2f * sin(lap)
            }
        }
        orbX = 0.5f + x
        orbY = 0.5f + y
        kit.place(0, orbX, orbY)
        swell.advance(0.2f + 0.8f * state.frame.loudLong, dt)
        ground?.dim = 0.7f + 0.4f * swell.value
        turn += dt * 1f * state.tempo
        if (gestures.drop) widen = 1f
        widen = (widen - dt / gestures.barSeconds).coerceAtLeast(0f)
        for (mote in 0 until MOTES) {
            moteAngle[mote] += dt * (1.6f + (mote % 5) * 0.4f) * state.tempo * if (mote % 2 == 0) 1f else -1f
        }
        wisps.advance(state, gestures, random)
        kit.follow(1, wisps.travellers)
    }

    private fun moteRadius(mote: Int): Float = 0.1f + 0.28f * ((mote * 37) % 17) / 16f

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val at = Offset(orbX * size.width, orbY * size.height)
        val tint = state.frame.keyHue * state.frame.keyConfidence + lean.value + genes.walk
        val lift = 0.3f + 0.7f * state.lift
        val radius = sceneRadius * (0.2f + 0.25f * swell.value)
        for (layer in 0 until 3) {
            val spread = (radius * (1f + layer * 0.6f)).coerceAtLeast(1f)
            drawCircle(
                Brush.radialGradient(0f to state.palette.cycled(tint + layer * 0.06f, alpha = ((0.2f - layer * 0.05f) * lift).coerceIn(0f, 1f)), 1f to Color.Transparent, center = at, radius = spread),
                spread,
                at,
            )
        }
        mesh.clear()
        // Shells of dots turning slowly round the orb, thrown out to the edges on a drop.
        val dot = size.minDimension * 0.009f
        for (shell in 0 until shells.drawn(1)) {
            val presence = shells.presence(shell, 1)
            if (presence <= 0.01f) continue
            val ring = radius * (1.4f + 0.6f * shell) * (1f + 2.5f * widen)
            val spin = turn * (if (shell % 2 == 0) 1f else -1f) * (1f - 0.25f * shell)
            val colour = state.palette.argb(tint + 0.1f * shell, value = 0.9f, alpha = 0.4f * lift * presence)
            for (index in 0 until DOTS) {
                val a = spin + TAU * index / DOTS
                mesh.glow(at.x + cos(a) * ring, at.y + sin(a) * ring, dot, colour)
            }
        }
        for (mote in 0 until motes.drawn(30, 15)) {
            val presence = motes.presence(mote, 30, 15)
            if (presence <= 0.01f) continue
            val reach = moteRadius(mote) * size.height
            val a = moteAngle[mote]
            val colour = state.palette.argb(tint + mote * 0.01f, saturation = 0.5f, value = 1f, alpha = 0.6f * lift * presence)
            mesh.glow(at.x + cos(a) * reach, at.y + sin(a) * reach * 0.8f, dot * 2f, colour)
        }
        drawMesh(mesh, BlendMode.Plus)
        with(wisps) { drawComets(state.palette, tint, alpha = 0.5f * lift) }
    }

    override fun onReset() {
        swell.reset(0.3f)
        lap = 0f
        direction = 1f
        turn = 0f
        widen = 0f
        wisps.clear()
    }

    private companion object {
        const val MOTES = 60
        const val DOTS = 72
    }
}

/**
 * Translucent bands of colour sliding sideways at their own speeds and passing each other, their
 * edges rippling with the waveform, a glint riding the fastest one and a moon crossing the top slowly.
 * Eight seconds on, it is a different picture.
 */
internal class Tide : Layered(
    name = "Tide",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 307L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.05f, cuts = false, seed = 307)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.55f, livelyTrail = 0.45f)

    private val count = genes.choice("Bands", 3, start = 1)
    private val speed = genes.number("Speeds", 0.7f, 1.6f, 1f)
    private val waviness = genes.number("Waviness", 0.3f, 1.2f, 0.7f)
    private val moon = genes.toggle("Moon", start = true)

    private val scroll = FloatArray(MOST)
    private val way = FloatArray(MOST) { if (it % 2 == 0) 1f else -1f }
    private val heights = Array(MOST) { Envelope(attackPerSecond = 2.2f, releasePerSecond = 0.8f) }
    private val swell = FloatArray(MOST)
    private val top = FloatArray(MOST)
    private val tall = FloatArray(MOST)
    private var moonX = 0.15f
    private var rush = 0f
    private val foam = Sprites(260, 1_307L)
    private var foamCredit = 0f
    private val mesh = TriangleMesh(maxVertices = MOST * SEGMENTS * 6 + 16)
    private val glintMesh = TriangleMesh(maxVertices = 32)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val bands = count.count(7, 2)
        if (gestures.phrase) {
            val index = gestures.phrases % bands
            way[index] = -way[index]
        }
        if (gestures.drop) rush = gestures.barSeconds
        rush -= dt
        val frame = state.frame
        var y = 0f
        for (band in 0 until MOST) {
            val barsPerScreen = PERIOD[band] / speed.value
            val direction = if (rush > 0f) 3f else way[band]
            scroll[band] += dt / (gestures.barSeconds * barsPerScreen) * direction * 1.6f
            swell[band] = (swell[band] - dt * 1.5f).coerceAtLeast(0f)
            val along = band.toFloat() / (bands - 1).coerceAtLeast(1)
            val reach = heights[band].advance(0.25f + 0.75f * frame.bandsRel.let { if (it.isEmpty()) 0f else it.sampleAt(along.coerceIn(0f, 1f)) }, dt)
            top[band] = y
            tall[band] = (0.6f + 0.9f * reach + 0.5f * swell[band]) / bands
            if (band < bands) y += tall[band]
        }
        if (gestures.kickHit > 0f) {
            var loudest = 0
            for (band in 1 until bands) if (heights[band].value > heights[loudest].value) loudest = band
            swell[loudest] = 1f
        }
        moonX = wrap(moonX + dt / (gestures.barSeconds * 8f))
        kit.place(0, wrap(scroll[0]), top[0] + tall[0] * 0.5f)
        kit.place(1, moonX, 0.12f)
        foamCredit += dt * (16f + 30f * state.drive) + gestures.hatHit * 3f
        while (foamCredit >= 1f) {
            foamCredit -= 1f
            val band = (random.next() * bands).toInt().coerceIn(0, MOST - 1)
            foam.burst(random.next(), top[band], 1, 0.06f, 1.5f, 0.012f, band.toFloat() / bands, Sprite.GLOW)
        }
        foam.advance(dt, drag = 0.5f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val bands = count.drawn(7, 2)
        val lift = 0.3f + 0.7f * state.lift
        val scope = state.frame.scope
        val keyShift = state.frame.keyHue * state.frame.keyConfidence + genes.walk
        for (band in 0 until bands) {
            val presence = count.presence(band, 7, 2)
            if (presence <= 0.01f) continue
            val along = band.toFloat() / (bands - 1).coerceAtLeast(1)
            val bandTop = top[band] * size.height
            val height = tall[band] * size.height
            val middle = bandTop + height * 0.5f
            for (segment in 0 until SEGMENTS) {
                val x0 = -0.05f + 1.1f * segment / SEGMENTS
                val x1 = -0.05f + 1.1f * (segment + 1) / SEGMENTS
                // Narrow crests that slide with the band. A soft swell sliding past barely shows as motion.
                val crest = 0.5f + 0.5f * sin((x0 - scroll[band]) * TAU * 4f + band)
                val light = 0.2f + 0.8f * crest * crest * crest * crest
                val wave = if (scope.isEmpty()) 0f else scope.sampleAt(x0.coerceIn(0f, 1f)) * height * 0.3f * waviness.value
                val colour = state.palette.argb(keyShift + along * 0.45f, value = 0.3f + 0.7f * light, alpha = (0.15f + 0.65f * light) * lift * presence)
                val clear = colour and 0x00FFFFFF
                val left = x0 * size.width
                val right = x1 * size.width
                val a = mesh.vertex(left, bandTop - wave, clear)
                val b = mesh.vertex(right, bandTop - wave, clear)
                val c = mesh.vertex(right, middle, colour)
                val d = mesh.vertex(left, middle, colour)
                mesh.quad(a, b, c, d)
                val e = mesh.vertex(right, bandTop + height + wave, clear)
                val f = mesh.vertex(left, bandTop + height + wave, clear)
                mesh.quad(d, c, e, f)
            }
        }
        drawMesh(mesh)
        with(foam) { drawSprites(state.palette, genes.walk, alpha = 0.5f * lift, saturation = 0.3f) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        glintMesh.clear()
        val glint = Offset(wrap(scroll[0]) * size.width, (top[0] + tall[0] * 0.5f) * size.height)
        glintMesh.glow(glint.x, glint.y, size.minDimension * 0.03f, state.palette.argb(genes.walk, saturation = 0.3f, value = 1f, alpha = 0.4f + 0.5f * state.lift))
        drawMesh(glintMesh, BlendMode.Plus)
        val shown = moon.weight(1)
        if (shown > 0.01f) {
            val at = Offset(moonX * size.width, size.height * 0.12f)
            val radius = size.minDimension * 0.07f
            drawCircle(Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.35f * shown), 1f to Color.Transparent, center = at, radius = radius * 3f), radius * 3f, at)
            drawCircle(state.palette.cap.copy(alpha = 0.7f * shown), radius, at)
        }
    }

    override fun onReset() {
        scroll.fill(0f)
        for (band in 0 until MOST) way[band] = if (band % 2 == 0) 1f else -1f
        heights.forEach { it.reset(0.3f) }
        swell.fill(0f)
        moonX = 0.15f
        rush = 0f
        foam.clear()
        foamCredit = 0f
    }

    private companion object {
        const val MOST = 11
        const val SEGMENTS = 80
        val PERIOD = floatArrayOf(1.5f, 2.5f, 4f, 6f, 2f, 3f, 5f, 8f, 1.8f, 3.5f, 7f)
    }
}

/**
 * A lamp swinging like a pendulum, one swing every two bars, from a pivot that wanders the screen. A
 * second lamp hangs far behind, moths circle the flame, sparks climb off it and motes rise the full
 * height. A soft onset lets out a puff of motes and a kick makes the flame flare.
 */
internal class Lantern : Layered(
    name = "Lantern",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Calm,
    kit = Kit(
        seed = 308L,
        groundKind = GroundKind.Fog,
        detailKind = DetailKind.Specks,
        detailStrength = 0.5f,
        camera = Camera2D(wander = 0.05f, punch = 0.05f, cuts = false, seed = 308),
    ),
) {
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.84f)

    private val swing = genes.number("Swing", 0.28f, 0.42f, 0.38f)
    private val lamps = genes.toggle("Second lamp", start = true)
    private val moths = genes.choice("Moths", 3, start = 1)
    private val moteRate = genes.number("Motes", 0.6f, 1.8f, 1f)

    private val stage = Stage(reachX = 0.14f, reachY = 0.14f, start = 0.4f)
    private var pendulum = 0f
    private var wide = 0f
    private val flare = Envelope(attackPerSecond = 20f, releasePerSecond = 2f)
    private val glow = Envelope(attackPerSecond = 3f, releasePerSecond = 0.9f)
    private var lampX = 0.5f
    private var lampY = 0.3f
    private var farX = 0.5f
    private val mothAngle = FloatArray(MOTHS) { it * 2.3999632f }
    private val motes = Sprites(260, 1_308L)
    private var credit = 0f
    private val sparks = Sprites(200, 2_308L)
    private var sparkCredit = 0f
    private val risers = Travellers(3)
    private val riserMesh = TriangleMesh(maxVertices = 3 * 12 + 8)
    private val mothMesh = TriangleMesh(maxVertices = MOTHS * 8 + 8)
    private val flies = Swarm(FLIES, 3_308L)
    private var blink = 0f
    private val flyMesh = TriangleMesh(maxVertices = FLIES * 7 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        // One swing from side to side every two bars, hung from wherever the stage has got to.
        pendulum += dt * PI.toFloat() / (gestures.barSeconds * 2f)
        if (gestures.drop) wide = 1f
        wide = (wide - dt / (gestures.barSeconds * 2f)).coerceAtLeast(0f)
        val amplitude = swing.value * (1f + 0.25f * wide)
        val lean = sin(pendulum)
        lampX = stage.x + amplitude * lean
        lampY = stage.y - 0.2f + 0.08f * lean * lean
        farX = stage.x - 0.6f * (lampX - stage.x)
        kit.place(0, lampX, lampY)
        kit.place(1, farX, stage.y - 0.28f, parallax = 0.5f)
        flare.hit(gestures.kickHit)
        flare.advance(0f, dt)
        glow.advance((0.2f + 0.8f * state.frame.loudLong) * (0.25f + 0.75f * state.drive), dt)
        for (moth in 0 until MOTHS) {
            mothAngle[moth] += dt * (2.5f + (moth % 4) * 0.6f) * state.tempo * if (moth % 2 == 0) 1f else -1f
        }
        // Motes rise the whole height; a soft onset lets out a puff of them.
        credit += dt * (8f + 24f * state.drive) * moteRate.value + state.frame.onsetStrength * 1.2f
        while (credit >= 1f) {
            credit -= 1f
            motes.burst(random.next(), 1.02f, 1, 0.24f, 5f, 0.014f, 0.1f + 0.2f * random.next(), Sprite.GLOW, UP, 0.5f)
        }
        motes.advance(dt * (0.8f + 0.6f * state.drive), drag = 0f)
        // Sparks climb off the flame all the time, more of them the harder the music pushes.
        sparkCredit += dt * (16f + 36f * state.drive) + gestures.kickHit * 10f
        while (sparkCredit >= 1f) {
            sparkCredit -= 1f
            sparks.burst(lampX, lampY, 1, 0.3f, 1.2f, 0.016f, 0.05f + 0.1f * random.next(), Sprite.SPARK, UP, 1.2f)
        }
        sparks.advance(dt, drag = 0.4f, gravity = -0.1f)
        if (gestures.bar) risers.spawn(random.next(), 1.05f, random.next(), -0.05f, gestures.barSeconds * 0.9f, PathShape.Wave, 0.05f, 0.022f, 0.15f, 0f, Sprite.GLOW)
        risers.advance(dt)
        kit.follow(2, risers)
        flies.targetX = lampX
        flies.targetY = lampY
        flies.advance(dt, speed = 0.35f + 0.3f * state.drive, pull = 0.4f)
        blink += dt * (3.5f + 2.5f * state.tempo)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val warmth = glow.value + 0.5f * flare.value
        val lift = 0.3f + 0.7f * state.lift
        val far = lamps.weight(1)
        if (far > 0.01f) {
            lamp(state, Offset(farX * size.width, (stage.y - 0.28f) * size.height), size.minDimension * 0.1f, warmth * 0.5f * far * lift)
        }
        val at = Offset(lampX * size.width, lampY * size.height)
        drawLine(state.palette.mid.copy(alpha = 0.3f * lift), Offset(stage.x * size.width, -size.height * 0.1f), at, (size.minDimension * 0.003f).coerceAtLeast(1f))
        lamp(state, at, size.minDimension * (0.18f + 0.2f * warmth), warmth * lift)
        with(motes) { drawSprites(state.palette, genes.walk, alpha = 0.8f * lift, saturation = 0.7f) }
        with(sparks) { drawSprites(state.palette, genes.walk, alpha = lift, saturation = 0.6f) }
        drawTravellers(risers, riserMesh, state.palette, genes.walk, alpha = 0.7f * lift)
    }

    private fun DrawScope.lamp(state: VizRenderState, at: Offset, radius: Float, warmth: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to state.palette.cycled(0.12f, saturation = 0.6f, alpha = (0.08f + 0.3f * warmth).coerceIn(0f, 1f)),
                0.4f to state.palette.cycled(0.05f, saturation = 0.75f, alpha = (0.03f + 0.12f * warmth).coerceIn(0f, 1f)),
                1f to Color.Transparent,
                center = at,
                radius = radius * 2.5f,
            ),
            radius = radius * 2.5f,
            center = at,
        )
        drawCircle(state.palette.cap.copy(alpha = (0.55f + 0.4f * warmth).coerceIn(0f, 1f)), radius * 0.28f, at)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        mothMesh.clear()
        val at = Offset(lampX * size.width, lampY * size.height)
        for (moth in 0 until moths.drawn(12, 6)) {
            val presence = moths.presence(moth, 12, 6)
            if (presence <= 0.01f) continue
            val reach = size.minDimension * (0.06f + 0.06f * ((moth * 7) % 5) / 4f)
            val a = mothAngle[moth]
            val colour = state.palette.argb(0.08f + genes.walk, saturation = 0.3f, value = 1f, alpha = 0.8f * presence)
            mothMesh.glow(at.x + cos(a) * reach, at.y + sin(a) * reach * 0.7f, size.minDimension * 0.012f, colour)
        }
        drawMesh(mothMesh, BlendMode.Plus)
        // Fireflies round the lamp, each blinking on its own beat.
        flyMesh.clear()
        for (index in 0 until FLIES) {
            val on = ((sin(blink * (1f + (index % 5) * 0.3f) + index * 2.1f) - 0.2f) * 2f).coerceIn(0f, 1f)
            if (on <= 0.01f) continue
            val colour = state.palette.argb(0.15f + 0.05f * (index % 3) + genes.walk, saturation = 0.5f, value = 1f, alpha = 0.8f * on)
            flyMesh.glow(flies.x[index] * size.width, flies.y[index] * size.height, size.minDimension * 0.018f, colour)
        }
        drawMesh(flyMesh, BlendMode.Plus)
    }

    override fun onReset() {
        stage.reset()
        flies.scatter()
        blink = 0f
        pendulum = 0f
        wide = 0f
        flare.reset()
        glow.reset(0.3f)
        motes.clear()
        credit = 0f
        sparks.clear()
        sparkCredit = 0f
        risers.clear()
    }

    private companion object {
        const val MOTHS = 24
        const val FLIES = 100
    }
}

/**
 * Drum shapes that appear anywhere and shoot off the screen spinning, each with a mirrored twin. A kick
 * throws a solid shape, a snare an outline, hats throw dashes and confetti, and each bar sends hard
 * stripes across. Nothing is eased: a drum either happened or it did not.
 */
internal class Riot : Layered(
    name = "Riot",
    family = VizFamily.Battery,
    bucket = VizEnergy.High,
    kit = Kit(seed = 112L, groundKind = GroundKind.Hatch, groundDim = 0.8f, detailKind = DetailKind.Grid, camera = Camera2D(wander = 0.1f, seed = 112)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.5f, livelyTrail = 0.42f, calmZoom = 1.01f, livelyZoom = 1.04f)

    private val shapes = genes.choice("Shapes", 3)
    private val zone = genes.choice("Spawn zone", 3, start = 2)
    private val mirror = genes.toggle("Mirror", start = true)
    private val strobe = genes.choice("Strobe", 3, start = 1)

    private val flying = Travellers(56)
    private val confetti = Sprites(300, 1_112L)
    private val mesh = TriangleMesh(maxVertices = 56 * 40)
    private val edgeX = FloatArray(8)
    private val edgeY = FloatArray(8)
    private val blockX = FloatArray(BLOCKS)
    private val blockY = FloatArray(BLOCKS)
    private var kickFlash = 0f
    private var invert = 0f
    private var lastBeat = -1
    private var sinceHit = 99f
    private var flipped = false
    private var stripesLeft = 0f
    private val stripes = Sweep()

    init {
        // The strobe flips the ground outright rather than fading between the two.
        ground?.fadeSeconds = 0.08f
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val kick = gestures.kickHit
        val snare = gestures.snareHit
        val hat = gestures.hatHit
        sinceHit = if (kick > 0f || snare > 0f) 0f else sinceHit + dt
        val drums = sinceHit < gestures.barSeconds
        kickFlash = maxOf(kickFlash - dt * 3.5f, kick)
        invert = (invert - dt / 0.15f).coerceAtLeast(0f)
        if (gestures.drop) invert = 1f
        val beat = (gestures.barPhase * 4f).toInt()
        val newBeat = beat != lastBeat
        lastBeat = beat
        val flip = drums && when (strobe.value) {
            1 -> gestures.bar
            2 -> newBeat
            else -> false
        }
        if (flip) {
            flipped = !flipped
            ground?.kind = if (flipped) GroundKind.Grid else GroundKind.Hatch
        }
        val big = if (gestures.drop) 1.6f else 1f
        if (kick > 0f) {
            launch(KICK, (0.16f + 0.1f * kick) * big, 0.05f, 1.3f, twin = true)
            for (block in 0 until BLOCKS) {
                blockX[block] = random.next()
                blockY[block] = random.next()
            }
        }
        if (snare > 0f) launch(SNARE, (0.12f + 0.08f * snare) * big, 0.55f, 1.5f, twin = true)
        if (hat > 0f) {
            launch(HAT, 0.04f, 0.3f, 1.8f, twin = true)
            launch(HAT, 0.03f, 0.35f, 1.8f, twin = false)
            confetti.sprinkle(12, 0.6f, 0.012f, random.next(), Sprite.SHARD, drift = 0.25f)
        }
        // Without drums the beat throws one small, faint shape, so a quiet passage is not frozen.
        if (newBeat && !drums) launch(SOFT, 0.09f, 0.8f, 0.7f, twin = false)
        if (gestures.bar) stripesLeft = 0.5f
        stripesLeft -= dt / gestures.barSeconds
        stripes.advance(gestures)
        flying.advance(dt)
        kit.follow(0, flying)
        kit.place(1, stripes.position, 0.5f)
        confetti.advance(dt, drag = 0.4f, gravity = 0.2f)
    }

    private fun launch(drum: Int, size: Float, tint: Float, reach: Float, twin: Boolean) {
        val x: Float
        val y: Float
        when (zone.value) {
            0 -> {
                x = 0.5f + random.signed() * 0.12f
                y = 0.5f + random.signed() * 0.12f
            }
            1 -> {
                val along = random.next()
                when ((random.next() * 4f).toInt()) {
                    0 -> { x = 0.05f; y = along }
                    1 -> { x = 0.95f; y = along }
                    2 -> { x = along; y = 0.05f }
                    else -> { x = along; y = 0.95f }
                }
            }
            else -> {
                x = random.next()
                y = random.next()
            }
        }
        val angle = random.next() * TAU
        val toX = x + cos(angle) * reach
        val toY = y + sin(angle) * reach
        val seconds = gestures.beatSeconds * 2f
        val spin = random.signed() * 8f
        flying.spawn(x, y, toX, toY, seconds, size = size, tint = tint + random.next() * 0.1f, spin = spin, kind = drum)
        if (twin && mirror.on) flying.spawn(1f - x, 1f - y, 1f - toX, 1f - toY, seconds, size = size, tint = tint + 0.5f, spin = -spin, kind = drum)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val unit = size.minDimension
        val walk = genes.walk
        val option = shapes.value
        for (slot in 0 until flying.capacity) {
            if (!flying.alive[slot]) continue
            val x = flying.x[slot] * size.width
            val y = flying.y[slot] * size.height
            val radius = flying.size[slot] * unit
            val turn = flying.angle[slot]
            val kind = flying.kind[slot]
            val colour = state.palette.argb(flying.tint[slot] + walk, value = 1f, alpha = if (kind == SOFT) 0.45f else 0.9f)
            when (kind) {
                KICK -> mesh.polygon(x, y, radius, KICK_SIDES[option], turn, colour)
                SNARE, SOFT -> outline(x, y, radius, SNARE_SIDES[option], turn, radius * 0.16f, colour)
                else -> mesh.glyph(3, x, y, radius, turn, colour)
            }
        }
        drawMesh(mesh)
    }

    private fun outline(x: Float, y: Float, radius: Float, sides: Int, turn: Float, width: Float, argb: Int) {
        for (corner in 0..sides) {
            val angle = turn + TAU * corner / sides
            edgeX[corner] = x + cos(angle) * radius
            edgeY[corner] = y + sin(angle) * radius
        }
        mesh.strip(edgeX, edgeY, sides + 1, width, argb)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(confetti) { drawSprites(state.palette, genes.walk) }
        if (kickFlash > 0.02f) {
            val block = size.minDimension * 0.12f
            val colour = state.palette.cap.copy(alpha = (kickFlash * 0.3f).coerceIn(0f, 1f))
            for (index in 0 until BLOCKS) {
                drawRect(colour, Offset(blockX[index] * size.width - block / 2f, blockY[index] * size.height - block / 2f), Size(block, block))
            }
        }
        if (stripesLeft > 0f) {
            val x = stripes.position * size.width
            val width = size.width * 0.02f
            val colour = state.palette.cap.copy(alpha = 0.2f + 0.35f * state.lift)
            for (stripe in 0 until 4) drawRect(colour, Offset(x + (stripe - 2) * width * 2.2f, 0f), Size(width, size.height))
        }
        if (invert > 0f) drawRect(Color.White, alpha = invert.coerceIn(0f, 1f), blendMode = BlendMode.Difference)
    }

    override fun onReset() {
        flying.clear()
        confetti.clear()
        kickFlash = 0f
        invert = 0f
        lastBeat = -1
        sinceHit = 99f
        flipped = false
        stripesLeft = 0f
        ground?.kind = GroundKind.Hatch
    }

    private companion object {
        const val KICK = 0
        const val SNARE = 1
        const val HAT = 2
        const val SOFT = 3
        const val BLOCKS = 3
        val KICK_SIDES = intArrayOf(3, 6, 4)
        val SNARE_SIDES = intArrayOf(4, 3, 5)
    }
}

/**
 * A waterfall of the waveform between drops, and the picture breaking apart when one comes.
 *
 * Between drops three traces are drawn at one edge every frame and the echo carries them across, so the
 * last seconds of sound stack up into a waterfall that fills the screen. On a drop the picture is cut
 * into shards, each a triangle carrying its own piece of it, spinning out to the edges over a bar. A
 * snare breaks off six smaller shards, a kick jolts the waterfall, and dust flies with the pieces.
 */
internal class Shatter : Layered(
    name = "Shatter",
    family = VizFamily.Acid,
    bucket = VizEnergy.High,
    kit = Kit(seed = 811L, groundKind = GroundKind.Grid, groundDim = 0.6f, detailKind = DetailKind.Scan, camera = Camera2D(wander = 0.05f, seed = 811)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.975f, livelyTrail = 0.965f)

    private val shardCount = genes.choice("Shards", 3, start = 1)
    private val shardSize = genes.number("Shard size", 0.7f, 1.4f, 1f)
    private val upward = genes.toggle("Waterfall rises", start = false)
    private val smallShatter = genes.toggle("Snare shatter", start = true)

    private val gain = TraceGain()
    private val jolt = Spring(stiffness = 120f, damping = 0.5f)
    private var previous: ImageBitmap? = null
    private var snapshot: ImageBitmap? = null
    private var pending = 0
    private val homeX = FloatArray(MOST)
    private val homeY = FloatArray(MOST)
    private val shardX = FloatArray(MOST)
    private val shardY = FloatArray(MOST)
    private val speedX = FloatArray(MOST)
    private val speedY = FloatArray(MOST)
    private val turn = FloatArray(MOST)
    private val spin = FloatArray(MOST)
    private val life = FloatArray(MOST)
    private val corners = FloatArray(MOST * 6)
    private val dust = Sprites(240, 1_811L)
    private val comets = Comets()
    private val shard = Path()
    private val trace = Path()

    override fun onPreviousFrame(picture: ImageBitmap?) {
        previous = picture
    }

    // The echo carries the traces away from the edge they are drawn at, faster when a kick jolts it.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val speed = (0.35f + 0.4f * state.drive) * (1f + jolt.value.coerceIn(0f, 2f))
        return EchoFrame(zoomX = base.zoomX, driftY = if (rising()) -speed else speed)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        jolt.kick(gestures.kickHit * 3f)
        jolt.advance(dt)
        if (gestures.drop) {
            pending = shardCount.count(16, 8)
        } else if (gestures.snareHit > 0f && smallShatter.on && life.all { it <= 0f }) {
            pending = SMALL
        }
        for (slot in 0 until MOST) {
            if (life[slot] <= 0f) continue
            life[slot] -= dt / gestures.barSeconds
            shardX[slot] += speedX[slot] * dt
            shardY[slot] += speedY[slot] * dt
            turn[slot] += spin[slot] * dt
        }
        dust.advance(dt, drag = 0.6f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        kit.place(1, 0.5f, if (rising()) 0.9f else 0.1f)
    }

    // The waterfall turns round every phrase.
    private fun rising(): Boolean = upward.on != (gestures.phrases % 2 == 1)

    override fun DrawScope.drawEcho(state: VizRenderState) {
        if (pending > 0) {
            cut(pending)
            pending = 0
        }
        drawShards(state)
        drawWaterfall(state)
        with(dust) { drawSprites(state.palette, genes.walk) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.5f + 0.5f * state.lift) }
    }

    /**
     * Copies the picture as it stood and cuts it into triangles flying out from the middle: a grid of
     * them over the whole screen on a drop, or a fan of six round one point on a snare.
     */
    private fun DrawScope.cut(count: Int) {
        val source = previous ?: return
        val copy = snapshot?.takeIf { it.width == source.width && it.height == source.height }
            ?: ImageBitmap(source.width, source.height).also { snapshot = it }
        Canvas(copy).drawImage(source, Offset.Zero, Paint().apply { blendMode = BlendMode.Src })
        life.fill(0f)
        if (count == SMALL) {
            val middleX = 0.2f + 0.6f * random.next()
            val middleY = 0.2f + 0.6f * random.next()
            val reach = 0.12f * shardSize.value
            for (slot in 0 until SMALL) {
                val from = TAU * slot / SMALL
                val to = TAU * (slot + 1) / SMALL
                launch(slot, middleX, middleY, 0f, 0f, cos(from) * reach / kit.aspect, sin(from) * reach, cos(to) * reach / kit.aspect, sin(to) * reach, 0.6f)
            }
            return
        }
        val columns = 4
        val rows = count / (columns * 2)
        var slot = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = column.toFloat() / columns
                val right = (column + 1f) / columns
                val top = row.toFloat() / rows
                val bottom = (row + 1f) / rows
                val jitter = 0.04f * shardSize.value
                val x = left + (right - left) * (0.5f + random.signed() * 0.3f)
                val y = top + (bottom - top) * (0.5f + random.signed() * 0.3f)
                // Two triangles a cell, split along a diagonal that wanders.
                launch(slot++, x, y, left - x, top - y, right - x + random.signed() * jitter, top - y, left - x, bottom - y + random.signed() * jitter, 1f)
                launch(slot++, x, y, right - x, top - y, right - x, bottom - y, left - x + random.signed() * jitter, bottom - y, 1f)
            }
        }
    }

    // One shard: its home in shares of the screen and its three corners measured from there.
    private fun launch(slot: Int, x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, speed: Float) {
        if (slot >= MOST) return
        val middleX = x + (ax + bx + cx) / 3f
        val middleY = y + (ay + by + cy) / 3f
        homeX[slot] = x
        homeY[slot] = y
        shardX[slot] = x
        shardY[slot] = y
        val awayX = middleX - 0.5f
        val awayY = middleY - 0.5f
        val length = kotlin.math.sqrt(awayX * awayX + awayY * awayY).coerceAtLeast(0.05f)
        val pace = speed * (0.5f + 0.5f * random.next())
        speedX[slot] = awayX / length * pace
        speedY[slot] = awayY / length * pace
        spin[slot] = random.signed() * 4f
        turn[slot] = 0f
        life[slot] = 1f
        corners[slot * 6] = ax
        corners[slot * 6 + 1] = ay
        corners[slot * 6 + 2] = bx
        corners[slot * 6 + 3] = by
        corners[slot * 6 + 4] = cx
        corners[slot * 6 + 5] = cy
        dust.burst(middleX, middleY, 3, 0.4f, 0.8f, 0.01f, random.next(), Sprite.SPARK)
    }

    // Each shard is the copied picture clipped to its triangle, moved and turned with it.
    private fun DrawScope.drawShards(state: VizRenderState) {
        val picture = snapshot ?: return
        val edge = (size.minDimension * 0.004f).coerceAtLeast(1f)
        for (slot in 0 until MOST) {
            val left = life[slot]
            if (left <= 0f) continue
            val x = homeX[slot] * size.width
            val y = homeY[slot] * size.height
            shard.reset()
            shard.moveTo(x + corners[slot * 6] * size.width, y + corners[slot * 6 + 1] * size.height)
            shard.lineTo(x + corners[slot * 6 + 2] * size.width, y + corners[slot * 6 + 3] * size.height)
            shard.lineTo(x + corners[slot * 6 + 4] * size.width, y + corners[slot * 6 + 5] * size.height)
            shard.close()
            withTransform({
                translate((shardX[slot] - homeX[slot]) * size.width, (shardY[slot] - homeY[slot]) * size.height)
                rotate(turn[slot] * 57.29578f, Offset(x, y))
            }) {
                clipPath(shard) { drawImage(picture, alpha = left.coerceIn(0f, 1f)) }
                drawPath(shard, state.palette.cap.copy(alpha = (0.6f * left).coerceIn(0f, 1f)), style = Stroke(edge))
            }
        }
    }

    // Three traces at the edge the waterfall starts from; the echo does the rest.
    private fun DrawScope.drawWaterfall(state: VizRenderState) {
        val frame = state.frame
        val scale = gain.update(frame.scopeLeft, frame.scopeRight, state.deltaSeconds)
        val edge = size.height * if (rising()) 0.9f else 0.1f
        val reach = size.height * 0.08f * (0.6f + state.lift) * scale
        for (index in 0 until 3) {
            val samples = when (index) {
                0 -> frame.scopeLeft
                1 -> frame.scopeRight
                else -> frame.scope
            }
            if (samples.size < 2) continue
            trace.reset()
            val step = size.width / (samples.size - 1)
            for (point in samples.indices) {
                val y = edge + (index - 1) * size.height * 0.03f - samples[point].coerceIn(-1f, 1f) * reach
                if (point == 0) trace.moveTo(0f, y) else trace.lineTo(point * step, y)
            }
            drawPath(
                trace,
                state.palette.cycled(genes.walk + index * 0.3f, value = 1f, alpha = (0.12f + 0.85f * state.lift).coerceIn(0f, 1f)),
                style = Stroke((size.minDimension * (0.004f + 0.004f * state.lift)).coerceAtLeast(1.2f), cap = StrokeCap.Round),
            )
        }
    }

    override fun onReset() {
        gain.reset()
        jolt.reset()
        previous = null
        pending = 0
        life.fill(0f)
        dust.clear()
        comets.clear()
    }

    private companion object {
        const val MOST = 32
        const val SMALL = 6
    }
}

/**
 * A machine of pistons that slides across the screen, faster as the music drives. The bottom set slams
 * on the kick; a top set, playing the treble half, hangs down to a jaw above each head that the snare
 * closes. Blocks climb every column, caps fly up and fall back as shards, and the camera jolts on every
 * slam. No attack curve anywhere: a slam lands in one frame.
 */
internal class Piston : Layered(
    name = "Piston",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.High,
    kit = Kit(seed = 206L, groundKind = GroundKind.Grid, detailKind = DetailKind.Scan, camera = Camera2D(wander = 0.05f, punch = 0.16f, seed = 206)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.45f, livelyTrail = 0.35f, livelyZoom = 1.01f)

    private val pistons = genes.choice("Pistons", 3, start = 2)
    private val reversed = genes.toggle("Travel reversed", start = false)
    private val shardKind = genes.choice("Cap shards", 3)
    private val twoSided = genes.toggle("Two sided", start = true)

    private val stage = Stage(reachX = 0f, reachY = 0.25f, start = 0f)
    private var climb = 0f
    private val bottom = FloatArray(MOST)
    private val top = FloatArray(MOST)
    private val caps = Array(MOST) { Spring(stiffness = 120f, damping = 0.35f) }
    private val chosen = BooleanArray(MOST)
    private var slide = 0f
    private var direction = 1f
    private var pump = 0f
    private val comets = Comets(kind = Sprite.SPARK)
    private val thrown = Sprites(300, 1_206L)
    private val flashes = Sprites(160, 2_206L)
    private val mesh = TriangleMesh(maxVertices = MOST * 90 * 4 + 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        val bands = frame.bandsRel
        val count = pistons.count(16, 8)
        if (gestures.phrase) direction = -direction
        // Everything runs at the pace the music pushes, so under a pad the machine all but stops.
        stage.advance(dt * (0.3f + state.drive))
        climb = wrap(climb + dt * (0.3f + 14f * state.drive))
        slide += dt * 10f * state.drive / gestures.barSeconds * direction * if (reversed.on) -1f else 1f
        pump += dt * (0.5f + 4f * state.drive)
        val all = gestures.drop
        // Only real hits throw the pistons; the soft onsets of a pad leave the machine pumping.
        val kick = if (all) 1f else gestures.kickHit
        val snare = if (all) 1f else gestures.snareHit
        var tallest = 0
        for (column in 0 until count) {
            val along = column.toFloat() / (count - 1)
            val low = if (bands.isEmpty()) 0f else bands.sampleAt(along)
            val high = if (bands.isEmpty()) 0f else bands.sampleAt(1f - along)
            // How far a hit throws a piston depends on how hard the music is pushing, not only on how loud the band is.
            if (kick > 0f) bottom[column] = 0.2f + low * (0.4f + 0.6f * state.drive) + if (all) 0.3f else 0f
            if (snare > 0f) top[column] = 0.2f + high * (0.4f + 0.6f * state.drive) + if (all) 0.3f else 0f
            // Falls fast, so the next hit has somewhere to throw it from.
            // Between hits a slow pump runs along the machine, so it never stands still.
            val rest = 0.1f + 0.08f * sin(pump + column * 0.7f)
            bottom[column] = (bottom[column] - dt * 1.6f).coerceAtLeast(low * 0.3f + rest)
            top[column] = (top[column] - dt * 1.6f).coerceAtLeast(high * 0.3f + rest)
            caps[column].kick(frame.kick * 4f * (0.4f + low))
            caps[column].advance(dt)
            if (bottom[column] > bottom[tallest]) tallest = column
        }
        val reach = reach()
        val rise = stage.y - 0.5f
        kit.place(0, columnX(tallest, count), 1f - (0.1f + reach * bottom[tallest]) + rise)
        kit.place(1, columnX(0, count), 0.5f)
        if (kick > 0f || snare > 0f) camera.shove(maxOf(kick, snare) * 0.5f)
        if (kick > 0f) {
            val kind = when (shardKind.value) {
                0 -> Sprite.DASH
                1 -> Sprite.SHARD
                else -> Sprite.DIAMOND
            }
            // The three tallest throw their caps up to the top edge, and gravity brings them back as shards.
            chosen.fill(false)
            repeat(3) {
                var best = -1
                for (column in 0 until count) if (!chosen[column] && (best < 0 || bottom[column] > bottom[best])) best = column
                if (best < 0) return@repeat
                chosen[best] = true
                val x = columnX(best, count)
                val y = 1f - (0.1f + reach * bottom[best]) + rise
                thrown.burst(x, y, 3, 1.25f, 1.8f, 0.022f, best.toFloat() / count, kind, UP, 0.3f)
                flashes.burst(x, y, 1, 0f, 0.15f, 0.08f, best.toFloat() / count, Sprite.GLOW)
                flashes.burst(x, y, 6, 0.2f, 0.7f, 0.035f, 0.5f, Sprite.GLOW, UP, 0.8f)
            }
        }
        if (snare > 0f && twoSided.on) {
            for (column in 0 until count step 3) flashes.burst(columnX(column, count), 0.9f - reach * bottom[column] + rise - jaw(column), 1, 0f, 0.12f, 0.05f, 0.5f, Sprite.GLOW)
        }
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
        thrown.advance(dt, drag = 0.2f, gravity = 1.3f)
        flashes.advance(dt, drag = 3f)
    }

    private fun reach(): Float = 0.92f - 0.3f * twoSided.weight(1)

    /** How far above a piston's head the one hanging over it stops, as a share of the height: the snare closes it. */
    private fun jaw(column: Int): Float = 0.05f + 0.2f * (1f - top[column].coerceIn(0f, 1f))

    // Up to ten columns a bar as the drive rises: each piston keeps its band and the whole machine moves, entering one side and leaving the other.
    private fun columnX(column: Int, count: Int): Float = wrap((column + 0.5f + slide) / count)

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val count = pistons.count(16, 8)
        val slot = size.width / count
        val reach = reach()
        // The machine glows as hard as the music pushes it.
        val shine = 0.4f + 0.6f * state.lift
        val high = state.palette.high.copy(alpha = shine).toArgb()
        val low = state.palette.low.copy(alpha = shine).toArgb()
        val cap = state.palette.cap.toArgb()
        val hanging = twoSided.weight(1)
        val hangHigh = state.palette.mid.copy(alpha = hanging * shine).toArgb()
        val hangLow = state.palette.low.copy(alpha = hanging * shine).toArgb()
        val capHeight = (size.height * 0.012f).coerceAtLeast(2f)
        // The stage rides the machine up and down, and the bars run past both edges so no gap shows.
        val shift = (stage.y - 0.5f) * size.height
        val block = size.height * 0.035f
        for (column in 0 until count) {
            val x = columnX(column, count) * size.width
            val left = x - slot * 0.44f
            val right = x + slot * 0.44f
            val head = size.height * (0.9f - reach * bottom[column]) + shift
            blocks(left, right, head, size.height * 1.3f, high, low, block, climb)
            val capAt = head - size.height * 0.06f * caps[column].value.coerceIn(0f, 3f)
            mesh.bar(left, right, capAt - capHeight, capAt, cap, cap)
            if (hanging > 0.01f) blocks(left, right, -size.height * 0.3f, head - size.height * jaw(column), hangLow, hangHigh, block, 1f - climb)
        }
        drawMesh(mesh)
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    // A bar as blocks that climb it, so a piston standing still still moves.
    private fun blocks(left: Float, right: Float, head: Float, foot: Float, high: Int, low: Int, block: Float, shift: Float) {
        var bottom = foot + shift * block
        while (bottom > head) {
            val top = maxOf(bottom - block * 0.5f, head)
            if (top < bottom) mesh.bar(left, right, top, bottom, high, low)
            bottom -= block
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(thrown) { drawSprites(state.palette, genes.walk) }
        with(flashes) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        climb = 0f
        bottom.fill(0f)
        top.fill(0f)
        caps.forEach { it.reset() }
        slide = 0f
        direction = 1f
        pump = 0f
        thrown.clear()
        flashes.clear()
        comets.clear()
    }

    private companion object {
        const val MOST = 32
    }
}

/**
 * Dark between the hits, apart from a faint fog. Each hit puts its figure at the next place in a
 * sequence across the screen, and the echo carries the figure before it away in a direction each hit
 * picks. Shards fly for one beat, and a drop whites the screen out.
 */
internal class Blackout : Layered(
    name = "Blackout",
    family = VizFamily.Battery,
    bucket = VizEnergy.High,
    kit = Kit(
        seed = 113L,
        groundKind = GroundKind.Fog,
        groundDim = 0.65f,
        detailKind = DetailKind.Specks,
        detailStrength = 0.5f,
        camera = Camera2D(wander = 0.06f, seed = 113),
    ),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.8f)

    private val figure = genes.choice("Figure", 3)
    private val placement = genes.choice("Placement", 3)
    private val afterimage = genes.choice("Afterimage", 3)
    private val strobe = genes.toggle("Strobe", start = false)

    private val stage = Stage(reachX = 0.12f, reachY = 0.15f, start = 2.8f)
    private var flash = 0f
    private var turn = 0f
    private var step = 0
    private var atX = 0.5f
    private var atY = 0.5f
    private var driftX = 0f
    private var driftY = 0f
    private var whiteOut = 0f
    private var lastBeat = -1
    private val shards = Sprites(240, 1_113L)
    private val sparkles = Sprites(60, 2_113L)
    private val shape = Path()

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, driftX = driftX, driftY = driftY)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        // Already rising before the hit, because the audio for it has been analysed and is waiting to be played.
        val coming = state.anticipation(window = 0.06f)
        var landed = maxOf(gestures.kickHit, gestures.snareHit * 0.8f)
        val beat = (gestures.barPhase * 4f).toInt()
        val newBeat = beat != lastBeat
        lastBeat = beat
        if (strobe.on) landed = maxOf(landed, gestures.hatHit * 0.45f)
        // Without drums the beat itself is a soft hit, so a quiet passage still shows faint figures.
        if (newBeat && landed <= 0f) landed = 0.4f
        if (landed > flash) move(landed)
        flash = maxOf(flash - dt * 5f, maxOf(landed, coming * 0.5f))
        if (gestures.drop) whiteOut = 1f
        whiteOut = (whiteOut - dt / 0.4f).coerceAtLeast(0f)
        if (gestures.hatHit > 0f) sparkles.sprinkle(1, 0.15f, 0.025f, random.next(), Sprite.GLOW, drift = 0f)
        shards.advance(dt, drag = 0.3f)
        sparkles.advance(dt)
        kit.place(0, atX, atY)
    }

    private fun move(strength: Float) {
        val fromX = atX
        val fromY = atY
        step++
        when (placement.value) {
            0 -> {
                atX = ROW[step % ROW.size]
                atY = 0.5f
            }
            1 -> {
                atX = SPOTS_X[step % SPOTS_X.size]
                atY = SPOTS_Y[step % SPOTS_Y.size]
            }
            else -> {
                val angle = step * 2.3999631f
                atX = 0.5f + 0.32f * cos(angle)
                atY = 0.5f + 0.3f * sin(angle)
            }
        }
        atX += stage.x - 0.5f
        atY += stage.y - 0.5f
        turn += 0.618f
        val away = when (afterimage.value) {
            0 -> atan2(fromY - 0.5f, fromX - 0.5f)
            1 -> turn * TAU
            else -> random.next() * TAU
        }
        driftX = cos(away) * 0.9f
        driftY = sin(away) * 0.9f
        shards.burst(atX, atY, (6 + 10 * strength).toInt(), 0.8f, gestures.beatSeconds, 0.018f, turn * 0.12f, Sprite.SHARD)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        if (flash <= 0.02f) return
        val at = Offset(atX * size.width, atY * size.height)
        val lift = if (whiteOut > 0f) 1.5f else 1f
        val reach = sceneRadius * (0.45f + 0.35f * flash) * lift
        val alpha = flash.coerceIn(0f, 1f)
        val colour = state.palette.cycled(turn * 0.12f + genes.walk, alpha = alpha)
        val width = (size.minDimension * 0.022f * flash).coerceAtLeast(2f)
        val bands = state.frame.bandsRel
        val outline = figure.weight(0)
        if (outline > 0.01f) {
            shape.reset()
            for (spoke in 0..SPOKES) {
                val along = spoke.toFloat() / SPOKES
                val energy = bands.sampleAt(if (along <= 0.5f) along * 2f else (1f - along) * 2f)
                val point = polar(at, TAU * along + turn, reach * (0.45f + 0.85f * energy))
                if (spoke == 0) shape.moveTo(point.x, point.y) else shape.lineTo(point.x, point.y)
            }
            shape.close()
            drawPath(shape, colour.copy(alpha = alpha * outline * 0.22f))
            drawPath(shape, colour.copy(alpha = alpha * outline), style = Stroke(width, cap = StrokeCap.Round))
        }
        val bars = figure.weight(1)
        if (bars > 0.01f) {
            for (spoke in 0 until SPOKES) {
                val along = spoke.toFloat() / SPOKES
                val energy = bands.sampleAt(if (along <= 0.5f) along * 2f else (1f - along) * 2f)
                val angle = TAU * along + turn
                drawLine(colour.copy(alpha = alpha * bars), polar(at, angle, reach * 0.25f), polar(at, angle, reach * (0.35f + 0.9f * energy)), width, StrokeCap.Round)
            }
        }
        val ring = figure.weight(2)
        if (ring > 0.01f) {
            drawCircle(colour.copy(alpha = alpha * ring * 0.2f), reach * (0.6f + 0.4f * state.bassMotion), at)
            drawCircle(colour.copy(alpha = alpha * ring), reach * (0.6f + 0.4f * state.bassMotion), at, style = Stroke(width * 1.5f))
            drawCircle(colour.copy(alpha = alpha * ring * 0.6f), reach * 0.35f, at, style = Stroke(width))
        }
        drawCircle(state.palette.cap.copy(alpha = (flash * 0.6f).coerceIn(0f, 1f)), size.minDimension * 0.02f * flash, at)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(shards) { drawSprites(state.palette, genes.walk) }
        with(sparkles) { drawSprites(state.palette, genes.walk) }
        if (whiteOut > 0f) drawRect(state.palette.cap, alpha = (whiteOut * 0.9f).coerceIn(0f, 1f))
    }

    override fun onReset() {
        stage.reset()
        flash = 0f
        turn = 0f
        step = 0
        atX = 0.5f
        atY = 0.5f
        driftX = 0f
        driftY = 0f
        whiteOut = 0f
        lastBeat = -1
        shards.clear()
        sparkles.clear()
    }

    private companion object {
        const val SPOKES = 18
        val ROW = floatArrayOf(0.18f, 0.5f, 0.82f)
        val SPOTS_X = floatArrayOf(0.2f, 0.8f, 0.5f, 0.2f, 0.8f)
        val SPOTS_Y = floatArrayOf(0.25f, 0.75f, 0.5f, 0.75f, 0.25f)
    }
}
