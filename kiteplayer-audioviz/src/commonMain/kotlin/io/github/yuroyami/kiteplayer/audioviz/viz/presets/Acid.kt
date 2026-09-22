package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Soft blobs on up to three depths, crossing the screen on looping paths once every two visual cycles, added
 * together and carried by a flow warp that drifts toward the loudest band. A kick splits blobs in two
 * and sends the halves flying, and a drop pulls every blob to the middle and throws them out again.
 */
internal class Melt : Layered(
    name = "Melt",
    family = VizFamily.Acid,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 802L, groundKind = GroundKind.Cloud, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 802)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Speed),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.7f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Pulse, VizProperty.Shape),
    )
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.88f, calmZoom = 1.004f, livelyZoom = 1.012f)
    override val warp: WarpSpec = WarpSpec(WarpFields.FLOW, calmAmount = 0.024f, livelyAmount = 0.1f)

    private val blobs = genes.choice("Blobs", 3, start = 2)
    private val depths = genes.choice("Depths", 3, start = 2)
    private val paths = genes.choice("Paths", 3)
    private val flow = genes.number("Flow", 0.6f, 1.6f, 1f)

    private var phase = 0f
    private var gather = 0f
    private var loudX = 0.5f
    private val blobX = FloatArray(MOST) { 0.5f }
    private val blobY = FloatArray(MOST) { 0.5f }
    private val halves = Travellers(16)
    private val halfMesh = TriangleMesh(maxVertices = 16 * 12 + 8)
    private val specks = Sprites(240, 1_802L)
    private var speckCredit = 0f

    // The flow drifts toward whichever side the loudest band is on.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, spin = base.spin, driftX = (loudX - 0.5f) * 0.25f)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        warp.strength = flow.value
        phase += state.stepSeconds * TAU / (gestures.cycleSeconds * 2f) * (0.12f + 1.7f * state.drive)
        if (gestures.section) paths.choose((paths.value + 1) % 3)
        if (gestures.drop) gather = 1f
        gather = (gather - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        val pull = sin(gather * PI.toFloat())
        for (index in 0 until MOST) {
            var x = 0f
            var y = 0f
            for (family in 0 until 3) {
                val share = paths.weight(family)
                if (share <= 0f) continue
                x += share * 0.42f * sin(FX[family] * phase + index * GOLDEN)
                y += share * 0.38f * sin(FY[family] * phase + index * GOLDEN * 1.7f + SHIFT[family])
            }
            blobX[index] = 0.5f + x * (1f - pull)
            blobY[index] = 0.5f + y * (1f - pull)
            if (index < 3) kit.place(index, blobX[index], blobY[index])
        }
        val bands = state.frame.bandsRel
        if (bands.isNotEmpty()) {
            var loudest = 0
            for (index in bands.indices) if (bands[index] > bands[loudest]) loudest = index
            loudX += ((loudest + 0.5f) / bands.size - loudX) * (dt * 2f).coerceAtMost(1f)
        }
        if (gestures.kick > 0f) {
            val blob = (random.next() * blobs.count(10, 4)).toInt().coerceIn(0, MOST - 1)
            val a = random.next() * TAU
            val apart = 0.25f + 0.5f * gestures.kick
            for (side in 0 until 2) {
                val way = if (side == 0) 1f else -1f
                halves.spawn(blobX[blob], blobY[blob], blobX[blob] + cos(a) * apart * way, blobY[blob] + sin(a) * apart * way, gestures.beatSeconds * 1.5f, PathShape.Arc, 0.1f * way, 0.05f, blob.toFloat() / MOST, 0f, Sprite.GLOW)
            }
        }
        halves.advance(dt)
        kit.follow(3, halves)
        speckCredit += dt * (30f * state.idle + 60f * state.drive)
        while (speckCredit >= 1f) {
            speckCredit -= 1f
            specks.sprinkle(1, 2f, 0.01f, random.next(), Sprite.GLOW, drift = 0.02f)
        }
        specks.advance(dt, drag = 0.2f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val count = blobs.drawn(10, 4)
        val layers = depths.count(1)
        for (pass in 0 until layers) {
            for (index in pass until count step layers) {
                val presence = blobs.presence(index, 10, 4)
                if (presence <= 0.01f) continue
                val depth = (pass + 1f) / layers
                val along = index.toFloat() / MOST
                val energy = state.frame.bandsRel.sampleAt(along)
                val at = Offset(blobX[index] * size.width, blobY[index] * size.height)
                val radius = (sceneRadius * (0.08f + 0.16f * energy + 0.08f * state.lift) * (0.6f + 0.6f * depth)).coerceAtLeast(1f)
                val tint = along + state.musicTime * 0.11f + genes.walk
                drawCircle(
                    Brush.radialGradient(
                        0f to state.palette.cycled(tint, alpha = ((0.03f + 0.2f * state.energy) * presence).coerceIn(0f, 1f)),
                        0.5f to state.palette.cycled(tint, alpha = ((0.01f + 0.06f * state.energy) * presence).coerceIn(0f, 1f)),
                        1f to Color.Transparent,
                        center = at,
                        radius = radius,
                    ),
                    radius,
                    at,
                    blendMode = BlendMode.Plus,
                )
                // A thin rim, so the blob's path reads as motion and not only as a wash of light.
                drawCircle(state.palette.cycled(tint, value = 1f, alpha = ((0.08f + 0.25f * state.lift) * presence).coerceIn(0f, 1f)), radius * 0.7f, at, style = Stroke((size.minDimension * 0.004f).coerceAtLeast(1f)), blendMode = BlendMode.Plus)
            }
        }
        drawTravellers(halves, halfMesh, state.palette, genes.walk, alpha = 0.6f)
        with(specks) { drawSprites(state.palette, genes.walk, alpha = 0.7f) }
    }

    override fun onReset() {
        phase = 0f
        gather = 0f
        loudX = 0.5f
        halves.clear()
        specks.clear()
        speckCredit = 0f
    }

    private companion object {
        const val MOST = 18
        const val GOLDEN = 2.3999632f
        val FX = floatArrayOf(1f, 2f, 3f)
        val FY = floatArrayOf(2f, 3f, 2f)
        val SHIFT = floatArrayOf(0f, 0f, 1.5707964f)
    }
}

/**
 * A few very large, slow blobs travelling right across the screen, one lap every four bars, through a
 * lens warp whose centre drifts. A slow sweep of light crosses once every four bars, embers rise in
 * front, and a hit makes a blob glow as hard as the hit was. It breathes with the section rather than
 * any single hit.
 */
internal class Lava : Layered(
    name = "Lava",
    family = VizFamily.Acid,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 803L, groundKind = GroundKind.Fog, detailKind = DetailKind.Specks, detailStrength = 0.5f, camera = Camera2D(wander = 0.05f, cuts = false, seed = 803)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled),
        VizDrive(VizDriver.BodyHit, VizProperty.Brightness, VizCurve.Scaled),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Pulse, VizProperty.Shape),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.95f, livelyTrail = 0.9f, calmZoom = 1.004f, livelyZoom = 1.014f)
    override val warp: WarpSpec = WarpSpec(WarpFields.LENS, calmAmount = 0.03f, livelyAmount = 0.08f)

    private val blobs = genes.choice("Blobs", 3, start = 1)
    private val paths = genes.choice("Path", 3)
    private val sweepBack = genes.toggle("Sweep reversed", start = false)
    private val emberRate = genes.number("Embers", 0.5f, 2f, 1f)

    private var phase = 0f
    private val glow = FloatArray(MOST)
    private val blobX = FloatArray(MOST) { 0.5f }
    private val blobY = FloatArray(MOST) { 0.5f }
    private var lensPhase = 0f
    private val sweep = Sweep(perBar = 0.25f)
    private var fastSweep = 0f
    private val embers = Sprites(160, 1_803L)
    private var emberCredit = 0f
    private val comets = Comets(size = 0.02f)

    // The lens warp's centre drifts slowly round the screen.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, centreX = 0.5f + 0.2f * sin(lensPhase), centreY = 0.5f + 0.15f * cos(lensPhase * 0.7f))
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        phase += dt * TAU / (gestures.cycleSeconds * 4f) * state.idle
        lensPhase += dt * 0.25f * state.idle
        val count = blobs.count(4, 2)
        // A hit makes one blob glow, as hard as the hit was.
        val landed = maxOf(gestures.snare, gestures.kick)
        if (landed > 0f) {
            val index = (random.next() * count).toInt().coerceIn(0, MOST - 1)
            glow[index] = maxOf(glow[index], 0.3f + 0.7f * landed)
        }
        for (index in 0 until MOST) {
            glow[index] = (glow[index] - dt * 1.2f).coerceAtLeast(0f)
            var x = 0f
            var y = 0f
            for (family in 0 until 3) {
                val share = paths.weight(family)
                if (share <= 0f) continue
                x += share * 0.4f * sin(FX[family] * phase + index * 2.4f)
                y += share * 0.34f * sin(FY[family] * phase + index * 1.7f + 1f)
            }
            blobX[index] = 0.5f + x
            blobY[index] = 0.5f + y
            if (index < 3) kit.place(index, blobX[index], blobY[index])
        }
        if (gestures.drop) fastSweep = gestures.cycleSeconds
        fastSweep -= dt
        sweep.perBar = if (fastSweep > 0f) 1f else 0.25f
        sweep.backwards = sweepBack.on
        sweep.advance(gestures)
        kit.place(3, sweep.position, 0.5f)
        emberCredit += dt * (4f * state.idle + 12f * state.drive) * emberRate.value
        while (emberCredit >= 1f) {
            emberCredit -= 1f
            embers.burst(random.next(), 1.02f, 1, 0.12f, 6f, 0.012f, random.next() * 0.2f, Sprite.GLOW, UP, 0.5f)
        }
        embers.advance(dt, drag = 0f, gravity = -0.01f)
        comets.advance(state, gestures, random)
        kit.follow(4, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val breath = 0.6f + 0.6f * state.lift
        val key = state.frame.keyHue * state.frame.keyConfidence + genes.walk
        for (index in 0 until blobs.drawn(4, 2)) {
            val presence = blobs.presence(index, 4, 2)
            if (presence <= 0.01f) continue
            val along = index.toFloat() / MOST
            val at = Offset(blobX[index] * size.width, blobY[index] * size.height)
            val radius = (sceneRadius * (0.2f + 0.12f * state.lift + 0.1f * along) * breath).coerceAtLeast(1f)
            val alpha = ((0.02f + 0.05f * state.lift + 0.05f * glow[index]) * presence).coerceIn(0f, 1f)
            drawCircle(
                Brush.radialGradient(0f to state.palette.cycled(key + along * 0.3f + state.musicTime * 0.05f, alpha = alpha), 1f to Color.Transparent, center = at, radius = radius),
                radius,
                at,
                blendMode = BlendMode.Plus,
            )
            drawCircle(state.palette.cycled(key + along * 0.3f, value = 1f, alpha = ((0.05f + 0.2f * state.lift) * presence).coerceIn(0f, 1f)), radius * 0.6f, at, style = Stroke((size.minDimension * 0.004f).coerceAtLeast(1f)), blendMode = BlendMode.Plus)
        }
        with(embers) { drawSprites(state.palette, genes.walk, alpha = 0.6f, saturation = 0.7f) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.3f + 0.5f * state.lift) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // A slow band of light crossing the screen.
        val x = sweep.position * size.width
        val band = size.width * 0.1f
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.5f to state.palette.cap.copy(alpha = (0.06f + 0.1f * state.lift).coerceIn(0f, 1f)), 1f to Color.Transparent, startX = x - band, endX = x + band),
            topLeft = Offset(x - band, 0f),
            size = Size(band * 2f, size.height),
            blendMode = BlendMode.Plus,
        )
    }

    override fun onReset() {
        phase = 0f
        glow.fill(0f)
        lensPhase = 0f
        fastSweep = 0f
        embers.clear()
        emberCredit = 0f
        comets.clear()
    }

    private companion object {
        const val MOST = 8
        val FX = floatArrayOf(1f, 2f, 1f)
        val FY = floatArrayOf(1f, 1f, 2f)
    }
}

/**
 * Rays from a centre on an orbit, bent by the middle of the spectrum, each chopped into dashes that fly
 * outward along it on every supported beat, with sparks at the tips. A kick snaps the rays to full, the top of a
 * phrase turns them the other way, and a second burst in the opposite corner can join, with both firing
 * together on a drop.
 */
internal class PrismBurst : Layered(
    name = "Prism Burst",
    family = VizFamily.Acid,
    bucket = VizEnergy.High,
    kit = Kit(seed = 804L, groundKind = GroundKind.Rays, groundDim = 0.7f, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 804)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Bass, VizProperty.Size),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.25f)),
        VizDrive(VizDriver.Pulse, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(1.2f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.92f, livelyTrail = 0.84f, calmZoom = 1.008f, livelyZoom = 1.032f, calmSpin = 0.04f, livelySpin = 0.2f)

    private val bursts = genes.toggle("Second burst", start = false)
    private val rays = genes.choice("Rays", 3, start = 1)
    private val bend = genes.number("Bend", 0f, 0.6f, 0.25f)
    private val dashRate = genes.choice("Dashes", 3, start = 1)

    private val stage = Stage(start = 1.4f)
    private val centre = Orbiter(radiusX = 0.25f, radiusY = 0.2f, lapsPerBar = 0.57f)
    private val turn = MusicClock(beatsPerCycle = 16f)
    private var direction = 1f
    private var spin = 0f
    private val snap = Spring(stiffness = 220f, damping = 0.45f)
    private var both = 0f
    private var lastBeat = -1
    private val dashes = Sprites(500, 1_804L)
    private val sparks = Sprites(200, 2_804L)
    private val comets = Comets()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt * state.idle)
        centre.centreX = stage.x
        centre.centreY = stage.y
        centre.advance(state, gestures)
        kit.place(1, centre.x, centre.y)
        if (gestures.section) direction = -direction
        spin = turn.advance(dt, state.frame, state.paced(0.05f)) * TAU * direction
        snap.kick(gestures.kick * 6f)
        snap.advance(dt)
        if (gestures.drop) both = gestures.cycleSeconds
        both -= dt
        // Dashes fly outward along the rays, on every supported beat or more often.
        val beat = (gestures.cyclePhase * 4f * (dashRate.value + 1)).toInt()
        if (beat != lastBeat && gestures.pulseUsable) {
            lastBeat = beat
            val count = rayCount()
            for (index in 0 until count step 2) {
                val angle = TAU * index / count + spin - PI.toFloat() / 2f
                dashes.burst(centre.x, centre.y, 1, 0.9f, 1.2f, 0.018f, index.toFloat() / count, Sprite.DASH, angle, 0.02f)
            }
        }
        dashes.advance(dt, drag = 0.1f)
        if (gestures.hat > 0f) sparks.sprinkle(gestures.hatSpawn(6), 0.4f, 0.01f, random.next(), Sprite.SPARK)
        sparks.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    private fun rayCount(): Int = when (rays.value) {
        0 -> 36
        1 -> 48
        else -> 64
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        burst(state, Offset(centre.x * size.width, centre.y * size.height), spin, 1f)
        val second = maxOf(bursts.weight(1), if (both > 0f) 1f else 0f)
        if (second > 0.01f) burst(state, Offset((1f - centre.x) * size.width, (1f - centre.y) * size.height), -spin, second)
        with(dashes) { drawSprites(state.palette, genes.walk) }
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    // Rays bent by the middle of the spectrum, the spectrum running round the circle three times.
    private fun DrawScope.burst(state: VizRenderState, at: Offset, turn: Float, share: Float) {
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        val count = rayCount()
        val reach = sceneRadius * (0.78f + 0.18f * state.bassMotion) * (1f + 0.25f * snap.value.coerceIn(0f, 1.2f))
        val curl = bend.value * (0.4f + split.level(1))
        for (ray in 0 until count) {
            val along = ray.toFloat() / count
            val lap = along * 3f - (along * 3f).toInt()
            val energy = bands.foldedAt(lap)
            val angle = TAU * along + turn
            val length = reach * (0.35f + 0.25f * state.lift + 0.55f * energy)
            val start = polar(at, angle, size.minDimension * 0.03f)
            val middle = polar(at, angle + curl * 0.5f, length * 0.5f)
            val end = polar(at, angle + curl, length)
            val colour = state.palette.cycled(along + state.musicTime * 0.2f + genes.walk, value = 1f, alpha = ((0.06f + 0.2f * energy + 0.08f * state.energy) * share).coerceIn(0f, 1f))
            val width = (size.minDimension * 0.009f * (0.4f + energy)).coerceAtLeast(1f)
            drawLine(colour, start, middle, width, StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(colour, middle, end, width, StrokeCap.Round, blendMode = BlendMode.Plus)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        centre.reset()
        turn.reset()
        direction = 1f
        spin = 0f
        snap.reset()
        both = 0f
        lastBeat = -1
        dashes.clear()
        sparks.clear()
        comets.clear()
    }
}

/**
 * Rings born at a wandering centre on every hit and every supported beat, pushed in and out by the
 * spectrum, drifting outward
 * and off the screen, their colour walking ring by ring like the sheen on oil. A second slick can mirror
 * it, glints catch the crests, a snare jumps the sheen, and a drop sends every ring out in a bar.
 */
internal class OilSlick : Layered(
    name = "Oil Slick",
    family = VizFamily.Acid,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 805L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 805)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.BodyHit, VizProperty.Colour, VizCurve.Scaled, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(1.5f)),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Level, VizProperty.Shape),
    )
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.84f, calmZoom = 1.004f, livelyZoom = 1.018f, calmSpin = -0.06f, livelySpin = -0.26f)

    private val rings = genes.choice("Rings", 3, start = 1)
    private val slicks = genes.toggle("Second slick", start = false)
    private val sheenRate = genes.number("Sheen rate", 0.05f, 0.4f, 0.16f)
    private val ripples = genes.choice("Ripples", 3, start = 1)

    private val stage = Stage(start = 2.1f)
    private val centre = Orbiter(radiusX = 0.18f, radiusY = 0.15f, lapsPerBar = 0.4f)
    private val age = FloatArray(MOST) { -1f }
    private val ringTint = FloatArray(MOST)
    private var nextRing = 0
    private var lastBeat = -1
    private var sheen = 0f
    private var ripple = 0f
    private var rush = 0f
    private var glintCredit = 0f
    private val glints = Sprites(200, 1_805L)
    private val comets = Comets()
    private val path = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt * state.idle)
        centre.centreX = stage.x
        centre.centreY = stage.y
        centre.advance(state, gestures)
        kit.place(1, centre.x, centre.y)
        sheen += state.stepSeconds * sheenRate.value
        sheen += 0.2f * gestures.snare
        ripple += dt * 2f * state.tempo
        if (gestures.drop) rush = gestures.cycleSeconds
        rush -= dt
        val beat = (gestures.cyclePhase * 4f).toInt()
        if ((beat != lastBeat && gestures.pulseUsable) || gestures.kick > 0f) {
            lastBeat = beat
            age[nextRing] = 0f
            ringTint[nextRing] = sheen
            nextRing = (nextRing + 1) % MOST
        }
        // Each ring takes its share of the rings' life to leave the screen.
        val life = gestures.beatSeconds * rings.count(4, 2) * if (rush > 0f) 0.25f else 1f
        for (slot in 0 until MOST) {
            if (age[slot] < 0f) continue
            age[slot] += dt / life
            if (age[slot] >= 1f) age[slot] = -1f
        }
        if (gestures.hat > 0f) {
            val a = random.next() * TAU
            glints.burst(centre.x + cos(a) * 0.3f / kit.aspect, centre.y + sin(a) * 0.3f,
                gestures.hatSpawn(2), 0.05f, 0.4f, 0.02f, sheen, Sprite.SPARK)
        }
        glintCredit += dt * (6f * state.idle + 14f * state.drive)
        while (glintCredit >= 1f) {
            glintCredit -= 1f
            val a = random.next() * TAU
            val reach = 0.1f + 0.35f * random.next()
            glints.burst(centre.x + cos(a) * reach / kit.aspect, centre.y + sin(a) * reach, 1, 0.05f, 0.4f, 0.02f, sheen, Sprite.SPARK)
        }
        glints.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        slick(state, Offset(centre.x * size.width, centre.y * size.height), 1f, 1f)
        val second = slicks.weight(1)
        if (second > 0.01f) slick(state, Offset((1f - centre.x) * size.width, (1f - centre.y) * size.height), -1f, second)
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    private fun DrawScope.slick(state: VizRenderState, at: Offset, way: Float, share: Float) {
        val reach = sceneRadius * 1.3f
        val lobes = 2 + 1.5f * ripples.value
        for (slot in 0 until MOST) {
            val out = age[slot]
            if (out < 0f) continue
            path.reset()
            for (point in 0..POINTS) {
                val along = point.toFloat() / POINTS
                val energy = state.frame.bandsRel.foldedAt(along)
                val wobble = 0.7f + energy * 0.6f + (0.08f + 0.12f * state.body) * sin(along * TAU * lobes + ripple * way + slot)
                val p = polar(at, TAU * along, reach * out * wobble)
                if (point == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(
                path,
                state.palette.cycled(ringTint[slot] + out * 0.3f + genes.walk, value = 1f, alpha = ((0.1f + 0.2f * state.lift) * (1f - out * 0.6f) * share).coerceIn(0f, 1f)),
                style = Stroke((size.minDimension * 0.008f).coerceAtLeast(1f)),
                blendMode = BlendMode.Plus,
            )
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(glints) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        centre.reset()
        age.fill(-1f)
        nextRing = 0
        lastBeat = -1
        sheen = 0f
        ripple = 0f
        rush = 0f
        glintCredit = 0f
        glints.clear()
        comets.clear()
    }

    private companion object {
        const val MOST = 16
        const val POINTS = 90
    }
}
