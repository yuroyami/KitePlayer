package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Colour, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Pulse, VizProperty.Shape),
    )
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
        stage.advance(dt * state.idle)
        main.centreX = stage.x
        main.centreY = stage.y
        main.advance(state, gestures)
        kit.place(0, main.x, main.y)
        spin = -turn.advance(dt, state.frame, state.paced(0.03f)) * TAU
        behind += dt * 0.3f * state.tempo * if (counter.on) -1f else 1f
        snap.kick(gestures.kick * 8f)
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
        if (gestures.kick > 0f) sparks.burst(main.x, main.y, (10 + 14 * gestures.kick).toInt(), 0.7f, 0.8f, 0.012f, random.next(), Sprite.SPARK)
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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.spring(0.4f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
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
        stage.advance(dt * state.idle)
        if (gestures.section) {
            direction = -direction
            swapped = !swapped
        }
        orbit += dt * TAU / (gestures.cycleSeconds * 1.3f) * state.tempo * direction
        spin += dt * 1.3f * state.tempo * direction
        apart.kick(gestures.kick * 4f)
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
        if (gestures.hat > 0f) shards.burst(armX[0], armY[0], gestures.hatSpawn(3), 0.4f, 0.5f, 0.012f, random.next(), Sprite.DIAMOND)
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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Speed),
        VizDrive(VizDriver.Bands, VizProperty.Speed),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.5f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
    )
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
        stage.advance(dt * state.idle)
        boost.kick(gestures.kick * 5f)
        boost.advance(dt)
        for (car in 0 until CARS) {
            val speed = (0.3f + 0.04f * car) * (0.1f + 1.7f * state.drive) + split.level(car % 3) * 0.08f +
                if (car == 0) boost.value.coerceAtLeast(0f) * 0.12f else 0f
            along[car] += dt * speed
            carX[car] = trackX(along[car])
            carY[car] = trackY(along[car])
            kit.place(car, carX[car], carY[car])
        }
        val kick = gestures.kick
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
