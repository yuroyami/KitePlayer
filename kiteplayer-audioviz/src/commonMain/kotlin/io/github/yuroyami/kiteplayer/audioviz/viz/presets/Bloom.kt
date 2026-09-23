package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A flower on an orbit in front of a meadow of smaller ones, each opening on its own drum. A kick
 * throws the main petals past the edges, petals break off and fly away, and pollen circles whichever
 * flower is most open.
 */
internal class Bloom : Layered(
    name = "Bloom",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 109L, groundKind = GroundKind.Cloud, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.08f, seed = 109)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Size),
        VizDrive(VizDriver.Mid, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.envelope(0.2f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(3f)),
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.Section, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Size, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
    )
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
        if (gestures.drop) dropHold = gestures.cycleSeconds
        dropHold -= dt
        val dropping = dropHold > 0f
        open.hit(if (dropping) 1f else gestures.kick * 0.9f + gestures.snare * 0.3f)
        open.advance(0.15f + 0.35f * state.energy, dt)
        stage.advance(dt * state.idle)
        main.centreX = stage.x
        main.centreY = stage.y
        main.advance(state, gestures)
        kit.place(0, main.x, main.y)
        val way = 1f - 2f * backwards.weight(1)
        spin += dt * (1f * state.tempo + if (dropping) 3f else 0f) * way
        for (index in 0 until MEADOW) {
            val drum = when (index % 3) {
                0 -> gestures.kick
                1 -> gestures.snare
                else -> gestures.hat * 0.7f
            }
            meadowOpen[index].hit(if (dropping) 1f else maxOf(drum, gestures.snare * 0.6f))
            meadowOpen[index].advance(0.1f + 0.25f * state.energy, dt)
            meadow[index].direction = if (index % 2 == 0) 1f else -1f
            meadow[index].advance(state, gestures)
            kit.place(index + 1, meadow[index].x, meadow[index].y, parallax = 0.7f)
        }
        // A petal leaves every bar, and on every snare while shedding is on. It flies over the middle,
        // so it crosses the screen instead of leaving by the nearest edge.
        if (gestures.section || (gestures.snare > 0f && shedding.on)) {
            val count = petals.count(8, 4)
            val petal = (random.next() * count).toInt()
            val over = atan2(0.5f - main.y, 0.5f - main.x) + random.signed() * 0.5f
            shed.outward(main.x, main.y, over, gestures.beatSeconds * 3f, 0.05f, petal / count.toFloat(), Sprite.DIAMOND, spin = 5f * way)
        }
        shed.advance(dt)
        kit.follow(MEADOW + 1, shed)
        val blooming = flowers.count(3, 2)
        if (shedding.on && gestures.hat > 0f) {
            val index = (random.next() * blooming).toInt().coerceIn(0, MEADOW - 1)
            falling.burst(meadow[index].x, meadow[index].y, gestures.hatSpawn(2), 0.1f, 2.5f, 0.012f,
                random.next(), Sprite.DIAMOND, PI.toFloat() / 2f, 1.5f)
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
