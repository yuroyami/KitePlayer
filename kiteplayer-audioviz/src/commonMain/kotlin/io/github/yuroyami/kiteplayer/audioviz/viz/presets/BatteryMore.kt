package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A flower on an orbit in front of a meadow of smaller ones, each opening on its own drum. A kick
 * throws the main petals past the edges, petals break off and fly away, and pollen circles whichever
 * flower is most open.
 */
internal class Bloom : Layered(
    name = "Bloom",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 109L, groundKind = GroundKind.Cloud, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.08f, seed = 109)),
) {
    override val moodSpec: MoodSpec = MoodSpec(
        calmTrail = 0.8f,
        livelyTrail = 0.68f,
        calmZoom = 1.004f,
        livelyZoom = 1.014f,
        calmSpin = 0.08f,
        livelySpin = 0.25f,
    )

    private val petals = genes.choice("Petals", 3, start = 1)
    private val flowers = genes.choice("Flowers", 3, start = 1)
    private val backwards = genes.toggle("Turn backwards", start = false)
    private val shedding = genes.toggle("Shedding", start = true)

    private val stage = Stage(start = 0.9f)
    private val main = Orbiter(radiusX = 0.32f, radiusY = 0.26f, lapsPerBar = 0.8f)
    private val meadow = Array(MEADOW) {
        Orbiter(
            radiusX = 0.34f + 0.04f * (it % 3),
            radiusY = 0.3f + 0.03f * (it % 2),
            lapsPerBar = 0.1f + 0.03f * (it % 4),
            phase = it / MEADOW.toFloat(),
        )
    }
    private val meadowOpen = Array(MEADOW) { Envelope(attackPerSecond = 20f, releasePerSecond = 1.6f) }
    private val open = Envelope(attackPerSecond = 26f, releasePerSecond = 2.2f)
    private var spin = 0f
    private var dropHold = 0f
    private val shed = Travellers(24)
    private val pollen = Swarm(48, 1_109L)
    private val falling = Sprites(200, 1_109L)
    private val petalMesh = TriangleMesh(maxVertices = (1 + MEADOW) * 2 * 16 * 4 + 32)
    private val pollenMesh = TriangleMesh(maxVertices = 48 * 8)
    private val shedMesh = TriangleMesh(maxVertices = 24 * 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        if (gestures.drop) dropHold = gestures.barSeconds
        dropHold -= dt
        val dropping = dropHold > 0f
        open.hit(if (dropping) 1f else gestures.kickHit * 0.9f + gestures.snareHit * 0.3f)
        open.advance(0.15f + 0.35f * state.energy, dt)
        stage.advance(dt)
        main.centreX = stage.x
        main.centreY = stage.y
        main.advance(state, gestures)
        kit.place(0, main.x, main.y)
        val way = 1f - 2f * backwards.weight(1)
        spin += dt * (1f * state.tempo + if (dropping) 3f else 0f) * way
        // Soft onsets make the petals shiver, so a calm passage still breathes.
        if (gestures.kickHit <= 0f) open.hit(0.2f * state.frame.snare)
        for (index in 0 until MEADOW) {
            val drum = when (index % 3) {
                0 -> gestures.kickHit
                1 -> gestures.snareHit
                else -> gestures.hatHit * 0.7f
            }
            meadowOpen[index].hit(if (dropping) 1f else maxOf(drum, gestures.snareHit * 0.6f))
            meadowOpen[index].advance(0.1f + 0.25f * state.energy, dt)
            meadow[index].direction = if (index % 2 == 0) 1f else -1f
            meadow[index].advance(state, gestures)
            kit.place(index + 1, meadow[index].x, meadow[index].y, parallax = 0.7f)
        }
        // A petal leaves every bar, and on every snare while shedding is on. It flies over the middle,
        // so it crosses the screen instead of leaving by the nearest edge.
        if (gestures.bar || (gestures.snareHit > 0f && shedding.on)) {
            val count = petals.count(8, 4)
            val petal = (random.next() * count).toInt()
            val over = atan2(0.5f - main.y, 0.5f - main.x) + random.signed() * 0.5f
            shed.outward(main.x, main.y, over, gestures.beatSeconds * 3f, 0.05f, petal / count.toFloat(), Sprite.DIAMOND, spin = 5f * way)
        }
        shed.advance(dt)
        kit.follow(MEADOW + 1, shed)
        val blooming = flowers.count(3, 2)
        if (shedding.on && gestures.hatHit > 0f) {
            val index = (random.next() * blooming).toInt().coerceIn(0, MEADOW - 1)
            falling.burst(meadow[index].x, meadow[index].y, 2, 0.1f, 2.5f, 0.012f, random.next(), Sprite.DIAMOND, PI.toFloat() / 2f, 1.5f)
        }
        falling.advance(dt, drag = 0.6f, gravity = 0.15f)
        var targetX = main.x
        var targetY = main.y
        var widest = open.value
        for (index in 0 until blooming) {
            if (meadowOpen[index].value > widest) {
                widest = meadowOpen[index].value
                targetX = meadow[index].x
                targetY = meadow[index].y
            }
        }
        pollen.targetX = targetX
        pollen.targetY = targetY
        pollen.advance(dt, speed = 0.25f + 0.6f * state.drive)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        petalMesh.clear()
        val walk = genes.walk
        for (index in 0 until flowers.drawn(3, 2)) {
            val presence = flowers.presence(index, 3, 2)
            if (presence <= 0.01f) continue
            val opened = meadowOpen[index].value.coerceIn(0f, 1f)
            val reach = sceneRadius * (0.14f + 0.08f * state.lift + 0.16f * opened)
            addFlower(state, meadow[index].x * size.width, meadow[index].y * size.height, reach, opened, -spin * 0.7f + index, index % 3, presence * 0.7f, walk + 0.13f * index)
        }
        val opened = open.value.coerceIn(0f, 1f)
        val reach = sceneRadius * (0.3f + 0.3f * state.lift + 0.55f * opened)
        addFlower(state, main.x * size.width, main.y * size.height, reach, opened, spin, -1, 1f, walk)
        drawMesh(petalMesh)
        drawCircle(
            state.palette.cap.copy(alpha = 0.6f),
            size.minDimension * 0.03f * (0.6f + opened),
            Offset(main.x * size.width, main.y * size.height),
        )
    }

    private fun addFlower(state: VizRenderState, x: Float, y: Float, reach: Float, opened: Float, turn: Float, part: Int, presence: Float, tint: Float) {
        for (option in 0 until 3) {
            val share = petals.weight(option)
            if (share <= 0.01f) continue
            val count = 8 + 4 * option
            for (index in 0 until count) {
                val along = index.toFloat() / count
                val energy = if (part < 0) state.frame.bandsRel.sampleAt(along) else split.sample(part, along)
                val angle = TAU * along + turn
                val length = reach * (0.2f + 0.35f * state.lift + 0.35f * energy + 0.3f * opened)
                val width = TAU / count * (0.2f + 0.8f * opened + 0.2f * state.body)
                val alpha = (0.3f + 0.6f * state.lift) * presence * share
                val inner = state.palette.argb(tint + along, value = 0.3f, alpha = alpha * 0.6f)
                val outer = state.palette.argb(tint + along + 0.08f, value = 0.5f + 0.5f * energy, alpha = alpha)
                val c = petalMesh.vertex(x, y, inner)
                val l = petalMesh.vertex(x + cos(angle - width) * length * 0.72f, y + sin(angle - width) * length * 0.72f, outer)
                val t = petalMesh.vertex(x + cos(angle) * length, y + sin(angle) * length, outer)
                val r = petalMesh.vertex(x + cos(angle + width) * length * 0.72f, y + sin(angle + width) * length * 0.72f, outer)
                petalMesh.triangle(c, l, t)
                petalMesh.triangle(c, t, r)
            }
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        pollenMesh.clear()
        val unit = size.minDimension * 0.005f
        for (index in 0 until pollen.count) {
            val colour = state.palette.argb(0.1f + index * 0.01f + genes.walk, value = 1f, alpha = 0.6f)
            pollenMesh.glow(pollen.x[index] * size.width, pollen.y[index] * size.height, unit * (1f + index % 3), colour)
        }
        drawMesh(pollenMesh, BlendMode.Plus)
        drawTravellers(shed, shedMesh, state.palette, genes.walk)
        with(falling) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        main.reset()
        meadow.forEach { it.reset() }
        meadowOpen.forEach { it.reset() }
        open.reset()
        spin = 0f
        dropHold = 0f
        shed.clear()
        pollen.scatter()
        falling.clear()
    }

    private companion object {
        const val MEADOW = 7
    }
}

/**
 * A dial that wanders past the corners, swept one way once a bar and the other way once a beat. Loud
 * parts of the spectrum leave marks the echo carries outward and off the screen, and contacts crossing
 * the screen light up when a sweep passes them.
 */
internal class Radar : Layered(
    name = "Radar",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 110L, groundKind = GroundKind.Grid, detailKind = DetailKind.Scan, camera = Camera2D(wander = 0.06f, seed = 110)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.93f, livelyTrail = 0.9f, calmZoom = 1.004f, livelyZoom = 1.012f)

    private val sweeps = genes.choice("Sweeps", 2, start = 1)
    private val contacts = genes.choice("Contacts", 4, start = 2)
    private val dial = genes.choice("Dial", 3)
    private val persistence = genes.number("Mark persistence", -0.05f, 0.04f, 0f)

    private val stage = Stage(start = 1.7f)
    private val centre = Orbiter(radiusX = 0.2f, radiusY = 0.16f, lapsPerBar = 0.2f)
    private val slow = MusicClock(beatsPerCycle = 4f)
    private val fast = MusicClock(beatsPerCycle = 1f)
    private var slowAngle = 0f
    private var fastAngle = 0f
    private var extra = 0f
    private var extraAngle = 0f
    private var dropLeft = 0f
    private var dropTurn = 0f
    private val flare = Envelope(attackPerSecond = 60f, releasePerSecond = 4f)
    private var drift = 0f
    private val contactX = FloatArray(CONTACTS) { 0.5f }
    private val contactY = FloatArray(CONTACTS) { 0.5f }
    private val lit = FloatArray(CONTACTS)
    private val sparks = Sprites(240, 1_110L)
    private val wedge = TriangleMesh(maxVertices = 3 * (WEDGE + 2) + 16)
    private val hexagon = Path()

    override fun trailAt(mood: Float): Float = (super.trailAt(mood) + persistence.value).coerceIn(0f, 0.97f)

    // The echo grows from the dial's centre, so marks drift outward from wherever the dial has got to.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val dial = anchors.getOrNull(DIAL)
        return EchoFrame(zoomX = base.zoomX, spin = base.spin, centreX = dial?.x ?: 0.5f, centreY = dial?.y ?: 0.5f)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        stage.advance(dt)
        centre.centreX = stage.x
        centre.centreY = stage.y
        centre.advance(state, gestures)
        kit.place(DIAL, centre.x, centre.y)
        if (gestures.drop) dropLeft = 1f
        if (dropLeft > 0f) {
            val step = minOf(dropLeft, dt / gestures.barSeconds)
            dropLeft -= step
            dropTurn += step * TAU
        }
        slowAngle = slow.advance(dt, frame.bpm, frame.beatConfidence, frame.phrasePhase, state.paced(0.25f)) * TAU + dropTurn
        fastAngle = -fast.advance(dt, frame.bpm, frame.beatConfidence, frame.phrasePhase, state.paced(0.7f)) * TAU - dropTurn
        kit.place(0, centre.x + sin(slowAngle) * 0.45f / kit.aspect, centre.y - cos(slowAngle) * 0.45f)
        if (gestures.snareHit > 0f) {
            extra = 1f
            extraAngle = random.next() * TAU
        }
        extra = (extra - dt / (gestures.beatSeconds * 0.75f)).coerceAtLeast(0f)
        flare.hit(gestures.kickHit)
        flare.advance(0f, dt)
        drift += dt * TAU / (gestures.barSeconds * 3f) * (0.6f + 0.6f * state.drive)
        val count = contacts.count(3)
        val second = sweeps.value == 1
        for (index in 0 until CONTACTS) {
            contactX[index] = 0.5f + 0.46f * sin(drift * FX[index] + index * 1.9f)
            contactY[index] = 0.5f + 0.42f * sin(drift * FY[index] + index * 2.3f)
            kit.place(index + 1, contactX[index], contactY[index])
            lit[index] = (lit[index] - dt * 1.5f).coerceAtLeast(0f)
            if (index >= count) continue
            val angle = atan2((contactX[index] - centre.x) * kit.aspect, centre.y - contactY[index])
            val passed = near(angle, slowAngle, 0.22f) || (second && near(angle, fastAngle, 0.4f))
            if (passed && lit[index] < 0.5f) {
                lit[index] = 1f
                sparks.burst(contactX[index], contactY[index], 8, 0.25f, 0.7f, 0.01f, index * 0.17f, Sprite.SPARK)
            }
        }
        if (gestures.hatHit > 0f) {
            val tipX = centre.x + sin(slowAngle) * 0.3f / kit.aspect
            val tipY = centre.y - cos(slowAngle) * 0.3f
            sparks.burst(tipX, tipY, 4, 0.3f, 0.5f, 0.008f, 0.5f, Sprite.SPARK)
        }
        sparks.advance(dt, drag = 1.2f)
    }

    // Angles follow polar(): clockwise from straight up, the same as the sweep lines.
    private fun near(a: Float, b: Float, window: Float): Boolean {
        var difference = (a - b) % TAU
        if (difference > TAU / 2f) difference -= TAU
        if (difference < -TAU / 2f) difference += TAU
        return abs(difference) < window
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val at = Offset(centre.x * size.width, centre.y * size.height)
        val reach = sceneRadius * 1.3f
        val walk = genes.walk
        val thin = (size.minDimension * (0.002f + 0.003f * state.air)).coerceAtLeast(1f)
        val ringColour = state.palette.mid.copy(alpha = (0.06f + 0.12f * state.energy + 0.25f * flare.value).coerceIn(0f, 1f))
        val round = dial.weight(0)
        val corners = dial.weight(1)
        val lines = dial.weight(2)
        for (ring in 1..4) {
            val radius = reach * ring / 4f
            if (round > 0.01f) drawCircle(ringColour.copy(alpha = ringColour.alpha * round), radius, at, style = Stroke(thin))
            if (corners > 0.01f) {
                hexagon.reset()
                for (corner in 0..6) {
                    val point = polar(at, dropTurn + TAU * corner / 6f, radius)
                    if (corner == 0) hexagon.moveTo(point.x, point.y) else hexagon.lineTo(point.x, point.y)
                }
                drawPath(hexagon, ringColour.copy(alpha = ringColour.alpha * corners), style = Stroke(thin))
            }
        }
        if (lines > 0.01f) {
            val step = reach / 4f
            val colour = ringColour.copy(alpha = ringColour.alpha * lines)
            for (k in -8..8) {
                val x = at.x + k * step
                if (x in 0f..size.width) drawLine(colour, Offset(x, 0f), Offset(x, size.height), thin)
                val y = at.y + k * step
                if (y in 0f..size.height) drawLine(colour, Offset(0f, y), Offset(size.width, y), thin)
            }
        }
        wedge.clear()
        addSweep(state, at, reach, slowAngle, -1f, 1f, walk)
        val second = sweeps.weight(1)
        if (second > 0.01f) addSweep(state, at, reach, fastAngle, 1f, second * 0.8f, walk + 0.5f)
        if (extra > 0f) addSweep(state, at, reach * 0.8f, extraAngle + (1f - extra) * PI.toFloat(), -1f, extra, walk + 0.25f)
        drawMesh(wedge, BlendMode.Plus)
        val line = state.palette.cap.copy(alpha = (0.35f + 0.5f * state.energy + 0.3f * flare.value).coerceIn(0f, 1f))
        val width = thin * (1.5f + 3f * flare.value)
        drawLine(line, at, polar(at, slowAngle, reach), width)
        if (second > 0.01f) drawLine(line.copy(alpha = line.alpha * second), at, polar(at, fastAngle, reach), width)
        val loud = state.percentile(0.6f)
        val lift = state.lift
        val dot = size.minDimension * 0.007f
        for (sweep in 0 until 2) {
            val strength = if (sweep == 0) 1f else second
            if (strength <= 0.01f) continue
            val angle = if (sweep == 0) slowAngle else fastAngle
            for (mark in 1..MARKS) {
                val along = mark.toFloat() / MARKS
                val energy = state.frame.bandsRel.sampleAt(along)
                if (energy <= loud) continue
                drawCircle(
                    state.palette.cycled(along + walk, value = 0.4f + 0.6f * energy, alpha = (energy * lift * strength).coerceIn(0f, 1f)),
                    dot * (0.5f + 2.5f * energy),
                    polar(at, angle, reach * 0.85f * along),
                )
            }
        }
        for (index in 0 until contacts.drawn(3)) {
            val presence = contacts.presence(index, 3)
            if (presence <= 0.01f) continue
            val glow = 0.3f + 0.7f * lit[index]
            drawCircle(
                state.palette.cycled(index * 0.17f + walk, value = 1f, alpha = (glow * presence).coerceIn(0f, 1f)),
                size.minDimension * (0.007f + 0.012f * lit[index]),
                Offset(contactX[index] * size.width, contactY[index] * size.height),
            )
        }
    }

    // A fan of thin triangles behind the sweep line, fading with distance from it.
    private fun addSweep(state: VizRenderState, at: Offset, reach: Float, angle: Float, behind: Float, strength: Float, tint: Float) {
        val middle = wedge.vertex(at.x, at.y, state.palette.argb(tint, value = 1f, alpha = 0f))
        var previous = -1
        for (index in 0..WEDGE) {
            val a = angle + behind * 0.07f * index
            val alpha = strength * 0.4f * (1f - index.toFloat() / WEDGE)
            val point = wedge.vertex(at.x + sin(a) * reach, at.y - cos(a) * reach, state.palette.argb(tint + index * 0.01f, value = 0.8f, alpha = alpha))
            if (previous >= 0) wedge.triangle(middle, previous, point)
            previous = point
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        centre.reset()
        slow.reset()
        fast.reset()
        slowAngle = 0f
        fastAngle = 0f
        extra = 0f
        dropLeft = 0f
        dropTurn = 0f
        flare.reset()
        drift = 0f
        lit.fill(0f)
        sparks.clear()
    }

    private companion object {
        const val CONTACTS = 6
        const val DIAL = CONTACTS + 1
        const val MARKS = 16
        const val WEDGE = 10
        val FX = floatArrayOf(1f, 1.3f, 0.7f, 1.7f, 0.9f, 1.45f)
        val FY = floatArrayOf(1.4f, 0.8f, 1.9f, 1.1f, 1.6f, 0.75f)
    }
}

/**
 * Stacks of discs, one for each part of the spectrum, riding orbits and pushing each other apart. A
 * kick punches the cores, a snare makes the stacks swap places, ripples leave each stack on its own
 * drum, and a drop merges every stack into one for a bar.
 */
internal class Pulse : Layered(
    name = "Pulse",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 111L, groundKind = GroundKind.Plasma, groundDim = 0.7f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.08f, seed = 111)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.55f, livelyTrail = 0.42f, calmDriftX = 0.02f, livelyDriftX = 0.08f)

    private val stacks = genes.choice("Stacks", 3, start = 1)
    private val discs = genes.choice("Discs", 3, start = 1)
    private val wobble = genes.number("Wobble", 0f, 0.25f, 0.14f)
    private val arrangement = genes.choice("Arrangement", 3)

    private val stage = Stage(start = 2.4f)
    private val orbits = Array(STACKS) {
        Orbiter(radiusX = 0.3f, radiusY = 0.26f, lapsPerBar = 0.35f - 0.05f * it, phase = it / STACKS.toFloat())
    }
    private val stackX = FloatArray(STACKS) { 0.5f }
    private val stackY = FloatArray(STACKS) { 0.5f }
    private var shift = 0
    private var wobblePhase = 0f
    private var mergeHold = 0f
    private val merge = Envelope(attackPerSecond = 4f, releasePerSecond = 1.5f)
    private val core = Spring(stiffness = 260f, damping = 0.42f)
    private val sweep = Sweep()
    private val sparks = Sprites(240, 1_111L)
    private val rippleX = FloatArray(RIPPLES)
    private val rippleY = FloatArray(RIPPLES)
    private val rippleAge = FloatArray(RIPPLES)
    private val rippleTint = FloatArray(RIPPLES)
    private val rippleStrength = FloatArray(RIPPLES)
    private var nextRipple = 0

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        val count = stacks.count(2)
        stage.advance(dt)
        if (gestures.snareHit > 0f) shift = (shift + 1) % STACKS
        if (gestures.drop) mergeHold = gestures.barSeconds
        mergeHold -= dt
        merge.advance(if (mergeHold > 0f) 1f else 0f, dt)
        core.kick(gestures.kickHit * 8f)
        core.advance(dt)
        wobblePhase += dt * 2.4f * state.tempo
        for (index in 0 until STACKS) {
            orbits[index].direction = if (index % 2 == 0) 1f else -1f
            orbits[index].centreX = stage.x
            orbits[index].centreY = stage.y
            orbits[index].advance(state, gestures)
        }
        val ease = (dt * 3f).coerceAtMost(1f)
        val lead = orbits[0]
        for (index in 0 until STACKS) {
            val slot = (index + shift) % STACKS
            val around = lead.angle + slot * TAU / STACKS
            var x = 0f
            var y = 0f
            for (option in 0 until 3) {
                val share = arrangement.weight(option)
                if (share <= 0f) continue
                x += share * when (option) {
                    0 -> orbits[slot].x
                    1 -> 0.16f + 0.68f * slot / (STACKS - 1f)
                    else -> 0.5f + 0.36f * cos(around)
                }
                y += share * when (option) {
                    0 -> orbits[slot].y
                    1 -> 0.5f + 0.26f * sin(orbits[slot].angle)
                    else -> 0.5f + 0.32f * sin(around)
                }
            }
            x += (lead.x - x) * merge.value
            y += (lead.y - y) * merge.value
            stackX[index] += (x - stackX[index]) * ease
            stackY[index] += (y - stackY[index]) * ease
        }
        // Stacks that come too close push each other apart a little, so they drift instead of overlapping.
        val gap = 0.22f * (1f - merge.value)
        for (a in 0 until count) {
            for (b in a + 1 until count) {
                val dx = stackX[b] - stackX[a]
                val dy = stackY[b] - stackY[a]
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                if (distance >= gap) continue
                val push = (gap - distance) * 0.5f * (dt * 6f).coerceAtMost(1f) / distance
                stackX[a] -= dx * push
                stackY[a] -= dy * push
                stackX[b] += dx * push
                stackY[b] += dy * push
            }
        }
        for (index in 0 until STACKS) kit.place(index, stackX[index], stackY[index])
        for (index in 0 until count) {
            val drum = when (index % 3) {
                0 -> gestures.kickHit
                1 -> gestures.snareHit
                else -> gestures.hatHit
            }
            if (drum > 0f) ripple(stackX[index], stackY[index], index * 0.25f, 1f)
            if (gestures.kickHit > 0f) {
                sparks.burst(stackX[index], stackY[index], 5 + (6 * gestures.kickHit).toInt(), 0.5f, 0.6f, 0.01f, index * 0.25f, Sprite.SPARK)
            }
        }
        // A soft onset under calm music leaves a faint ring on the lead stack, like rain on a pond.
        if (gestures.kickHit <= 0f && frame.kick > 0f) ripple(stackX[0], stackY[0], 0.1f, 0.3f + 0.4f * frame.kick)
        for (slot in 0 until RIPPLES) {
            if (rippleAge[slot] <= 0f) continue
            rippleAge[slot] += dt / (gestures.beatSeconds * 2f)
            if (rippleAge[slot] >= 1f) rippleAge[slot] = 0f
        }
        sweep.advance(gestures)
        kit.place(STACKS, sweep.position, 0.5f)
        sparks.advance(dt, drag = 1f)
    }

    private fun ripple(x: Float, y: Float, tint: Float, strength: Float) {
        rippleX[nextRipple] = x
        rippleY[nextRipple] = y
        rippleAge[nextRipple] = 0.001f
        rippleTint[nextRipple] = tint
        rippleStrength[nextRipple] = strength
        nextRipple = (nextRipple + 1) % RIPPLES
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val walk = genes.walk
        for (index in 0 until stacks.drawn(2)) {
            val presence = stacks.presence(index, 2)
            if (presence <= 0.01f) continue
            val grow = if (index == 0) 1f + 0.5f * merge.value else 1f - 0.5f * merge.value
            val reach = sceneRadius * SIZES[index] * (0.45f + 0.25f * state.lift) * grow
            drawStack(state, Offset(stackX[index] * size.width, stackY[index] * size.height), reach, index % 3, presence, walk + index * 0.23f)
        }
    }

    // Drawn outermost first, so each smaller disc lands on top of the one behind it.
    private fun DrawScope.drawStack(state: VizRenderState, at: Offset, reach: Float, part: Int, presence: Float, tint: Float) {
        val punch = 0.1f * core.value.coerceIn(0f, 1f)
        for (option in 0 until 3) {
            val share = discs.weight(option)
            if (share <= 0.01f) continue
            val count = 5 + 2 * option
            for (disc in count - 1 downTo 0) {
                val along = disc.toFloat() / count
                val energy = split.sample(part, along)
                val sway = 1f + wobble.value * sin(wobblePhase * (1f + along * 2f) + disc)
                val radius = reach * ((disc + 1f) / count) * (0.25f + 0.35f * state.lift + 0.35f * energy + punch) * sway
                drawCircle(
                    state.palette.cycled(tint + along * 0.6f, value = 0.3f + 0.7f * energy, alpha = (0.3f + 0.55f * state.lift) * presence * share),
                    radius.coerceAtLeast(1f),
                    at,
                )
            }
        }
        drawCircle(
            state.palette.cap.copy(alpha = ((0.2f + 0.6f * core.value) * presence).coerceIn(0f, 1f)),
            size.minDimension * 0.02f * (0.6f + core.value.coerceIn(0f, 1.6f)),
            at,
        )
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val thin = (size.minDimension * 0.004f).coerceAtLeast(1f)
        for (slot in 0 until RIPPLES) {
            val age = rippleAge[slot]
            if (age <= 0f) continue
            drawCircle(
                state.palette.cycled(rippleTint[slot] + genes.walk, value = 1f, alpha = (0.6f * (1f - age) * rippleStrength[slot]).coerceIn(0f, 1f)),
                sceneRadius * 0.9f * age,
                Offset(rippleX[slot] * size.width, rippleY[slot] * size.height),
                style = Stroke(thin * (1f + 2f * (1f - age))),
            )
        }
        val x = sweep.position * size.width
        val band = size.width * 0.07f
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to state.palette.cap.copy(alpha = (0.16f + 0.2f * state.energy).coerceIn(0f, 1f)),
                1f to Color.Transparent,
                startX = x - band,
                endX = x + band,
            ),
            topLeft = Offset(x - band, 0f),
            size = Size(band * 2f, size.height),
            blendMode = BlendMode.Plus,
        )
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        orbits.forEach { it.reset() }
        stackX.fill(0.5f)
        stackY.fill(0.5f)
        shift = 0
        wobblePhase = 0f
        mergeHold = 0f
        merge.reset()
        core.reset()
        sparks.clear()
        rippleAge.fill(0f)
        nextRipple = 0
    }

    private companion object {
        const val STACKS = 4
        const val RIPPLES = 16
        val SIZES = floatArrayOf(1f, 0.72f, 0.58f, 0.48f)
    }
}
