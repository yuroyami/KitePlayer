package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
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
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import androidx.compose.ui.graphics.BlendMode
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import kotlin.math.cos
import kotlin.math.sin
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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Size),
        VizDrive(VizDriver.SlowLevel, VizProperty.Size),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Size, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
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
        if (gestures.section) direction = -direction
        lap += dt / (gestures.cycleSeconds * 4f) * direction * TAU
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
        ground?.dim = (0.2f + 0.7f * swell.value) * (0.35f + 0.9f * state.lift)
        turn += dt * 1f * state.tempo
        if (gestures.drop) widen = 1f
        widen = (widen - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        for (mote in 0 until MOTES) {
            moteAngle[mote] += dt * (1.6f * state.idle + (mote % 5) * 0.4f) * state.tempo * if (mote % 2 == 0) 1f else -1f
        }
        wisps.advance(state, gestures, random)
        kit.follow(1, wisps.travellers)
    }

    private fun moteRadius(mote: Int): Float = 0.1f + 0.28f * ((mote * 37) % 17) / 16f

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val at = Offset(orbX * size.width, orbY * size.height)
        val tint = state.frame.keyHue * state.frame.keyConfidence + lean.value + genes.walk
        val lift = 0.075f + 1.15f * state.lift
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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.envelope(0.8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Speed, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
    )
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
        if (gestures.section) {
            val index = gestures.sections % bands
            way[index] = -way[index]
        }
        if (gestures.drop) rush = gestures.cycleSeconds
        rush -= dt
        val frame = state.frame
        var y = 0f
        for (band in 0 until MOST) {
            val barsPerScreen = PERIOD[band] / speed.value
            val direction = if (rush > 0f) 3f else way[band]
            scroll[band] += dt / (gestures.cycleSeconds * barsPerScreen) * direction * 1.6f
            swell[band] = (swell[band] - dt * 1.5f).coerceAtLeast(0f)
            val along = band.toFloat() / (bands - 1).coerceAtLeast(1)
            val reach = heights[band].advance(0.25f + 0.75f * frame.bandsRel.let { if (it.isEmpty()) 0f else it.sampleAt(along.coerceIn(0f, 1f)) }, dt)
            top[band] = y
            tall[band] = (0.6f + 0.9f * reach + 0.5f * swell[band]) / bands
            if (band < bands) y += tall[band]
        }
        if (gestures.kick > 0f) {
            var loudest = 0
            for (band in 1 until bands) if (heights[band].value > heights[loudest].value) loudest = band
            swell[loudest] = 0.4f + 0.6f * gestures.kick
        }
        moonX = wrap(moonX + dt / (gestures.cycleSeconds * 8f))
        kit.place(0, wrap(scroll[0]), top[0] + tall[0] * 0.5f)
        kit.place(1, moonX, 0.12f)
        foamCredit += dt * (16f * state.idle + 30f * state.drive) + gestures.hat * 3f
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
 * A lamp swinging like a pendulum, one swing every two visual cycles, from a pivot that wanders the screen. A
 * second lamp hangs far behind, moths circle the flame, sparks climb off it and motes rise the full
 * height. Arriving energy lets out a puff of motes and a kick makes the flame flare.
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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.SlowLevel, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.3f)),
        VizDrive(VizDriver.Drop, VizProperty.Size, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
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
        stage.advance(dt * state.idle)
        // One swing from side to side every two visual cycles, hung from wherever the stage has got to.
        pendulum += dt * PI.toFloat() / (gestures.cycleSeconds * 2f)
        if (gestures.drop) wide = 1f
        wide = (wide - dt / (gestures.cycleSeconds * 2f)).coerceAtLeast(0f)
        val amplitude = swing.value * (1f + 0.25f * wide)
        val lean = sin(pendulum)
        lampX = stage.x + amplitude * lean
        lampY = stage.y - 0.2f + 0.08f * lean * lean
        farX = stage.x - 0.6f * (lampX - stage.x)
        kit.place(0, lampX, lampY)
        kit.place(1, farX, stage.y - 0.28f, parallax = 0.5f)
        flare.hit(gestures.kick)
        flare.advance(0f, dt)
        glow.advance((0.2f + 0.8f * state.frame.loudLong) * (0.25f + 0.75f * state.drive), dt)
        for (moth in 0 until MOTHS) {
            mothAngle[moth] += dt * (2.5f * state.idle + (moth % 4) * 0.6f) * state.tempo * if (moth % 2 == 0) 1f else -1f
        }
        // Motes rise the whole height; arriving energy lets out a puff of them.
        credit += dt * (8f * state.idle + 24f * state.drive) * moteRate.value + state.frame.onsetStrength * 1.2f
        while (credit >= 1f) {
            credit -= 1f
            motes.burst(random.next(), 1.02f, 1, 0.24f, 5f, 0.014f, 0.1f + 0.2f * random.next(), Sprite.GLOW, UP, 0.5f)
        }
        motes.advance(dt * (0.8f + 0.6f * state.drive), drag = 0f)
        // Sparks climb off the flame all the time, more of them the harder the music pushes.
        sparkCredit += dt * (16f * state.idle + 36f * state.drive) + gestures.kick * 10f
        while (sparkCredit >= 1f) {
            sparkCredit -= 1f
            sparks.burst(lampX, lampY, 1, 0.3f, 1.2f, 0.016f, 0.05f + 0.1f * random.next(), Sprite.SPARK, UP, 1.2f)
        }
        sparks.advance(dt, drag = 0.4f, gravity = -0.1f)
        if (gestures.section) risers.spawn(random.next(), 1.05f, random.next(), -0.05f, gestures.cycleSeconds * 0.9f, PathShape.Wave, 0.05f, 0.022f, 0.15f, 0f, Sprite.GLOW)
        risers.advance(dt)
        kit.follow(2, risers)
        flies.targetX = lampX
        flies.targetY = lampY
        flies.advance(dt, speed = 0.35f + 0.3f * state.drive, pull = 0.4f)
        blink += dt * (3.5f * state.idle + 2.5f * state.tempo)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val warmth = glow.value + 0.5f * flare.value
        val lift = 0.075f + 1.15f * state.lift
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
        drawCircle(state.palette.cap.copy(alpha = (0.18f + 0.75f * warmth).coerceIn(0f, 1f)), radius * 0.28f, at)
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
 * A waterfall of the waveform between drops, and the picture breaking apart when one comes.
 *
 * Between drops three traces are drawn at one edge every frame and the echo carries them across, so the
 * last seconds of sound stack up into a waterfall that fills the screen. On a drop the picture is cut
 * into shards, each a triangle carrying its own piece of it, spinning out to the edges over a visual cycle. A
 * snare breaks off six smaller shards, a kick jolts the waterfall, and dust flies with the pieces.
 */
internal class Shatter : Layered(
    name = "Shatter",
    family = VizFamily.Acid,
    bucket = VizEnergy.High,
    kit = Kit(seed = 811L, groundKind = GroundKind.Grid, groundDim = 0.6f, detailKind = DetailKind.Scan, camera = Camera2D(wander = 0.05f, seed = 811)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Camera, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Discrete),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.975f, livelyTrail = 0.965f)

    private val shardCount = genes.choice("Shards", 3, start = 1)
    private val shardSize = genes.number("Shard size", 0.7f, 1.4f, 1f)
    private val upward = genes.toggle("Waterfall rises", start = false)
    private val smallShatter = genes.toggle("Snare shatter", start = true)

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
        // Mostly the music, not mostly a floor: at a floor of 0.35 against 0.4 of drive, this ran
        // at nine tenths of its drum speed under a quiet pad and said nothing about the music.
        val speed = (0.08f + 1.1f * state.drive) * (1f + jolt.value.coerceIn(0f, 2f))
        return EchoFrame(zoomX = base.zoomX, driftY = if (rising()) -speed else speed)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        jolt.kick(gestures.kick * 3f)
        jolt.advance(dt)
        if (gestures.drop) {
            pending = shardCount.count(16, 8)
        } else if (gestures.snare > 0f && smallShatter.on && life.all { it <= 0f }) {
            pending = SMALL
        }
        for (slot in 0 until MOST) {
            if (life[slot] <= 0f) continue
            life[slot] -= dt / gestures.cycleSeconds
            shardX[slot] += speedX[slot] * dt
            shardY[slot] += speedY[slot] * dt
            turn[slot] += spin[slot] * dt
        }
        dust.advance(dt, drag = 0.6f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        kit.place(1, 0.5f, if (rising()) 0.9f else 0.1f)
    }

    // The waterfall turns round at each supported section boundary.
    private fun rising(): Boolean = upward.on != (gestures.sections % 2 == 1)

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
        val scale = frame.waveformGain
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
