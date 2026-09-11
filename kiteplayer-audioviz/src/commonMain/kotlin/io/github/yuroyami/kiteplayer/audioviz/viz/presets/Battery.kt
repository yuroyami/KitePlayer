package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoBlend
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoCopy
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spike stars, one for each part of the spectrum, orbiting each other across the screen.
 *
 * The bass star is the biggest. A snare sends a small star across, a kick flashes the hubs and
 * throws sparks, and a drop pulls every star into one for a bar before they split again.
 */
internal class Spikes : Layered(
    name = "Spikes",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 101L, groundKind = GroundKind.Rings, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.1f, seed = 101)),
) {
    // Long echoes that zoom and turn, so every blade leaves a spiral that fills the screen.
    override val moodSpec: MoodSpec = MoodSpec(
        calmTrail = 0.9f,
        livelyTrail = 0.87f,
        calmZoom = 1.008f,
        livelyZoom = 1.026f,
        calmSpin = 0.35f,
        livelySpin = 0.9f,
    )
    override val echoBlend: EchoBlend get() = EchoBlend.Add

    private val stars = genes.choice("Stars", 4, start = 2)
    private val shape = genes.number("Orbit shape", 0.35f, 1f, 0.7f)
    private val curve = genes.number("Blade curve", -0.45f, 0.45f, 0.13f)
    private val blades = genes.choice("Blades", 3, start = 2)
    private val floor = genes.choice("Ground", 2)
    private val mirrored = genes.toggle("Mirrored echo", start = false)
    private val follows = genes.toggle("Echo follows the star", start = false)

    private val stage = Stage(start = 0.4f)
    private val orbits = Array(STARS) { Orbiter(lapsPerBar = LAPS[it], phase = it / STARS.toFloat()) }
    private val turns = FloatArray(STARS)
    private val starX = FloatArray(STARS) { 0.5f }
    private val starY = FloatArray(STARS) { 0.5f }
    private val hub = Spring(stiffness = 210f, damping = 0.45f)
    private val merge = Envelope(attackPerSecond = 5f, releasePerSecond = 1.2f)
    private var mergeHold = 0f
    private val travellers = Travellers(10)
    private val sparks = Sprites(320, 1_101L)
    private val shocks = Shocks()
    private val bladeMesh = TriangleMesh(maxVertices = STARS * 48 * 3 + 16)
    private val topMesh = TriangleMesh(maxVertices = 400)

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val mirror = mirrored.weight(1)
        val follow = follows.weight(1)
        val main = anchors.firstOrNull()
        return EchoFrame(
            zoomX = base.zoomX,
            spin = base.spin,
            centreX = 0.5f + ((main?.x ?: 0.5f) - 0.5f) * follow,
            centreY = 0.5f + ((main?.y ?: 0.5f) - 0.5f) * follow,
            copy = if (mirror > 0.05f) EchoCopy(angle = PI.toFloat(), share = 0.3f * mirror) else null,
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        ground?.kind = if (floor.value == 0) GroundKind.Rings else GroundKind.Stars
        if (gestures.drop) mergeHold = gestures.barSeconds
        mergeHold -= dt
        merge.advance(if (mergeHold > 0f) 1f else 0f, dt)
        hub.kick(gestures.kickHit * 9f)
        hub.advance(dt)
        val apart = 1f - merge.value
        for (index in 0 until STARS) {
            val orbit = orbits[index]
            orbit.centreX = stage.x
            orbit.centreY = stage.y
            orbit.radiusX = (0.3f + 0.06f * index) * apart
            orbit.radiusY = (orbit.radiusX * shape.value * 1.2f).coerceAtMost(0.4f)
            orbit.direction = if (index % 2 == 0) 1f else -1f
            orbit.advance(state, gestures)
            starX[index] = orbit.x
            starY[index] = orbit.y
            turns[index] += dt * (1.4f + 0.5f * index) * state.tempo * if (index % 2 == 0) 1f else -1f
            kit.place(index, starX[index], starY[index])
        }
        if (gestures.snareHit > 0f) {
            travellers.across(random, gestures.beatSeconds * 2.5f, PathShape.Arc, 0.14f, 0.045f, random.next(), 4f, Sprite.SPARK)
        }
        if (gestures.bar) {
            travellers.across(random, gestures.barSeconds * 0.7f, PathShape.Wave, 0.05f, 0.06f, random.next(), -2f, Sprite.HEX)
        }
        travellers.advance(dt)
        kit.follow(STARS, travellers)
        val kick = gestures.kickHit
        if (kick > 0f) {
            shocks.fire(starX[0], starY[0], random.next(), kick)
            for (index in 0 until stars.drawn(1)) {
                sparks.burst(starX[index], starY[index], (6 + 14 * kick).toInt(), 0.45f + 0.4f * state.drive, 0.9f, 0.014f, index * 0.25f, Sprite.SPARK)
            }
        }
        val hat = gestures.hatHit
        if (hat > 0f) sparks.sprinkle((3 + 6 * hat).toInt(), 0.5f, 0.008f, 0.5f, Sprite.GLOW)
        sparks.advance(dt, drag = 1.1f)
        shocks.advance(dt, gestures.beatSeconds * 2f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        bladeMesh.clear()
        val count = 30 + 12 * blades.value
        val walk = genes.walk
        for (index in 0 until STARS) {
            val presence = stars.presence(index, 1)
            if (presence <= 0.01f) continue
            val grow = if (index == 0) 1.1f else 0.35f
            val reach = size.minDimension * 0.42f * SIZES[index] * (1f + merge.value * grow)
            addStar(state, starX[index] * size.width, starY[index] * size.height, reach, index, count, presence, walk + index * 0.22f)
        }
        // Added as light: where echoes of blades cross they climb towards white instead of covering each other.
        drawMesh(bladeMesh, BlendMode.Plus)
        with(shocks) { drawShocks(state.palette, genes.walk, sceneRadius * 1.6f, size.minDimension * 0.02f, state.lift) }
        for (index in 0 until STARS) {
            val presence = stars.presence(index, 1)
            if (presence <= 0.01f) continue
            val radius = size.minDimension * SIZES[index] * (0.03f + 0.03f * hub.value.coerceIn(0f, 1.4f))
            drawCircle(
                state.palette.cap.copy(alpha = ((0.2f + 0.7f * hub.value) * presence).coerceIn(0f, 1f)),
                radius.coerceAtLeast(1f),
                Offset(starX[index] * size.width, starY[index] * size.height),
            )
        }
    }

    private fun addStar(state: VizRenderState, x: Float, y: Float, reach: Float, index: Int, count: Int, presence: Float, tint: Float) {
        val inner = reach * 0.14f
        val part = index % 3
        val bend = curve.value
        val half = TAU / count * 0.42f
        val turn = turns[index]
        for (blade in 0 until count) {
            val along = blade.toFloat() / count
            val value = if (index == 3) state.frame.bandsRel.foldedAt(along) else split.folded(part, along)
            val angle = turn + TAU * along
            val tip = inner + reach * (0.25f * state.presence + 0.75f * value)
            // Light is stored up by the long echo, so each frame lays down only a little of it.
            val strength = (0.1f + 0.6f * state.lift + 0.15f * value) * presence
            val base = state.palette.argb(tint + along * 0.5f, value = 0.35f + 0.65f * value, alpha = strength * 0.7f)
            val point = state.palette.argb(tint + along * 0.5f + 0.1f, value = 1f, alpha = strength)
            val a = bladeMesh.vertex(x + sin(angle - half) * inner, y - cos(angle - half) * inner, base)
            val b = bladeMesh.vertex(x + sin(angle + bend) * tip, y - cos(angle + bend) * tip, point)
            val c = bladeMesh.vertex(x + sin(angle + half) * inner, y - cos(angle + half) * inner, base)
            bladeMesh.triangle(a, b, c)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
        drawTravellers(travellers, topMesh, state.palette, genes.walk)
    }

    override fun onReset() {
        stage.reset()
        orbits.forEach { it.reset() }
        turns.fill(0f)
        starX.fill(0.5f)
        starY.fill(0.5f)
        hub.reset()
        merge.reset()
        mergeHold = 0f
        travellers.clear()
        sparks.clear()
        shocks.clear()
    }

    private companion object {
        const val STARS = 4
        val SIZES = floatArrayOf(1.2f, 0.8f, 0.6f, 0.48f)
        val LAPS = floatArrayOf(1.03f, 0.8f, 0.92f, 0.57f)
    }
}

/**
 * Bars of the spectrum laid across the screen, each notched by its own part of the music, sliding
 * past each other and scrolling their colours at their own speeds. A light runs along the main bar
 * once a bar, a kick drops blocks off it and a snare flips one bar over for a beat.
 */
internal class RainbowBar : Layered(
    name = "Rainbow Bar",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 102L, groundKind = GroundKind.Plasma, groundDim = 0.8f, detailKind = DetailKind.Hatch, camera = Camera2D(wander = 0.08f, seed = 102)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.66f, livelyTrail = 0.55f, calmDriftY = 0.02f, livelyDriftY = 0.1f)

    private val bars = genes.choice("Bars", 5, start = 2)
    private val tilt = genes.number("Tilt", -0.35f, 0.35f, 0f)
    private val notches = genes.choice("Notches", 2)
    private val backwards = genes.toggle("Scroll backwards", start = false)

    // The group rides up and down the screen, and the stage's sideways swing tilts it.
    private val stage = Stage(reachX = 0.3f, reachY = 0.36f, start = 0.2f)
    private val slide = FloatArray(MOST) { it * 1.3f }
    private val scroll = FloatArray(MOST)
    private val flipped = FloatArray(MOST)
    private val barY = FloatArray(MOST) { 0.5f }
    private val sweep = Sweep()
    private val thick = Spring(stiffness = 180f, damping = 0.45f)
    private val blocks = Sprites(260, 1_102L)
    private val mesh = TriangleMesh(maxVertices = MOST * SLOTS * 4 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        thick.kick(gestures.kickHit * 7f)
        thick.advance(dt)
        sweep.advance(gestures)
        val direction = if (backwards.on) -1f else 1f
        val swing = 0.14f + 0.04f * state.frame.loudLong
        for (index in 0 until MOST) {
            slide[index] += dt * (2.2f + 0.4f * index) * state.tempo * if (index % 2 == 0) 1f else -1f
            scroll[index] += dt * state.paced(0.6f + 0.18f * index) * direction * if (index % 2 == 0) 1f else -1f
            flipped[index] = (flipped[index] - dt / gestures.beatSeconds).coerceAtLeast(0f)
            barY[index] = stage.y + (index - 2) * 0.09f + swing * sin(slide[index])
        }
        if (gestures.snareHit > 0f) flipped[(random.next() * bars.count(3)).toInt().coerceIn(0, MOST - 1)] = 1f
        kit.place(0, sweep.position, barY[0])
        for (index in 1 until MOST) kit.place(index, 0.5f, barY[index])
        val kick = gestures.kickHit
        if (kick > 0f) {
            blocks.burst(random.next(), barY[0], (4 + 8 * kick).toInt(), 0.25f, 1.2f, 0.012f, random.next(), Sprite.DASH, PI.toFloat() / 2f, 1f)
        }
        if (gestures.hatHit > 0f) blocks.burst(random.next(), barY[1], 3, 0.3f, 1f, 0.008f, random.next(), Sprite.DASH, -PI.toFloat() / 2f, 0.8f)
        blocks.advance(dt, drag = 0.4f, gravity = 0.25f)
    }

    /** The gene's tilt plus the stage's sideways swing, in degrees. */
    private fun turn(): Float = (tilt.value + (stage.x - 0.5f) * 0.9f) * 57.29578f

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val walk = genes.walk
        val slotWidth = size.width * 1.2f / SLOTS
        for (index in 0 until MOST) {
            val presence = bars.presence(index, 3)
            if (presence <= 0.01f) continue
            val part = index % 3
            val punch = if (index == 0) 0.05f * thick.value.coerceIn(0f, 1.5f) else 0f
            val tall = size.height * (0.05f + 0.13f * state.energy + punch)
            val middle = barY[index] * size.height
            val flip = flipped[index] > 0f
            val alpha = (0.3f + 0.6f * state.lift) * presence
            for (slot in 0 until SLOTS) {
                val along = slot.toFloat() / SLOTS
                val value = split.sample(part, along)
                val reach = tall * (0.25f + 0.75f * value)
                val above = if (flip) reach * 0.3f else reach
                val below = if (notches.value == 1 || flip) reach else reach * 0.3f
                val colour = state.palette.argb(along + scroll[index] + walk, value = 0.4f + 0.6f * value, alpha = alpha)
                val left = -size.width * 0.1f + slot * slotWidth
                val a = mesh.vertex(left, middle - above, colour)
                val b = mesh.vertex(left + slotWidth * 0.8f, middle - above, colour)
                val c = mesh.vertex(left + slotWidth * 0.8f, middle + below, colour)
                val d = mesh.vertex(left, middle + below, colour)
                mesh.quad(a, b, c, d)
            }
        }
        rotate(turn(), center) { drawMesh(mesh) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        rotate(turn(), center) {
            val at = Offset(sweep.position * size.width, barY[0] * size.height)
            val reach = size.minDimension * 0.12f
            drawCircle(
                Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.55f), 1f to Color.Transparent, center = at, radius = reach),
                reach,
                at,
                blendMode = BlendMode.Plus,
            )
        }
        with(blocks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        for (index in 0 until MOST) slide[index] = index * 1.3f
        scroll.fill(0f)
        flipped.fill(0f)
        thick.reset()
        blocks.clear()
    }

    private companion object {
        const val MOST = 7
        const val SLOTS = 48
    }
}

/**
 * Spiral arms winding out from an eye that wanders the screen, with a second spiral turning the other
 * way. The echoes drain back into the eye, dots ride the arms out past the edges on the drums, a comet
 * crosses every bar, and a kick flings the debris circling the eye outward.
 */
internal class Vortex : Layered(
    name = "Vortex",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 103L, groundKind = GroundKind.Rings, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.08f, seed = 103)),
) {
    override val moodSpec: MoodSpec = MoodSpec(
        calmTrail = 0.86f,
        livelyTrail = 0.8f,
        calmZoom = 0.994f,
        livelyZoom = 0.982f,
        calmSpin = 0.75f,
        livelySpin = 1.4f,
    )
    override val echoBlend: EchoBlend get() = EchoBlend.Add

    private val turns = genes.number("Turns", 1.5f, 4.5f, 2.5f)
    private val arms = genes.choice("Arms", 3, start = 1)
    private val armMore = 3
    private val reversed = genes.toggle("Reversed", start = false)
    private val second = genes.toggle("Second spiral", start = true)

    private val stage = Stage(reachX = 0.2f, reachY = 0.15f, start = 0.8f)
    private val eye = Orbiter(radiusX = 0.3f, radiusY = 0.26f, lapsPerBar = 0.8f)
    private var spin = 0f
    private val push = Spring(stiffness = 60f, damping = 0.7f)
    private val riders = Travellers(24)
    private val comets = Comets()
    private val debris = Swarm(DEBRIS, 1_103L)
    private val shocks = Shocks()
    private val armMesh = TriangleMesh(maxVertices = 7 * DOTS * 13 + 16)
    private val topMesh = TriangleMesh(maxVertices = DEBRIS * 5 + 16)
    private val riderMesh = TriangleMesh(maxVertices = 24 * 12)
    private var armUnit = 4f

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val main = anchors.firstOrNull()
        val way = if (reversed.on) -1f else 1f
        return EchoFrame(zoomX = base.zoomX, spin = base.spin * way, centreX = main?.x ?: 0.5f, centreY = main?.y ?: 0.5f)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        eye.centreX = stage.x
        eye.centreY = stage.y
        eye.advance(state, gestures)
        kit.place(0, eye.x, eye.y)
        push.kick(gestures.kickHit * 6f)
        push.advance(dt)
        val way = if (reversed.on) -1f else 1f
        spin += dt * (1.1f * state.tempo + push.value) * way
        debris.targetX = eye.x
        debris.targetY = eye.y
        val kick = gestures.kickHit
        if (kick > 0f) {
            shocks.fire(eye.x, eye.y, random.next(), kick)
            for (index in 0 until debris.count) {
                val dx = debris.x[index] - eye.x
                val dy = debris.y[index] - eye.y
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                debris.vx[index] += dx / distance * 0.8f * kick
                debris.vy[index] += dy / distance * 0.8f * kick
            }
        }
        debris.advance(dt, speed = 0.3f + 0.7f * state.drive)
        shocks.advance(dt, gestures.beatSeconds * 2.5f)
        val hit = maxOf(kick, gestures.snareHit)
        if (hit > 0f || gestures.hatHit > 0f) {
            val count = arms.count(armMore)
            val arm = (random.next() * count).toInt()
            val angle = spin + arm * TAU / count + random.signed() * 0.3f
            riders.spawn(
                eye.x, eye.y, eye.x + cos(angle) * 1.2f, eye.y + sin(angle) * 1.2f,
                gestures.beatSeconds * 3f, PathShape.Arc, 0.25f * way, if (hit > 0f) 0.022f else 0.012f, random.next(), 0f, Sprite.GLOW,
            )
        }
        riders.advance(dt)
        kit.follow(1, riders)
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        armUnit = size.minDimension * 0.006f
        armMesh.clear()
        val ex = eye.x * size.width
        val ey = eye.y * size.height
        val reach = sceneRadius * 1.5f
        val count = arms.count(armMore)
        val walk = genes.walk
        for (arm in 0 until arms.drawn(armMore)) {
            val share = arms.presence(arm, armMore)
            if (share <= 0.01f) continue
            addArm(state, ex, ey, reach, spin + arm * TAU / count, turns.value, share, walk + arm * 0.2f, -1)
        }
        val extra = second.weight(1)
        if (extra > 0.01f) addArm(state, ex, ey, reach * 0.8f, -spin * 1.3f + 1f, -turns.value * 0.7f, extra * 0.7f, walk + 0.5f, 2)
        drawMesh(armMesh, BlendMode.Plus)
        with(shocks) { drawShocks(state.palette, walk, sceneRadius * 1.5f, size.minDimension * 0.018f, state.lift) }
        val core = size.minDimension * (0.05f + 0.05f * push.value.coerceIn(0f, 1.5f))
        drawCircle(
            Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.1f + 0.6f * state.lift), 1f to Color.Transparent, center = Offset(ex, ey), radius = core),
            core,
            Offset(ex, ey),
        )
        with(comets) { drawComets(state.palette, walk, alpha = state.lift) }
    }

    private fun addArm(state: VizRenderState, x: Float, y: Float, reach: Float, start: Float, windings: Float, share: Float, tint: Float, part: Int) {
        val bands = state.frame.bandsRel
        // A calm pad fills the bands too, so the light follows how hard the music pushes instead.
        val light = 0.06f + state.lift * state.lift * 1.6f
        for (index in 0 until DOTS) {
            val along = index.toFloat() / DOTS
            val value = if (part < 0) bands.sampleAt(along) else split.sample(part, along)
            val angle = TAU * windings * along + start
            val radius = reach * (0.04f + 0.96f * along) * (0.9f + 0.1f * value)
            val dot = armUnit * (1.2f + 3.6f * value) * (0.5f + 1.2f * along) * (0.6f + 0.4f * state.lift)
            val colour = state.palette.argb(along + tint, value = 0.45f + 0.55f * value, alpha = (0.15f + 0.6f * value) * light * share)
            val px = x + cos(angle) * radius
            val py = y + sin(angle) * radius
            // A hard core inside each soft dot, so the arms read as sharp moving points.
            armMesh.glow(px, py, dot, colour)
            armMesh.polygon(px, py, dot * 0.45f, 4, angle, colour)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        topMesh.clear()
        for (index in 0 until debris.count) {
            val colour = state.palette.argb(index * 0.013f + genes.walk, value = 1f, alpha = 0.15f + 0.6f * state.lift)
            topMesh.polygon(debris.x[index] * size.width, debris.y[index] * size.height, size.minDimension * 0.007f, 4, index * 0.7f, colour)
        }
        drawMesh(topMesh, BlendMode.Plus)
        drawTravellers(riders, riderMesh, state.palette, genes.walk)
    }

    override fun onReset() {
        stage.reset()
        eye.reset()
        spin = 0f
        push.reset()
        riders.clear()
        comets.clear()
        shocks.clear()
        debris.scatter()
    }

    private companion object {
        const val DOTS = 150
        const val DEBRIS = 120
    }
}

/**
 * A reticle that hunts: every bar it springs to whichever of its targets is loudest. The targets drift
 * across the whole screen, range rings sweep out from the reticle on the kick, a scan band crosses
 * once a bar, and blips light up on the hats.
 */
internal class LockOn : Layered(
    name = "Lock On",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 104L, groundKind = GroundKind.Grid, groundDim = 0.8f, detailKind = DetailKind.Scan, camera = Camera2D(wander = 0.06f, punch = 0.14f, seed = 104)),
) {
    override val moodSpec: MoodSpec = MoodSpec(
        calmTrail = 0.72f,
        livelyTrail = 0.58f,
        calmZoom = 1.005f,
        livelyZoom = 1.02f,
        calmSpin = 0.15f,
        livelySpin = 0.4f,
    )

    private val targets = genes.choice("Targets", 3, start = 1)
    private val rings = genes.choice("Rings", 4, start = 2)
    private val style = genes.choice("Reticle", 2)
    private val sweepBack = genes.toggle("Sweep reversed", start = false)

    private val stage = Stage(reachX = 0.22f, reachY = 0.16f, start = 1.6f)
    private var drift = 0f
    private val targetX = FloatArray(TARGETS) { 0.5f }
    private val targetY = FloatArray(TARGETS) { 0.5f }
    private var chosen = 0
    private val aimX = Spring(stiffness = 36f, damping = 0.72f, initial = 0.5f)
    private val aimY = Spring(stiffness = 36f, damping = 0.72f, initial = 0.5f)
    private val jump = Spring(stiffness = 150f, damping = 0.5f)
    private val flash = Envelope(attackPerSecond = 60f, releasePerSecond = 5f)
    private val turn = MusicClock(beatsPerCycle = 16f)
    private var spin = 0f
    private val pulse = FloatArray(PULSES)
    private val pulseX = FloatArray(PULSES)
    private val pulseY = FloatArray(PULSES)
    private var nextPulse = 0
    private val sweep = Sweep()
    private val blips = Sprites(160, 1_104L)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        drift += dt * TAU / (gestures.barSeconds * 1.6f) * state.tempo
        for (index in 0 until TARGETS) {
            targetX[index] = stage.x + 0.36f * sin(drift * FX[index] + index * 1.7f)
            targetY[index] = stage.y + 0.34f * sin(drift * FY[index] + index * 2.9f)
        }
        val count = targets.count(2)
        if (gestures.bar) {
            var best = (chosen + 1) % count
            var loudest = -1f
            for (index in 0 until count) {
                if (index == chosen) continue
                val level = split.level(index % 3)
                if (level > loudest) {
                    loudest = level
                    best = index
                }
            }
            chosen = best
        }
        if (chosen >= count) chosen = 0
        aimX.target = targetX[chosen]
        aimY.target = targetY[chosen]
        aimX.advance(dt)
        aimY.advance(dt)
        kit.place(0, aimX.value, aimY.value)
        for (index in 0 until TARGETS) kit.place(index + 1, targetX[index], targetY[index])
        jump.kick(gestures.kickHit * 5f)
        jump.advance(dt)
        flash.hit(maxOf(gestures.kickHit, gestures.snareHit))
        flash.advance(0f, dt)
        spin = turn.advance(dt, state.frame.bpm, state.frame.beatConfidence, state.frame.phrasePhase, state.paced(0.03f)) * TAU
        if (gestures.kickHit > 0f) {
            pulse[nextPulse] = 0.001f
            pulseX[nextPulse] = aimX.value
            pulseY[nextPulse] = aimY.value
            nextPulse = (nextPulse + 1) % PULSES
        }
        for (index in 0 until PULSES) {
            if (pulse[index] <= 0f) continue
            pulse[index] += dt / (gestures.beatSeconds * 1.5f)
            if (pulse[index] >= 1f) pulse[index] = 0f
        }
        sweep.backwards = sweepBack.on
        sweep.advance(gestures)
        kit.place(TARGETS + 1, sweep.position, 0.5f)
        if (gestures.hatHit > 0f) {
            val target = (random.next() * count).toInt().coerceIn(0, TARGETS - 1)
            blips.burst(targetX[target], targetY[target], 3, 0.12f, 0.6f, 0.012f, random.next(), Sprite.CROSS)
        }
        blips.advance(dt, drag = 1.5f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val at = Offset(aimX.value * size.width, aimY.value * size.height)
        val reach = size.minDimension * 0.46f
        val count = rings.count(3)
        val nudge = jump.value.coerceIn(-0.4f, 1.2f)
        val walk = genes.walk
        val lift = 0.35f + 0.65f * state.lift
        val glow = reach * 0.55f
        drawCircle(
            Brush.radialGradient(0f to state.palette.mid.copy(alpha = ((0.16f + 0.35f * flash.value) * lift).coerceIn(0f, 1f)), 1f to Color.Transparent, center = at, radius = glow),
            glow,
            at,
        )
        for (ring in 0 until rings.drawn(3)) {
            val presence = rings.presence(ring, 3)
            if (presence <= 0.01f) continue
            val energy = state.frame.bandsRel.sampleAt((ring + 1f) / count)
            val radius = reach * (ring + 1f) / count * (0.78f + 0.32f * energy) + reach * 0.07f * nudge
            drawCircle(
                color = state.palette.cycled(walk + ring * 0.15f, value = 0.4f + 0.6f * energy, alpha = ((0.4f + 0.6f * energy) * lift * presence).coerceIn(0f, 1f)),
                radius = radius.coerceAtLeast(1f),
                center = at,
                style = Stroke((size.minDimension * (0.018f + 0.02f * state.frame.snarePulse)).coerceAtLeast(2f)),
            )
        }
        val hair = state.palette.cap.copy(alpha = ((0.25f + 0.55f * flash.value) * lift).coerceIn(0f, 1f))
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        val arm = reach * (1.05f + 0.12f * nudge)
        for (spoke in 0 until 2) {
            val angle = spin + spoke * TAU * 0.25f
            drawLine(hair, polar(at, angle, -arm), polar(at, angle, arm), thin)
        }
        val box = style.weight(1)
        if (box > 0.01f) {
            val side = reach * 0.35f
            for (corner in 0 until 4) {
                val angle = spin + TAU / 8f + corner * TAU / 4f
                drawLine(hair.copy(alpha = hair.alpha * box), polar(at, angle, side), polar(at, angle, side * 1.4f), thin * 2f)
            }
        }
        for (index in 0 until targets.drawn(2)) {
            val presence = targets.presence(index, 2)
            if (presence <= 0.01f) continue
            val target = Offset(targetX[index] * size.width, targetY[index] * size.height)
            val lit = if (index == chosen) 1f else 0.5f
            val radius = size.minDimension * 0.07f * (1f + split.level(index % 3))
            drawCircle(
                Brush.radialGradient(0f to state.palette.cycled(index * 0.25f + walk, alpha = (0.5f * lit * lift * presence).coerceIn(0f, 1f)), 1f to Color.Transparent, center = target, radius = radius * 1.8f),
                radius * 1.8f,
                target,
            )
            drawCircle(
                state.palette.cycled(index * 0.25f + walk, value = 1f, alpha = (0.8f * lit * presence).coerceIn(0f, 1f)),
                radius,
                target,
                style = Stroke(thin * 2f),
            )
            drawLine(state.palette.mid.copy(alpha = 0.25f * presence), at, target, thin)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        for (index in 0 until PULSES) {
            val progress = pulse[index]
            if (progress <= 0f) continue
            drawCircle(
                state.palette.cap.copy(alpha = (0.8f * (1f - progress)).coerceIn(0f, 1f)),
                sceneRadius * 1.5f * progress,
                Offset(pulseX[index] * size.width, pulseY[index] * size.height),
                style = Stroke(thin * (3f + 8f * (1f - progress))),
            )
        }
        val x = sweep.position * size.width
        val band = size.width * 0.04f
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to state.palette.high.copy(alpha = (0.12f + 0.25f * state.lift).coerceIn(0f, 1f)),
                1f to Color.Transparent,
                startX = x - band,
                endX = x + band,
            ),
            topLeft = Offset(x - band, 0f),
            size = androidx.compose.ui.geometry.Size(band * 2f, size.height),
            blendMode = BlendMode.Plus,
        )
        with(blips) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        drift = 0f
        chosen = 0
        aimX.reset(0.5f)
        aimY.reset(0.5f)
        jump.reset()
        flash.reset()
        turn.reset()
        pulse.fill(0f)
        blips.clear()
    }

    private companion object {
        const val TARGETS = 4
        const val PULSES = 6
        val FX = floatArrayOf(1f, 1.3f, 0.7f, 1.7f)
        val FY = floatArrayOf(1.4f, 0.8f, 1.9f, 1.1f)
    }
}

/**
 * A burst of wedges on an orbit, with satellite bursts for each part of the spectrum turning the other
 * way round it and a slower layer of wedges behind. The wedges reach past the corners, a kick snaps
 * them wide, a comet crosses every bar, and a drop sends the colours a quarter of the way round.
 */
internal class Sunburst : Layered(
    name = "Sunburst",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 105L, groundKind = GroundKind.Rays, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.08f, seed = 105)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.58f, calmSpin = 0.2f, livelySpin = 0.5f)

    private val satellites = genes.choice("Satellites", 5, start = 2)
    private val wedges = genes.choice("Wedges", 3, start = 1)
    private val counter = genes.toggle("Layers turn apart", start = true)
    private val arrangement = genes.choice("Arrangement", 3)

    private val stage = Stage(start = 1.1f)
    private val main = Orbiter(radiusX = 0.3f, radiusY = 0.24f, lapsPerBar = 0.8f)
    private val moons = Array(MOONS) { Orbiter(radiusX = 0.4f, radiusY = 0.34f, lapsPerBar = 0.3f + 0.07f * it, phase = it / MOONS.toFloat()) }
    private val moonX = FloatArray(MOONS) { 0.5f }
    private val moonY = FloatArray(MOONS) { 0.5f }
    private val turn = MusicClock(beatsPerCycle = 16f)
    private var spin = 0f
    private var behind = 0f
    private val snap = Spring(stiffness = 200f, damping = 0.45f)
    private var hueGoal = 0f
    private val hue = Slew(maxPerSecond = 0.3f)
    private val comets = Comets()
    private val sparks = Sprites(260, 1_105L)
    private val mesh = TriangleMesh(maxVertices = (2 + MOONS) * 24 * 4 + 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        main.centreX = stage.x
        main.centreY = stage.y
        main.advance(state, gestures)
        kit.place(0, main.x, main.y)
        spin = -turn.advance(dt, state.frame.bpm, state.frame.beatConfidence, state.frame.phrasePhase, state.paced(0.03f)) * TAU
        behind += dt * 0.3f * state.tempo * if (counter.on) -1f else 1f
        snap.kick(gestures.kickHit * 8f)
        snap.advance(dt)
        if (gestures.drop) hueGoal += 0.25f
        hue.advance(hueGoal, dt)
        for (index in 0 until MOONS) {
            val moon = moons[index]
            moon.centreX = stage.x
            moon.centreY = stage.y
            moon.direction = -1f
            moon.advance(state, gestures)
            var x = 0f
            var y = 0f
            for (option in 0 until 3) {
                val share = arrangement.weight(option)
                if (share <= 0f) continue
                x += share * when (option) {
                    0 -> main.x + 0.22f * cos(moon.angle)
                    1 -> moon.x
                    else -> 0.14f + 0.24f * index + 0.06f * cos(moon.angle)
                }
                y += share * when (option) {
                    0 -> main.y + 0.2f * sin(moon.angle)
                    1 -> moon.y
                    else -> 0.5f + 0.3f * sin(moon.angle * 0.7f + index)
                }
            }
            moonX[index] = x
            moonY[index] = y
            kit.place(index + 1, x, y)
        }
        comets.advance(state, gestures, random)
        kit.follow(MOONS + 1, comets.travellers)
        if (gestures.kickHit > 0f) sparks.burst(main.x, main.y, (10 + 14 * gestures.kickHit).toInt(), 0.7f, 0.8f, 0.012f, random.next(), Sprite.SPARK)
        sparks.advance(dt, drag = 0.9f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val count = 12 + 6 * wedges.value
        val walk = genes.walk + hue.value
        val x = main.x * size.width
        val y = main.y * size.height
        addBurst(state, x, y, sceneRadius * 1.25f, behind, count, 0.3f, walk + 0.4f, -1, wide = true)
        addBurst(state, x, y, sceneRadius * 1.15f, spin, count, 1f, walk, -1, wide = false)
        for (index in 0 until MOONS) {
            val presence = satellites.presence(index, 0)
            if (presence <= 0.01f) continue
            addBurst(state, moonX[index] * size.width, moonY[index] * size.height, sceneRadius * 0.34f, -spin * 1.4f + index, count / 2, presence, walk + 0.2f * index, index % 3, wide = false)
        }
        drawMesh(mesh)
        with(comets) { drawComets(state.palette, walk) }
    }

    private fun DrawScope.addBurst(state: VizRenderState, x: Float, y: Float, reach: Float, turn: Float, count: Int, presence: Float, tint: Float, part: Int, wide: Boolean) {
        val inner = size.minDimension * 0.05f
        val step = TAU / count
        val open = 0.2f * snap.value.coerceIn(0f, 1.4f)
        for (index in 0 until count) {
            val along = index.toFloat() / count
            val value = if (part < 0) state.frame.bandsRel.foldedAt(along) else split.folded(part, along)
            val from = step * index + turn
            val to = from + step * if (wide) 0.95f else 0.8f
            val tip = inner + reach * (0.35f * state.presence + 0.65f * value + open)
            val colour = state.palette.argb(tint + along, value = (0.2f + 0.45f * value + 0.35f * state.lift).coerceIn(0f, 1f), alpha = presence * if (wide) 0.45f else 0.9f)
            val a = mesh.vertex(x + sin(from) * inner, y - cos(from) * inner, colour)
            val b = mesh.vertex(x + sin(from) * tip, y - cos(from) * tip, colour)
            val c = mesh.vertex(x + sin(to) * tip, y - cos(to) * tip, colour)
            val d = mesh.vertex(x + sin(to) * inner, y - cos(to) * inner, colour)
            mesh.quad(a, b, c, d)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk + hue.value) }
    }

    override fun onReset() {
        stage.reset()
        main.reset()
        moons.forEach { it.reset() }
        turn.reset()
        spin = 0f
        behind = 0f
        snap.reset()
        hueGoal = 0f
        hue.reset()
        comets.clear()
        sparks.clear()
    }

    private companion object {
        const val MOONS = 4
    }
}

/**
 * Rings born beyond the corners that collapse onto a centre on the drums, the centre itself on the
 * move. A kick makes a wide ring and a snare a thin one, each ring reaches its centre on the beat, and
 * where it lands it throws shards to the edges. A drop sends rings in from every side at once.
 */
internal class Implosion : Layered(
    name = "Implosion",
    family = VizFamily.Battery,
    bucket = VizEnergy.High,
    kit = Kit(seed = 106L, groundKind = GroundKind.Rings, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.1f, seed = 106)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.58f, livelyTrail = 0.46f)

    private val centres = genes.choice("Centres", 2)
    private val shape = genes.choice("Ring shape", 3)
    private val collapse = genes.choice("Collapse", 2)
    private val shardKind = genes.choice("Shards", 3)

    private val stage = Stage(reachX = 0.2f, reachY = 0.15f, start = 2.2f)
    private val homes = Array(2) { Orbiter(radiusX = 0.34f, radiusY = 0.28f, lapsPerBar = if (it == 0) 0.3f else 0.2f, phase = it * 0.5f) }
    private val along = FloatArray(RINGS)
    private val strength = FloatArray(RINGS)
    private val tint = FloatArray(RINGS)
    private val weight = FloatArray(RINGS)
    private val home = IntArray(RINGS)
    private var next = 0
    private val core = Spring(stiffness = 240f, damping = 0.4f)
    private val shards = Sprites(320, 1_106L)
    private val sweep = Sweep()
    private val path = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        for (index in 0 until 2) {
            homes[index].centreX = stage.x
            homes[index].centreY = stage.y
            homes[index].advance(state, gestures)
            kit.place(index + 1, homes[index].x, homes[index].y)
        }
        // Weak onsets still make faint rings, so a calm passage keeps its slow pulse.
        val kick = state.frame.kick
        val snare = state.frame.snare
        val count = centres.count(1)
        if (kick > 0f) born(kick, 1f, state.bassMotion, next % count, 1f)
        if (snare > 0f) born(snare, 0.45f, 0.6f + 0.3f * state.air, (next + 1) % count, 1f)
        if (gestures.drop) for (extra in 0 until 6) born(1f, 0.8f, extra / 6f, extra % count, 1f + extra * 0.06f)
        core.kick(gestures.kickHit * 8f)
        core.advance(dt)
        val speed = dt / (gestures.beatSeconds * (collapse.value + 1))
        for (slot in 0 until RINGS) {
            if (strength[slot] <= 0f) continue
            along[slot] -= speed
            if (along[slot] <= 0f) {
                val centre = homes[home[slot]]
                val kind = when (shardKind.value) {
                    0 -> Sprite.SHARD
                    1 -> Sprite.DASH
                    else -> Sprite.DIAMOND
                }
                shards.burst(centre.x, centre.y, (6 + 10 * strength[slot]).toInt(), 0.9f, 0.8f, 0.026f, tint[slot], kind)
                strength[slot] = 0f
            }
        }
        shards.advance(dt, drag = 0.6f)
        sweep.advance(gestures)
        kit.place(0, sweep.position, 0.5f)
    }

    private fun born(hit: Float, thickness: Float, colour: Float, centre: Int, start: Float) {
        along[next] = start
        tint[next] = colour
        strength[next] = 0.1f + 0.9f * hit
        weight[next] = thickness
        home[next] = centre
        next = (next + 1) % RINGS
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val reach = sceneRadius * 1.45f
        val walk = genes.walk
        val lift = 0.5f + 0.5f * state.lift
        for (slot in 0 until RINGS) {
            if (strength[slot] <= 0f) continue
            val centre = homes[home[slot]]
            val at = Offset(centre.x * size.width, centre.y * size.height)
            val t = along[slot]
            val radius = reach * t
            if (radius < 1f) continue
            val alpha = (strength[slot] * (0.25f + 0.75f * (1f - t)) * lift).coerceIn(0f, 1f)
            val colour = state.palette.cycled(tint[slot] + walk, value = 1f, alpha = alpha)
            val stroke = Stroke((size.minDimension * 0.042f * weight[slot]) * (1f - t) + 3.5f)
            val round = shape.weight(0)
            if (round > 0.01f) drawCircle(colour.copy(alpha = alpha * round), radius, at, style = stroke)
            val figure = shape.weight(1)
            val corners = shape.weight(2)
            if (figure > 0.01f || corners > 0.01f) {
                val sides = if (figure >= corners) 64 else 6
                path.reset()
                for (point in 0..sides) {
                    val a = TAU * point / sides + t * 2f
                    val swell = if (sides == 64) 0.8f + 0.35f * state.frame.bandsRel.foldedAt(point.toFloat() / sides) else 1f
                    val p = polar(at, a, radius * swell)
                    if (point == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, colour.copy(alpha = alpha * maxOf(figure, corners)), style = stroke)
            }
        }
        for (index in 0 until centres.drawn(1)) {
            val presence = centres.presence(index, 1)
            if (presence <= 0.01f) continue
            val centre = Offset(homes[index].x * size.width, homes[index].y * size.height)
            // A wide glow round each home, so the bright part of the picture sits where the homes are.
            val halo = size.minDimension * 0.4f
            drawCircle(
                Brush.radialGradient(0f to state.palette.cycled(index * 0.4f + walk, alpha = ((0.2f + 0.5f * state.lift) * presence).coerceIn(0f, 1f)), 1f to Color.Transparent, center = centre, radius = halo),
                halo,
                centre,
            )
            drawCircle(
                state.palette.cap.copy(alpha = ((0.3f + 0.6f * core.value) * presence).coerceIn(0f, 1f)),
                size.minDimension * 0.075f * (0.5f + core.value.coerceIn(0f, 1.5f)),
                centre,
            )
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(shards) { drawSprites(state.palette, genes.walk) }
        val x = sweep.position * size.width
        drawLine(
            state.palette.cap.copy(alpha = 0.2f + 0.2f * state.lift),
            Offset(x - size.height * 0.3f, 0f),
            Offset(x + size.height * 0.3f, size.height),
            (size.minDimension * 0.005f).coerceAtLeast(1f),
        )
    }

    override fun onReset() {
        stage.reset()
        homes.forEach { it.reset() }
        strength.fill(0f)
        next = 0
        core.reset()
        shards.clear()
    }

    private companion object {
        const val RINGS = 18
    }
}

/**
 * Two arcs of spokes orbiting each other at opposite ends of one ellipse, each reading its own part
 * of the spectrum, so each crosses the screen twice a lap. A kick throws them apart, the top of a
 * phrase turns them the other way, and a drop smashes them together in the middle.
 */
internal class Gemini : Layered(
    name = "Gemini",
    family = VizFamily.Battery,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 107L, groundKind = GroundKind.Plasma, groundDim = 0.75f, detailKind = DetailKind.Hatch, camera = Camera2D(wander = 0.08f, seed = 107)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.74f, livelyTrail = 0.6f, calmSpin = 0.35f, livelySpin = 0.5f)

    private val pairs = genes.choice("Pairs", 2)
    private val shape = genes.number("Orbit shape", 0.4f, 1f, 0.75f)
    private val spokes = genes.choice("Spokes", 3, start = 1)
    private val mirror = genes.choice("Mirror", 2)

    private val stage = Stage(start = 3f)
    private var orbit = 0f
    private var spin = 0f
    private var direction = 1f
    private var swapped = false
    private val apart = Spring(stiffness = 90f, damping = 0.55f)
    private var crash = 0f
    private val comets = Comets()
    private val shards = Sprites(240, 1_107L)
    private val armX = FloatArray(4) { 0.5f }
    private val armY = FloatArray(4) { 0.5f }
    private val mesh = TriangleMesh(maxVertices = 4 * 48 * 4 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        if (gestures.phrase) {
            direction = -direction
            swapped = !swapped
        }
        orbit += dt * TAU / (gestures.barSeconds * 1.3f) * state.tempo * direction
        spin += dt * 1.3f * state.tempo * direction
        apart.kick(gestures.kickHit * 4f)
        apart.advance(dt)
        if (gestures.drop) {
            crash = 1f
            shards.burst(stage.x, stage.y, 40, 1f, 1f, 0.025f, random.next(), Sprite.SHARD)
        }
        crash = (crash - dt / gestures.beatSeconds).coerceAtLeast(0f)
        val radius = (0.36f + 0.06f * apart.value) * (1f - crash)
        for (index in 0 until 4) {
            val pair = index / 2
            val scale = if (pair == 0) 1f else 0.5f
            val phase = orbit * (if (pair == 0) 1f else -0.7f) + (index % 2) * PI.toFloat() + pair * 1.5707964f
            armX[index] = stage.x + radius * scale * cos(phase)
            armY[index] = stage.y + radius * scale * shape.value * 1.1f * sin(phase)
            kit.place(index, armX[index], armY[index])
        }
        if (gestures.hatHit > 0f) shards.burst(armX[0], armY[0], 3, 0.4f, 0.5f, 0.012f, random.next(), Sprite.DIAMOND)
        shards.advance(dt, drag = 0.7f)
        comets.advance(state, gestures, random)
        kit.follow(4, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val count = 24 + 12 * spokes.value
        val walk = genes.walk
        for (index in 0 until 4) {
            val pair = index / 2
            val presence = if (pair == 0) 1f else pairs.weight(1)
            if (presence <= 0.01f) continue
            val side = index % 2
            val part = if (swapped) 2 - side * 2 else side * 2
            val reach = sceneRadius * (if (pair == 0) 0.8f else 0.46f)
            val facing = if (mirror.value == 1 && side == 1) -1f else 1f
            addArc(state, armX[index] * size.width, armY[index] * size.height, reach, spin * facing + side * PI.toFloat(), count, part, presence, walk + side * 0.5f + pair * 0.25f)
        }
        drawMesh(mesh)
        with(comets) { drawComets(state.palette, walk) }
    }

    private fun DrawScope.addArc(state: VizRenderState, x: Float, y: Float, reach: Float, start: Float, count: Int, part: Int, presence: Float, tint: Float) {
        val inner = reach * 0.25f
        val width = PI.toFloat() / count * 0.7f
        for (spoke in 0 until count) {
            val along = spoke.toFloat() / count
            val value = split.sample(part, along)
            val angle = start + PI.toFloat() * along
            val tip = inner + reach * (0.2f * state.presence + 0.8f * value)
            val colour = state.palette.argb(tint + along * 0.5f, value = 0.4f + 0.6f * value, alpha = (0.3f + 0.6f * state.lift) * presence)
            val a = mesh.vertex(x + cos(angle - width) * inner, y + sin(angle - width) * inner, colour)
            val b = mesh.vertex(x + cos(angle - width) * tip, y + sin(angle - width) * tip, colour)
            val c = mesh.vertex(x + cos(angle + width) * tip, y + sin(angle + width) * tip, colour)
            val d = mesh.vertex(x + cos(angle + width) * inner, y + sin(angle + width) * inner, colour)
            mesh.quad(a, b, c, d)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(shards) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        orbit = 0f
        spin = 0f
        direction = 1f
        swapped = false
        apart.reset()
        crash = 0f
        comets.clear()
        shards.clear()
    }
}

/**
 * Cars racing a track that spans the corners, each at the speed of its part of the spectrum, their
 * streaks kept by a long echo that slowly turns. The lead car boosts on the kick and throws sparks,
 * and the shape of the track changes as the song goes on.
 */
internal class Skidmark : Layered(
    name = "Skidmark",
    family = VizFamily.Battery,
    bucket = VizEnergy.High,
    kit = Kit(seed = 108L, groundKind = GroundKind.Voronoi, groundDim = 0.7f, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 108)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.95f, livelyTrail = 0.9f, livelyZoom = 1.01f, calmSpin = 0.1f, livelySpin = 0.6f)

    private val cars = genes.choice("Cars", 4, start = 2)
    private val carStep = 2
    private val track = genes.choice("Track", 3)
    private val streak = genes.number("Streak", -0.05f, 0.03f, 0f)
    private val showTrack = genes.toggle("Track visible", start = true)

    private val stage = Stage(start = 2.6f)
    private val along = FloatArray(CARS) { it / CARS.toFloat() }
    private val carX = FloatArray(CARS) { 0.5f }
    private val carY = FloatArray(CARS) { 0.5f }
    private val boost = Spring(stiffness = 60f, damping = 0.6f)
    private val sparks = Sprites(280, 1_108L)
    private val path = Path()

    override fun trailAt(mood: Float): Float = (super.trailAt(mood) + streak.value).coerceIn(0f, 0.97f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        boost.kick(gestures.kickHit * 5f)
        boost.advance(dt)
        for (car in 0 until CARS) {
            val speed = (0.3f + 0.04f * car) * (0.5f + 0.9f * state.drive) + split.level(car % 3) * 0.08f +
                if (car == 0) boost.value.coerceAtLeast(0f) * 0.12f else 0f
            along[car] += dt * speed
            carX[car] = trackX(along[car])
            carY[car] = trackY(along[car])
            kit.place(car, carX[car], carY[car])
        }
        val kick = gestures.kickHit
        if (kick > 0f) {
            for (car in 0 until cars.drawn(3, carStep)) sparks.burst(carX[car], carY[car], (4 + 6 * kick).toInt(), 0.35f, 0.5f, 0.01f, car * 0.2f, Sprite.SPARK)
        }
        sparks.advance(dt, drag = 1.4f)
    }

    // The track is centred on the slow stage path, so the whole circuit wanders over the song.
    private fun trackX(t: Float): Float {
        val a = t * TAU
        var x = 0f
        var total = 0f
        for (option in 0 until 3) {
            val share = track.weight(option)
            if (share <= 0f) continue
            x += share * when (option) {
                0 -> 0.4f * cos(a)
                1 -> 0.42f * sin(a)
                else -> (0.3f + 0.12f * cos(5f * a)) * cos(a) * 1.15f
            }
            total += share
        }
        return 0.5f + (stage.x - 0.5f) * 0.5f + x / total.coerceAtLeast(1e-4f)
    }

    private fun trackY(t: Float): Float {
        val a = t * TAU
        var y = 0f
        var total = 0f
        for (option in 0 until 3) {
            val share = track.weight(option)
            if (share <= 0f) continue
            y += share * when (option) {
                0 -> 0.38f * sin(a)
                1 -> 0.36f * sin(2f * a)
                else -> (0.3f + 0.12f * cos(5f * a)) * sin(a) * 1.05f
            }
            total += share
        }
        return 0.5f + (stage.y - 0.5f) * 0.5f + y / total.coerceAtLeast(1e-4f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val walk = genes.walk
        val glow = 0.45f + 0.55f * state.lift
        for (car in 0 until cars.drawn(3, carStep)) {
            val presence = cars.presence(car, 3, carStep)
            if (presence <= 0.01f) continue
            val at = Offset(carX[car] * size.width, carY[car] * size.height)
            val head = size.minDimension * 0.026f * (0.7f + (if (car == 0) boost.value.coerceIn(0f, 1.6f) else split.level(car % 3)))
            drawCircle(
                Brush.radialGradient(0f to state.palette.cycled(car * 0.21f + walk, alpha = presence * glow), 1f to Color.Transparent, center = at, radius = head * 5f),
                head * 5f,
                at,
            )
            drawCircle(state.palette.cap.copy(alpha = presence), head, at)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val visible = showTrack.weight(1)
        if (visible > 0.01f) {
            path.reset()
            for (point in 0..120) {
                val t = point / 120f
                val x = trackX(t) * size.width
                val y = trackY(t) * size.height
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, state.palette.mid.copy(alpha = 0.14f * visible), style = Stroke((size.minDimension * 0.004f).coerceAtLeast(1f), cap = StrokeCap.Round))
        }
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        for (car in 0 until CARS) along[car] = car / CARS.toFloat()
        boost.reset()
        sparks.clear()
    }

    private companion object {
        const val CARS = 9
    }
}
