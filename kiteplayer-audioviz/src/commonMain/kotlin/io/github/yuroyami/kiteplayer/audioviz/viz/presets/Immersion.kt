package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.CameraRig
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoCopy
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glyph
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The flat camera that goes with a drawing flown by its own camera in three dimensions: it only punches. */
private fun stillCamera(seed: Long): Camera2D = Camera2D(wander = 0f, punch = 0.03f, roll = 0f, shake = 0f, cuts = false, seed = seed.toInt())

/**
 * Points rushing past the camera on three depths, drawn as streaks, over a cloud that scrolls with the
 * camera. A planet lobed by the spectrum drifts past every couple of phrases, asteroids tumble across,
 * the camera banks, a snare cuts to a new lane, and a drop sends a planet right through the frame.
 *
 * A dot moving fast between two frames looks like a dot that jumped; the line between where it was and
 * where it is turns the same motion into speed.
 */
internal open class Starfield(
    name: String = "Starfield",
    override val trail: Float = 0.3f,
    override val feedbackZoom: Float = 1f,
    override val bloom: Int = 0,
    override val moodSpec: MoodSpec? = null,
    bucket: VizEnergy = VizEnergy.Mid,
    /** Multiplies the whole flight. Below 1 this is a drift rather than a warp. */
    private val speedScale: Float = 1f,
    /** How long a streak is drawn for the same speed. Zero draws round stars instead. */
    private val streakScale: Float = 1f,
    family: VizFamily = VizFamily.Immersion,
    seed: Long = 711L,
    private val count: Int = 4200,
    cuts: Boolean = true,
) : Layered(
    name = name,
    family = family,
    bucket = bucket,
    kit = Kit(seed = seed, groundKind = GroundKind.Cloud, groundDim = 0.8f, detailKind = DetailKind.Specks, detailStrength = 0.5f, camera = stillCamera(seed)),
) {
    override val cameraOnEcho: Boolean get() = false

    private val starDensity = genes.number("Star density", 0.5f, 1f, 0.85f)
    private val planetRate = genes.choice("Planets", 3, start = 1)
    private val asteroidRate = genes.number("Asteroids", 0.5f, 2f, 1f)
    private val bank = genes.number("Bank", 0f, 0.6f, 0.3f)

    private val spread = 26f
    private val depth = 52f
    private val scene = Scene3D()
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val z = FloatArray(count)
    private val tint = FloatArray(count)
    private var seeded = false
    private var roll = 0f
    private var moved = 0f
    private var laneX = 0f
    private var laneY = 0f
    private var look = 0f
    private val rig = CameraRig(topSpeed = 48.5f, restSpeed = 10f, shove = 14f, sway = 0.4f, lean = 0.25f, baseFov = 82f, cuts = cuts, seed = 3_331)

    private val speedParam = VizParam("Speed", 0.1f, 3f, 1f)
    private val streakParam = VizParam("Streaks", 0f, 3f, streakScale)
    override val params: List<VizParam> = listOf(speedParam, streakParam)

    // Twelve corners a round star, four a streak; a mesh holds at most 32767, and a full one just stops adding.
    private val mesh = TriangleMesh(maxVertices = (count * 12 + 16).coerceAtMost(32_767))
    private var planet = -1f
    private var planetBig = false
    private var planetSide = 1f
    private var planetY = 0.4f
    private var planetTint = 0f
    private var phrasesSeen = 0
    private val asteroids = Travellers(12)
    private val rockMesh = TriangleMesh(maxVertices = 12 * 10 + 8)
    private var rockCredit = 0f
    private val shape = Path()

    /** For a subclass to move its own things on each frame. */
    protected open fun onAdvance(state: VizRenderState) {}

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (!seeded) {
            for (index in 0 until count) respawn(index, anywhere = true)
            seeded = true
        }
        moved = rig.advance(state, speedScale * speedParam.value)
        roll += dt * state.paced(0.55f) * 0.5f
        look += dt * TAU / 16f
        // A snare cuts to a new lane: the camera jumps sideways rather than sliding there.
        if (gestures.snareHit > 0f && streakScale > 0f) {
            laneX = random.signed() * 3f
            laneY = random.signed() * 2f
        }
        for (index in 0 until count) {
            z[index] += moved
            if (z[index] > -0.6f) respawn(index, anywhere = false)
        }
        // A planet every four, two or one phrases, and a drop sends one right through the frame.
        if (gestures.phrase) {
            phrasesSeen++
            val every = when (planetRate.value) {
                0 -> 4
                1 -> 2
                else -> 1
            }
            if (phrasesSeen % every == 0 && planet < 0f) startPlanet(big = false)
        }
        if (gestures.drop) startPlanet(big = true)
        if (planet >= 0f) {
            planet += dt / (gestures.barSeconds * if (planetBig) 1f else 3f) * speedScale.coerceAtLeast(0.3f)
            if (planet > 1f) planet = -1f
        }
        // Asteroids tumble across, one a bar at least and more when the music drives.
        rockCredit += dt * asteroidRate.value * state.drive / gestures.beatSeconds * 0.5f
        val flown = asteroids.anyNewest && asteroids.progress[asteroids.newest] > 0.6f
        if (!asteroids.anyNewest || ((gestures.bar || rockCredit >= 1f) && flown)) {
            rockCredit = 0f
            asteroids.across(random, gestures.barSeconds * 0.7f / speedScale.coerceAtLeast(0.6f), PathShape.Line, 0f, 0.025f + 0.03f * random.next(), random.next(), random.signed() * 3f, Sprite.HEX)
        }
        asteroids.advance(dt)
        kit.follow(0, asteroids)
        if (planet >= 0f) kit.place(1, planetX(), planetY)
        onAdvance(state)
    }

    private fun startPlanet(big: Boolean) {
        planet = 0f
        planetBig = big
        planetSide = if (random.next() < 0.5f) 1f else -1f
        planetY = 0.3f + 0.4f * random.next()
        planetTint = random.next()
    }

    private fun planetX(): Float = if (planetSide > 0f) -0.35f + 1.7f * planet else 1.35f - 1.7f * planet

    /** Sends a star back to the far end. [anywhere] spreads the first batch through the volume. */
    private fun respawn(index: Int, anywhere: Boolean) {
        x[index] = random.signed() * spread * 0.5f
        y[index] = random.signed() * spread * 0.5f
        z[index] = if (anywhere) -random.next() * depth else -depth
        tint[index] = random.next()
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        scene.lens(size, fovDegrees = rig.fov, near = 0.4f, far = depth)
        val banked = roll + rig.roll * (0.5f + bank.value * 3f)
        scene.camera(rig.eyeX + laneX, rig.eyeY + laneY, 0f, laneX + 1f * sin(look), laneY + 0.7f * cos(look), -1f, roll = banked)
        val lift = 0.4f + 0.6f * state.lift
        val live = (count * starDensity.value * (0.55f + 0.45f * state.texture)).toInt().coerceIn(40, count)
        val streak = (moved * streakParam.value * (4f + 4f * state.bassMotion)).coerceIn(0.05f, 7f)
        val round = streakParam.value <= 0.01f
        mesh.clear()
        for (index in 0 until live) {
            if (!scene.project(x[index], y[index], z[index])) continue
            val headX = scene.screenX
            val headY = scene.screenY
            val fog = scene.fog(-z[index])
            val colour = state.palette.argb(tint[index], saturation = 0.75f, value = 1f, alpha = (0.5f + 0.5f * fog) * lift)
            if (round) {
                mesh.glow(headX, headY, (size.minDimension * 0.016f * fog).coerceAtLeast(1f), colour)
                // A hard core in the glow, so a slow star still reads as a point that moves.
                mesh.polygon(headX, headY, (size.minDimension * 0.005f * fog).coerceAtLeast(0.8f), 4, 0.785f, colour)
                continue
            }
            if (!scene.project(x[index], y[index], z[index] - streak)) continue
            mesh.streak(scene.screenX, scene.screenY, headX, headY, (size.minDimension * 0.016f * fog).coerceAtLeast(1f), colour)
        }
        drawMesh(mesh, BlendMode.Plus)
        if (planet >= 0f) drawPlanet(state)
        rockMesh.clear()
        for (slot in 0 until asteroids.capacity) {
            if (!asteroids.alive[slot]) continue
            val colour = state.palette.argb(asteroids.tint[slot] + genes.walk, saturation = 0.3f, value = 0.7f, alpha = 0.9f)
            rockMesh.polygon(asteroids.x[slot] * size.width, asteroids.y[slot] * size.height, asteroids.size[slot] * size.minDimension, 7, asteroids.angle[slot], colour, colour and 0x7FFFFFFF)
        }
        drawMesh(rockMesh)
    }

    // A disc lobed by the spectrum, lit from one side, drifting past.
    private fun DrawScope.drawPlanet(state: VizRenderState) {
        val at = Offset(planetX() * size.width, planetY * size.height)
        val radius = size.minDimension * if (planetBig) 0.9f else 0.22f
        val bands = state.frame.bandsRel
        shape.reset()
        for (point in 0..48) {
            val along = point / 48f
            val swell = 1f + 0.06f * (if (bands.isEmpty()) 0f else bands.foldedAt(along))
            val angle = TAU * along
            val px = at.x + cos(angle) * radius * swell
            val py = at.y + sin(angle) * radius * swell
            if (point == 0) shape.moveTo(px, py) else shape.lineTo(px, py)
        }
        shape.close()
        drawPath(
            shape,
            Brush.radialGradient(
                0f to state.palette.cycled(planetTint + genes.walk, value = 0.85f),
                1f to state.palette.cycled(planetTint + 0.2f + genes.walk, value = 0.15f),
                center = Offset(at.x - radius * 0.35f, at.y - radius * 0.35f),
                radius = radius * 1.4f,
            ),
        )
        drawPath(shape, state.palette.cap.copy(alpha = 0.35f), style = Stroke((size.minDimension * 0.004f).coerceAtLeast(1f)))
    }

    override fun onReset() {
        seeded = false
        roll = 0f
        moved = 0f
        laneX = 0f
        laneY = 0f
        look = 0f
        rig.reset()
        planet = -1f
        phrasesSeen = 0
        asteroids.clear()
        rockCredit = 0f
    }
}

/**
 * The same stars with the frame coming back larger, so every streak keeps stretching outward. The
 * zoom centre wanders and a half-turned copy of the echo lays the streaks into a lattice.
 */
internal class Hyperdrive : Starfield(
    name = "Hyperdrive",
    bloom = 2,
    count = 3200,
    bucket = VizEnergy.High,
    family = VizFamily.Acid,
    seed = 713L,
    moodSpec = MoodSpec(
        calmTrail = 0.9f,
        livelyTrail = 0.84f,
        calmZoom = 1.012f,
        livelyZoom = 1.05f,
    ),
) {
    private var wander = 0f

    override fun onAdvance(state: VizRenderState) {
        wander += state.deltaSeconds * 0.4f * state.tempo
    }

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(
            zoomX = base.zoomX,
            spin = base.spin,
            centreX = 0.5f + 0.25f * sin(wander),
            centreY = 0.5f + 0.2f * cos(wander * 0.8f),
            copy = EchoCopy(angle = PI.toFloat(), share = 0.22f),
        )
    }

    override fun onReset() {
        super.onReset()
        wander = 0f
    }
}

/**
 * The starfield slowed right down, with fourteen hundred round stars instead of streaks, a cloud of
 * light drifting round the sky, a slow planet and no cuts. It is the drawing for the two minutes of an album where nothing much happens, and it would
 * be wrong under a drum break.
 */
internal class Drift : Starfield(
    name = "Drift",
    trail = 0.62f,
    bucket = VizEnergy.Calm,
    speedScale = 0.65f,
    streakScale = 0f,
    seed = 712L,
    count = 1400,
    cuts = false,
) {
    // A cloud of light on a slow circle, so the sky is lit somewhere else every few seconds.
    private val stage = Stage(reachX = 0.3f, reachY = 0.22f, start = 1.2f)
    private val nebula = TriangleMesh(maxVertices = 32)

    override fun onAdvance(state: VizRenderState) {
        stage.advance(state.deltaSeconds)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        nebula.clear()
        val colour = state.palette.argb(0.7f + genes.walk, saturation = 0.6f, value = 0.8f, alpha = 0.5f * (0.4f + 0.6f * state.lift))
        nebula.glow(stage.x * size.width, stage.y * size.height, size.minDimension * 0.5f, colour, sides = 24)
        drawMesh(nebula, BlendMode.Plus)
    }

    override fun onReset() {
        super.onReset()
        stage.reset()
    }
}

/**
 * A landscape built from the last few seconds of spectrum, flown over. Every row of ground is one
 * snapshot, so the mountains ahead are the music coming up and the ones below already played. Around it:
 * cloud lines rushing overhead, a flock crossing the sky that turns on the snare, a sun swinging across once a phrase, light towers on
 * the peaks in loud passages, water that mirrors the sky, a wave across it on every kick, and a drop that
 * drains the water and lights every peak.
 */
internal class Terrain : Layered(
    name = "Terrain",
    family = VizFamily.Immersion,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 721L, detailKind = DetailKind.Specks, detailStrength = 0.4f, camera = stillCamera(721L)),
) {
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false

    private val reliefGene = genes.number("Relief", 0.5f, 1.6f, 1f)
    private val waterRule = genes.choice("Water rule", 2)
    private val flockSize = genes.choice("Flock", 3, start = 2)
    private val towers = genes.toggle("Towers", start = true)

    private val rows = 46
    private val columns = 40
    private val spacing = 1.15f
    private val across = 26f

    private val scene = Scene3D()
    private val path = Path()
    private val height = Array(rows) { FloatArray(columns) }
    private val rowTint = FloatArray(rows)
    private var writeSlot = 0
    private var travel = 0f
    private val rig = CameraRig(topSpeed = 22f, restSpeed = 0.6f, shove = 5f, sway = 6f, swayUp = 0f, lean = 0.2f, baseFov = 70f, seed = 5_501)
    private val lift = Spring(stiffness = 45f, damping = 0.7f)

    private val water = VizParam("Water", -1f, 1f, 0f)
    private val relief = VizParam("Height", 0.3f, 2f, 1f)
    override val params: List<VizParam> = listOf(water, relief)

    private val pointX = FloatArray(rows * columns)
    private val pointY = FloatArray(rows * columns)
    private val pointOk = BooleanArray(rows * columns)
    private val rowFog = FloatArray(rows)
    private val pointHeight = FloatArray(rows * columns)
    private val pointWater = BooleanArray(rows * columns)
    private val mesh = TriangleMesh(maxVertices = rows * columns, maxIndices = rows * columns * 6)
    private val vertexOf = IntArray(rows * columns)
    private var waterLevel = 1f

    private val flock = Swarm(40, 2_721L)
    private var look = 0f
    private var cloudTravel = 0f
    private var flockTravel = 0f
    private var flockWay = 1f
    private val birdMesh = TriangleMesh(maxVertices = 40 * 8 + 8)
    private var waveAt = -1f
    private var drainHold = 0f
    private val drained = Envelope(attackPerSecond = 3f, releasePerSecond = 1f)
    private val spray = Sprites(160, 3_721L)
    private val comets = Comets(size = 0.025f)
    private val skyStars = Sprites(120, 4_721L)
    private var starCredit = 0f
    private var hueShift = 0f
    private val peakX = FloatArray(PEAKS)
    private val peakY = FloatArray(PEAKS)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val moved = rig.advance(state)
        travel += moved
        cloudTravel = (cloudTravel + moved) % (CLOUDS * CLOUD_GAP)
        look += dt * TAU / 16f
        while (travel >= spacing) {
            travel -= spacing
            writeSlot = (writeSlot - 1 + rows) % rows
            capture(writeSlot, state)
        }
        // The camera climbs through a loud section and settles back in a quiet one.
        lift.target = 2.4f + 2.6f * state.frame.loudLong
        lift.advance(dt)
        if (gestures.phrase) {
            reliefGene.target = 0.5f + 1.1f * random.next()
            waterRule.choose(1 - waterRule.value)
            hueShift += 0.25f
        }
        if (gestures.drop) drainHold = gestures.barSeconds
        drainHold -= dt
        drained.advance(if (drainHold > 0f) 1f else 0f, dt)
        if (gestures.kickHit > 0f) {
            waveAt = 0f
            spray.burst(0.2f + 0.6f * random.next(), 0.8f, 24, 0.25f, 0.8f, 0.01f, 0.6f, Sprite.GLOW, UP, 1f)
        }
        if (waveAt >= 0f) {
            waveAt += dt / (gestures.beatSeconds * 2f)
            if (waveAt > 1.2f) waveAt = -1f
        }
        spray.advance(dt, drag = 0.5f, gravity = 0.5f)
        // The flock crosses the sky, turning back on the snare.
        if (gestures.snareHit > 0f) flockWay = -flockWay
        flockTravel += dt / (gestures.barSeconds * 2f) * flockWay
        flock.targetX = -0.1f + 1.2f * wrap(flockTravel)
        flock.targetY = 0.2f + 0.06f * sin(flockTravel * TAU * 2f)
        flock.advance(dt, speed = 0.45f + 0.4f * state.drive)
        var sumX = 0f
        var sumY = 0f
        val birds = flockSize.count(16, 12)
        for (index in 0 until birds) {
            sumX += flock.x[index]
            sumY += flock.y[index]
        }
        kit.place(1, sumX / birds, sumY / birds)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        starCredit += dt * (80f + 120f * state.air)
        while (starCredit >= 1f) {
            starCredit -= 1f
            skyStars.burst(random.next(), 0.02f + 0.4f * random.next(), 1, 0.01f, 1.2f, 0.014f, random.next(), Sprite.SPARK)
        }
        skyStars.advance(dt, drag = 0.5f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        // The sky, with the water below mirroring it.
        val hue = state.musicTime * 0.035f + state.frame.keyHue * state.frame.keyConfidence + hueShift
        drawRect(Brush.verticalGradient(
            0f to state.palette.cycled(hue + 0.62f, value = 0.015f + 0.08f * state.energy),
            0.48f to state.palette.cycled(hue + 0.4f, value = 0.12f + 0.25f * state.body),
            1f to state.palette.cycled(hue + 0.7f, value = 0.025f + 0.1f * state.energy),
        ))
        // A sun swinging across the sky once a phrase.
        val swing = TAU * gestures.phrasePhase
        val sun = Offset((0.5f + 0.35f * sin(swing)) * size.width, (0.16f + 0.06f * cos(swing)) * size.height)
        val glow = size.minDimension * 0.18f
        drawCircle(Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.35f + 0.3f * state.lift), 1f to Color.Transparent, center = sun, radius = glow), glow, sun)
        drawCircle(state.palette.cap.copy(alpha = 0.8f), size.minDimension * 0.045f, sun)

        var total = 0f
        for (row in height) for (value in row) total += value
        val meanHeight = total / (rows * columns)
        val loudness = if (waterRule.value == 0) state.frame.loudLong else 0.5f
        waterLevel = meanHeight * (1.15f - 0.95f * loudness + water.value) * (1f - drained.value)
        scene.lens(size, fovDegrees = rig.fov, near = 0.6f, far = rows * spacing)
        scene.camera(
            eyeX = rig.eyeX,
            eyeY = lift.value.coerceIn(1.4f, 6f),
            eyeZ = 0f,
            targetX = rig.eyeX * 0.3f + 2.5f * sin(look * 0.7f),
            targetY = 1.1f,
            targetZ = -spacing * 10f,
            // A slow lean of the horizon, so the sky is not always the top of the picture.
            roll = rig.roll + 0.25f * sin(look),
        )
        // A ceiling of cloud lines rushing overhead at the speed of the flight.
        val cloudWidth = (size.minDimension * 0.006f).coerceAtLeast(1f)
        for (line in 0 until CLOUDS) {
            val distance = 1.5f + ((line * CLOUD_GAP - cloudTravel) % (CLOUDS * CLOUD_GAP) + CLOUDS * CLOUD_GAP) % (CLOUDS * CLOUD_GAP)
            val fog = scene.fog(distance)
            if (fog <= 0.02f || !scene.project(-across, CLOUD_HEIGHT, -distance)) continue
            val fromX = scene.screenX
            val fromY = scene.screenY
            if (!scene.project(across, CLOUD_HEIGHT, -distance)) continue
            val colour = state.palette.cycled(hue + 0.55f + line * 0.07f, value = 0.6f + 0.4f * fog, alpha = (0.15f + 0.45f * fog) * (0.4f + 0.6f * state.lift))
            drawLine(colour, Offset(fromX, fromY), Offset(scene.screenX, scene.screenY), cloudWidth * (0.5f + fog), StrokeCap.Round)
        }
        for (row in 0 until rows) {
            val slot = (writeSlot + row) % rows
            // Held well in front of the eye: a row level with the camera projects to nonsense.
            val distance = 2.2f + row * spacing - travel
            rowFog[row] = scene.fog(distance)
            val heights = height[slot]
            val base = row * columns
            for (column in 0 until columns) {
                val worldX = (column.toFloat() / (columns - 1) - 0.5f) * across
                val raw = heights[column]
                val drowned = raw < waterLevel
                pointHeight[base + column] = raw
                pointWater[base + column] = drowned
                val ok = scene.project(worldX, if (drowned) waterLevel else raw, -distance)
                pointOk[base + column] = ok
                if (ok) {
                    pointX[base + column] = scene.screenX
                    pointY[base + column] = scene.screenY
                }
            }
        }
        drawGround(state, hue)
        // Contour lines over the solid ground, far to near.
        for (row in rows - 1 downTo 0 step 2) {
            val fog = rowFog[row]
            if (fog <= 0.02f) continue
            val base = row * columns
            path.reset()
            var started = false
            for (column in 0 until columns) {
                val at = base + column
                if (!pointOk[at]) {
                    started = false
                    continue
                }
                if (started) path.lineTo(pointX[at], pointY[at]) else path.moveTo(pointX[at], pointY[at])
                started = true
            }
            drawPath(path, state.palette.cycled(rowTint[(writeSlot + row) % rows], value = 0.35f + 0.65f * fog, alpha = fog * 0.6f), style = Stroke((size.minDimension * 0.007f * fog).coerceAtLeast(1f)))
        }
        drawTowers(state)
    }

    // Beams from the highest peaks in loud passages, and from every peak on a drop.
    private fun DrawScope.drawTowers(state: VizRenderState) {
        val draining = drained.value
        // The towers fade in as the drive rises rather than switching on at one level.
        val glow = maxOf(draining, if (towers.on) (state.drive - 0.3f) * 2f else 0f).coerceIn(0f, 1f)
        if (glow <= 0f) return
        peakY.fill(Float.MAX_VALUE)
        val wanted = if (draining > 0.05f) PEAKS else 3
        for (row in 2 until rows / 2) {
            val base = row * columns
            for (column in 1 until columns - 1) {
                val at = base + column
                if (!pointOk[at] || pointWater[at]) continue
                if (pointHeight[at] < pointHeight[at - 1] || pointHeight[at] < pointHeight[at + 1]) continue
                // Keep the highest on screen, which are the smallest screen y.
                var worst = 0
                for (slot in 1 until wanted) if (peakY[slot] > peakY[worst]) worst = slot
                if (pointY[at] < peakY[worst]) {
                    peakY[worst] = pointY[at]
                    peakX[worst] = pointX[at]
                }
            }
        }
        val beam = size.minDimension * 0.012f
        for (slot in 0 until wanted) {
            if (peakY[slot] == Float.MAX_VALUE) continue
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to state.palette.cap.copy(alpha = 0.5f * glow), startY = 0f, endY = peakY[slot]),
                topLeft = Offset(peakX[slot] - beam / 2f, 0f),
                size = androidx.compose.ui.geometry.Size(beam, peakY[slot]),
                blendMode = BlendMode.Plus,
            )
            drawCircle(state.palette.cap.copy(alpha = glow), beam, Offset(peakX[slot], peakY[slot]))
        }
    }

    /**
     * The ground as one solid surface, far to near in one call. Land is lit from the side, peaks catch
     * more light, and water takes the sky's own colours and ripples, brightest where a kick's wave is.
     */
    private fun DrawScope.drawGround(state: VizRenderState, hue: Float) {
        mesh.clear()
        vertexOf.fill(-1)
        val shimmer = state.musicTime * 5f
        val light = 0.3f + 0.7f * state.drive
        for (row in 0 until rows) {
            val fog = rowFog[row]
            if (fog <= 0.02f) continue
            val slot = (writeSlot + row) % rows
            val base = row * columns
            for (column in 0 until columns) {
                val at = base + column
                if (!pointOk[at]) continue
                val colour = if (pointWater[at]) {
                    val ripple = 0.5f + 0.5f * sin(shimmer + column * 0.7f + row * 0.4f)
                    val wave = if (waveAt >= 0f) (1f - abs(column.toFloat() / columns - waveAt) / 0.08f).coerceIn(0f, 1f) else 0f
                    state.palette.cycled(hue + 0.4f, saturation = 0.55f, value = ((0.16f + 0.3f * ripple + 0.2f * state.energy) * light + 0.4f * wave).coerceIn(0f, 1f), alpha = fog)
                } else {
                    val beside = if (column + 1 < columns) pointHeight[at + 1] else pointHeight[at]
                    val slope = ((pointHeight[at] - beside) * 0.6f + 0.5f).coerceIn(0f, 1f)
                    val peak = (pointHeight[at] / 3.4f).coerceIn(0f, 1f)
                    state.palette.cycled(rowTint[slot], saturation = 0.75f, value = (0.12f + 0.45f * peak + 0.35f * slope) * light, alpha = fog)
                }
                vertexOf[at] = mesh.vertex(pointX[at], pointY[at], colour.toArgb())
            }
        }
        for (row in rows - 2 downTo 0) {
            val here = row * columns
            val there = (row + 1) * columns
            for (column in 0 until columns - 1) {
                val a = vertexOf[here + column]
                val b = vertexOf[here + column + 1]
                val c = vertexOf[there + column + 1]
                val d = vertexOf[there + column]
                if (a < 0 || b < 0 || c < 0 || d < 0) continue
                mesh.quad(a, b, c, d)
            }
        }
        drawMesh(mesh)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        birdMesh.clear()
        val colour = state.palette.argb(genes.walk + 0.1f, saturation = 0.2f, value = 0.9f, alpha = 0.85f)
        for (index in 0 until flockSize.drawn(16, 12)) {
            val presence = flockSize.presence(index, 16, 12)
            if (presence <= 0.01f) continue
            val heading = kotlin.math.atan2(flock.vy[index], flock.vx[index])
            birdMesh.glyph(2, flock.x[index] * size.width, flock.y[index] * size.height, size.minDimension * 0.016f, heading + PI.toFloat() / 2f, colour)
        }
        drawMesh(birdMesh)
        with(spray) { drawSprites(state.palette, genes.walk, saturation = 0.3f) }
        with(skyStars) { drawSprites(state.palette, genes.walk, alpha = 0.7f, saturation = 0.3f) }
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    private fun capture(slot: Int, state: VizRenderState) {
        val bands = state.frame.bandsRel
        val target = height[slot]
        for (column in 0 until columns) {
            // Mirrored with the bass in the middle, straight ahead, and the treble out at the edges.
            val fromMiddle = abs(column.toFloat() / (columns - 1) - 0.5f) * 2f
            target[column] = (if (bands.isEmpty()) 0f else bands.sampleAt(fromMiddle)) * 3.4f * relief.value * reliefGene.value * (0.55f + 0.65f * state.drive)
        }
        rowTint[slot] = state.musicTime * 0.24f + hueShift
    }

    override fun onReset() {
        for (row in height) row.fill(0f)
        rowTint.fill(0f)
        writeSlot = 0
        travel = 0f
        rig.reset()
        lift.reset()
        flock.scatter()
        flockTravel = 0f
        flockWay = 1f
        waveAt = -1f
        drainHold = 0f
        drained.reset()
        spray.clear()
        hueShift = 0f
        look = 0f
        cloudTravel = 0f
        comets.clear()
        skyStars.clear()
        starCredit = 0f
    }

    private companion object {
        const val PEAKS = 8
        const val CLOUDS = 14
        const val CLOUD_GAP = 3f
        const val CLOUD_HEIGHT = 9f
    }
}

/**
 * A solid turning in a room, orbiting and tumbling, with its dual inside turning the other way, the two
 * trading shapes over each phrase. Three satellites circle it and jump on the snare, sparks fly off its
 * corners on the kick, and a drop blows it apart and puts it back together over a bar.
 *
 * The outer shape is an icosahedron; its dual, the dodecahedron, has a corner at the middle of each of
 * its faces.
 */
internal class Wireframe : Layered(
    name = "Wireframe",
    family = VizFamily.Immersion,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 731L, groundKind = GroundKind.Grid, groundDim = 0.8f, detailKind = DetailKind.Scan, camera = stillCamera(731L)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.5f)
    override val cameraOnEcho: Boolean get() = false

    private val blend = genes.number("Shape blend", 0f, 1f, 0f)
    private val satellites = genes.choice("Satellites", 3, start = 2)
    private val room = genes.toggle("Hatched room", start = false)
    private val edgeGlow = genes.number("Edge glow", 0.3f, 1.2f, 0.7f)

    private val scene = Scene3D()
    private val ico: Array<FloatArray>
    private val icoEdges: Array<IntArray>
    private val dual: Array<FloatArray>
    private val dualEdges: Array<IntArray>
    private val screenX = FloatArray(20)
    private val screenY = FloatArray(20)
    private val visible = BooleanArray(20)
    private var spinX = 0f
    private var spinY = 0f
    private var orbit = 0f
    private val swell = Spring(stiffness = 190f, damping = 0.42f)
    private var explode = 0f
    private val satPhase = FloatArray(3) { it * TAU / 3f }
    private val sparks = Sprites(240, 1_731L)
    private val comets = Comets()
    private val satMesh = TriangleMesh(maxVertices = 3 * 8 + 8)

    init {
        val phi = 1.618034f
        ico = arrayOf(
            floatArrayOf(-1f, phi, 0f), floatArrayOf(1f, phi, 0f),
            floatArrayOf(-1f, -phi, 0f), floatArrayOf(1f, -phi, 0f),
            floatArrayOf(0f, -1f, phi), floatArrayOf(0f, 1f, phi),
            floatArrayOf(0f, -1f, -phi), floatArrayOf(0f, 1f, -phi),
            floatArrayOf(phi, 0f, -1f), floatArrayOf(phi, 0f, 1f),
            floatArrayOf(-phi, 0f, -1f), floatArrayOf(-phi, 0f, 1f),
        )
        icoEdges = arrayOf(
            intArrayOf(0, 1), intArrayOf(0, 5), intArrayOf(0, 7), intArrayOf(0, 10), intArrayOf(0, 11),
            intArrayOf(1, 5), intArrayOf(1, 7), intArrayOf(1, 8), intArrayOf(1, 9),
            intArrayOf(2, 3), intArrayOf(2, 4), intArrayOf(2, 6), intArrayOf(2, 10), intArrayOf(2, 11),
            intArrayOf(3, 4), intArrayOf(3, 6), intArrayOf(3, 8), intArrayOf(3, 9),
            intArrayOf(4, 5), intArrayOf(4, 9), intArrayOf(4, 11),
            intArrayOf(5, 9), intArrayOf(5, 11),
            intArrayOf(6, 7), intArrayOf(6, 8), intArrayOf(6, 10),
            intArrayOf(7, 8), intArrayOf(7, 10),
            intArrayOf(8, 9), intArrayOf(10, 11),
        )
        // The faces are the triples whose three pairs are all edges; their middles are the dual's corners.
        val linked = Array(12) { BooleanArray(12) }
        for (edge in icoEdges) {
            linked[edge[0]][edge[1]] = true
            linked[edge[1]][edge[0]] = true
        }
        val faces = ArrayList<IntArray>()
        for (a in 0 until 12) for (b in a + 1 until 12) for (c in b + 1 until 12) {
            if (linked[a][b] && linked[b][c] && linked[a][c]) faces += intArrayOf(a, b, c)
        }
        val reach = sqrt(1f + phi * phi)
        dual = Array(faces.size) { index ->
            val face = faces[index]
            val mx = (ico[face[0]][0] + ico[face[1]][0] + ico[face[2]][0]) / 3f
            val my = (ico[face[0]][1] + ico[face[1]][1] + ico[face[2]][1]) / 3f
            val mz = (ico[face[0]][2] + ico[face[1]][2] + ico[face[2]][2]) / 3f
            val length = sqrt(mx * mx + my * my + mz * mz)
            floatArrayOf(mx / length * reach, my / length * reach, mz / length * reach)
        }
        val pairs = ArrayList<IntArray>()
        for (a in faces.indices) for (b in a + 1 until faces.size) {
            var shared = 0
            for (corner in faces[a]) if (corner in faces[b]) shared++
            if (shared == 2) pairs += intArrayOf(a, b)
        }
        dualEdges = pairs.toTypedArray()
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        ground?.kind = if (room.on) GroundKind.Hatch else GroundKind.Grid
        spinX += dt * 1.4f * state.tempo * (0.5f + state.body)
        spinY += dt * 2f * state.tempo * (0.5f + state.air)
        orbit += dt * 0.5f * state.tempo
        swell.kick(gestures.kickHit * 6f)
        swell.advance(dt)
        if (gestures.drop) explode = 1f
        explode = (explode - dt / gestures.barSeconds).coerceAtLeast(0f)
        if (gestures.snareHit > 0f) for (index in 0 until 3) satPhase[index] += 1.2f
        for (index in 0 until 3) satPhase[index] += dt * (1.5f + 0.4f * index) * state.tempo
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        sparks.advance(dt, drag = 1.2f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        scene.lens(size, fovDegrees = 55f, near = 0.5f, far = 40f)
        scene.camera(0f, 0f, 0f, 0f, 0f, -1f, roll = sin(spinX * 0.3f) * 0.2f)
        val burst = 1f + 1.5f * sin(explode * PI.toFloat())
        val scale = 3.8f * (0.7f + 0.4f * state.lift + 0.22f * swell.value.coerceIn(-0.5f, 1.6f)) * burst
        val centreX = 6f * cos(orbit)
        val centreY = 2.6f * sin(orbit * 0.7f)
        val distance = 11f
        // The two shapes trade places over each phrase, the gene shifting where in the phrase it happens.
        val morph = 0.5f - 0.5f * cos(TAU * (gestures.phrasePhase + blend.value))
        val lift = 0.35f + 0.65f * state.lift
        solid(state, ico, icoEdges, scale, centreX, centreY, distance, spinX, spinY, (1f - morph) * lift, 0f)
        solid(state, dual, dualEdges, scale, centreX, centreY, distance, spinX, spinY, morph * lift, 0.5f)
        // The inner solid turns the other way at half the size.
        solid(state, if (morph > 0.5f) ico else dual, if (morph > 0.5f) icoEdges else dualEdges, scale * 0.45f, centreX, centreY, distance, -spinX * 1.3f, -spinY * 1.3f, 0.7f * lift, 0.25f)
        if (scene.project(centreX, centreY, -distance)) kit.place(1, scene.screenX / size.width, scene.screenY / size.height)
        // Satellites circling the solid.
        satMesh.clear()
        for (index in 0 until satellites.drawn(1)) {
            val presence = satellites.presence(index, 1)
            if (presence <= 0.01f) continue
            val a = satPhase[index]
            val sx = centreX + cos(a) * scale * 1.9f
            val sy = centreY + sin(a) * scale * 0.9f
            val sz = -distance + sin(a) * scale * 1.2f
            if (!scene.project(sx, sy, sz)) continue
            satMesh.glow(scene.screenX, scene.screenY, size.minDimension * 0.02f, state.palette.argb(index * 0.33f + genes.walk, value = 1f, alpha = presence * lift))
            kit.place(index + 2, scene.screenX / size.width, scene.screenY / size.height)
        }
        drawMesh(satMesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    private fun DrawScope.solid(
        state: VizRenderState,
        corners: Array<FloatArray>,
        lines: Array<IntArray>,
        scale: Float,
        centreX: Float,
        centreY: Float,
        distance: Float,
        turnX: Float,
        turnY: Float,
        alpha: Float,
        tint: Float,
    ) {
        if (alpha <= 0.01f) return
        val cosX = cos(turnX)
        val sinX = sin(turnX)
        val cosY = cos(turnY)
        val sinY = sin(turnY)
        for (index in corners.indices) {
            val vertex = corners[index]
            // About y, then about x: two steps show every face over time.
            val x1 = vertex[0] * cosY + vertex[2] * sinY
            val z1 = -vertex[0] * sinY + vertex[2] * cosY
            val y2 = vertex[1] * cosX - z1 * sinX
            val z2 = vertex[1] * sinX + z1 * cosX
            visible[index] = scene.project(centreX + x1 * scale, centreY + y2 * scale, z2 * scale - distance)
            if (visible[index]) {
                screenX[index] = scene.screenX
                screenY[index] = scene.screenY
            }
        }
        val width = (size.minDimension * 0.011f).coerceAtLeast(1.2f)
        for (edge in lines.indices) {
            val from = lines[edge][0]
            val to = lines[edge][1]
            if (!visible[from] || !visible[to]) continue
            val colour = state.palette.cycled(edge.toFloat() / lines.size + tint + genes.walk, value = (0.4f + 0.6f * state.energy).coerceIn(0f, 1f))
            val start = Offset(screenX[from], screenY[from])
            val end = Offset(screenX[to], screenY[to])
            drawLine(colour.copy(alpha = (alpha * 0.5f * edgeGlow.value).coerceIn(0f, 1f)), start, end, width * 4f, StrokeCap.Round)
            drawLine(colour.copy(alpha = (alpha * 0.9f).coerceIn(0f, 1f)), start, end, width, StrokeCap.Round)
        }
        // Sparks fly off the corners on the kick.
        if (gestures.kickHit > 0f && tint == 0f) {
            for (index in corners.indices step 3) {
                if (visible[index]) sparks.burst(screenX[index] / size.width, screenY[index] / size.height, 3, 0.4f, 0.6f, 0.01f, random.next(), Sprite.SPARK)
            }
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        spinX = 0f
        spinY = 0f
        orbit = 0f
        swell.reset()
        explode = 0f
        for (index in 0 until 3) satPhase[index] = index * TAU / 3f
        sparks.clear()
        comets.clear()
    }
}

/**
 * A run of gates to fly through, one a beat, over a grid floor under the stars. The gates carry
 * rippling membranes, three rival racers fly the course ahead of the camera and overtake on the snare,
 * the course banks hard enough that the gates cross the frame, a kick boosts, and a drop rolls the whole
 * course a full turn in a bar.
 */
internal class RingFlight : Layered(
    name = "Ring Flight",
    family = VizFamily.Immersion,
    bucket = VizEnergy.High,
    kit = Kit(seed = 741L, groundKind = GroundKind.Stars, detailKind = DetailKind.Specks, detailStrength = 0.5f, camera = stillCamera(741L)),
) {
    override val trail: Float get() = 0.35f
    override val cameraOnEcho: Boolean get() = false

    private val racers = genes.choice("Racers", 3, start = 2)
    private val membrane = genes.toggle("Membranes", start = true)
    private val bank = genes.number("Bank", 0.5f, 1.5f, 1f)
    private val gateShape = genes.choice("Gate shape", 3)

    private val gates = 18
    private val points = 30
    private val spacing = 3.4f
    private val scene = Scene3D()
    private val path = Path()
    private val gateRadius = FloatArray(gates) { 3.2f }
    private val gateOffsetX = FloatArray(gates)
    private val gateOffsetY = FloatArray(gates)
    private val gateTint = FloatArray(gates)
    private var writeSlot = 0
    private var travel = 0f
    private var course = 0f
    private var wobblePhase = 0f
    private val wander = Noise1(seed = 8_123)
    private val rig = CameraRig(topSpeed = 14.4f, restSpeed = 1.4f, shove = 9f, sway = 0f, lean = 0.26f, baseFov = 76f, seed = 8_123)
    private val racerAhead = FloatArray(3) { 6f + 4f * it }
    private val racerPush = FloatArray(3)
    private var rollExtra = 0f
    private var rollLeft = 0f
    private var look = 0f
    private val streamers = Sprites(240, 1_741L)
    private val comets = Comets()
    private val membraneMesh = TriangleMesh(maxVertices = gates * (32 + 2) + 16)
    private val racerMesh = TriangleMesh(maxVertices = 3 * 8 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        // One gate per beat when the tempo is known, so passing through one IS the beat.
        val cruise = if (state.frame.bpm > 0f && state.frame.beatConfidence > 0.4f) spacing * state.frame.bpm / 60f else -1f
        travel += rig.advance(state, cruise = cruise)
        wobblePhase += dt * 2f * state.tempo
        look += dt * TAU / 16f
        while (travel >= spacing) {
            travel -= spacing
            writeSlot = (writeSlot - 1 + gates) % gates
            course += 1.1f
            gateRadius[writeSlot] = 2.6f + 2.4f * state.bassMotion
            gateOffsetX[writeSlot] = wander.at(course * 0.2f) * 3.5f
            gateOffsetY[writeSlot] = wander.at(course * 0.16f + 30f) * 2.4f
            gateTint[writeSlot] = state.musicTime * 0.3f + genes.walk
            // Streamers peel off the edges of the frame as a gate is flown through.
            for (corner in 0 until 4) {
                val angle = corner * TAU / 4f + 0.785f
                streamers.burst(0.5f + cos(angle) * 0.45f, 0.5f + sin(angle) * 0.45f, 12, 0.4f, 0.6f, 0.012f, gateTint[writeSlot], Sprite.STREAK, angle, 0.5f)
            }
        }
        streamers.advance(dt, drag = 0.6f)
        if (gestures.snareHit > 0f) racerPush[(random.next() * racers.count(1)).toInt().coerceIn(0, 2)] = -3f
        for (index in 0 until 3) {
            racerPush[index] += (0f - racerPush[index]) * (dt * 1.5f).coerceAtMost(1f)
            racerAhead[index] = 6f + 4f * index + 2f * sin(wobblePhase * 0.5f + index * 2f) + racerPush[index]
        }
        if (gestures.drop) rollLeft = gestures.barSeconds
        if (rollLeft > 0f) {
            rollLeft -= dt
            rollExtra += dt * TAU / gestures.barSeconds
        }
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val aim = (writeSlot + 2) % gates
        scene.lens(size, fovDegrees = rig.fov, near = 0.5f, far = gates * spacing)
        // Banked three times harder than the rig alone, so the gates swing across the frame.
        val roll = rig.roll * 3f * bank.value + sin(course * 0.3f) * 0.35f * bank.value + rollExtra
        scene.camera(
            eyeX = gateOffsetX[(writeSlot + gates - 1) % gates] * 0.25f + rig.nudgeX,
            eyeY = gateOffsetY[(writeSlot + gates - 1) % gates] * 0.25f,
            eyeZ = 0f,
            // A slow look round, so the course swings across the frame.
            targetX = gateOffsetX[aim] * 0.5f + 2.2f * sin(look),
            targetY = gateOffsetY[aim] * 0.5f + 1.2f * cos(look * 0.8f),
            targetZ = -spacing * 3f,
            roll = roll,
        )
        val lift = 0.4f + 0.6f * state.lift
        drawFloor(state, lift)
        val shared = membrane.weight(1)
        for (index in gates - 1 downTo 0) {
            val slot = (writeSlot + index) % gates
            val distance = 1f + index * spacing - travel
            val fog = scene.fog(distance)
            if (fog <= 0.02f) continue
            val radius = gateRadius[slot]
            val centreX = gateOffsetX[slot]
            val centreY = gateOffsetY[slot]
            val spin = distance * 0.1f + wobblePhase * 0.15f
            val corners = when (gateShape.value) {
                0 -> points
                1 -> 6
                else -> 4
            }
            if (shared > 0.01f && fog > 0.2f && scene.project(centreX, centreY, -distance)) {
                // A thin membrane across the gate that ripples as the course plays.
                membraneMesh.clear()
                val middle = membraneMesh.vertex(scene.screenX, scene.screenY, state.palette.argb(gateTint[slot], value = 1f, alpha = 0.04f * fog * shared))
                var previous = -1
                for (point in 0..corners) {
                    val angle = TAU * point / corners + spin
                    val ripple = 0.5f + 0.5f * sin(angle * 5f + wobblePhase * 2f)
                    if (!scene.project(centreX + cos(angle) * radius, centreY + sin(angle) * radius, -distance)) {
                        previous = -1
                        continue
                    }
                    val rim = membraneMesh.vertex(scene.screenX, scene.screenY, state.palette.argb(gateTint[slot] + 0.1f, value = 1f, alpha = (0.1f + 0.14f * ripple) * fog * shared))
                    if (previous >= 0) membraneMesh.triangle(middle, previous, rim)
                    previous = rim
                }
                drawMesh(membraneMesh)
            }
            path.reset()
            var started = false
            for (point in 0..corners) {
                val angle = TAU * point / corners + spin
                val wobble = 1f + (0.08f + 0.16f * state.body) * sin(angle * 5f + wobblePhase)
                if (!scene.project(centreX + cos(angle) * radius * wobble, centreY + sin(angle) * radius * wobble, -distance)) {
                    started = false
                    continue
                }
                if (started) path.lineTo(scene.screenX, scene.screenY) else path.moveTo(scene.screenX, scene.screenY)
                started = true
            }
            if (fog > 0.2f) {
                drawPath(path, state.palette.cycled(gateTint[slot], value = 0.5f + 0.5f * fog, alpha = fog * 0.4f * lift), style = Stroke((size.minDimension * 0.036f * fog).coerceAtLeast(1.5f)))
            }
            drawPath(path, state.palette.cycled(gateTint[slot], value = 0.7f + 0.3f * fog, alpha = (fog * lift).coerceIn(0f, 1f)), style = Stroke((size.minDimension * 0.01f * fog).coerceAtLeast(1f)))
        }
        // The rival racers, whose glow the echo stretches into trails.
        racerMesh.clear()
        for (index in 0 until racers.drawn(1)) {
            val presence = racers.presence(index, 1)
            if (presence <= 0.01f) continue
            val ahead = racerAhead[index]
            val slot = (writeSlot + (ahead / spacing).toInt().coerceIn(0, gates - 1)) % gates
            val rx = gateOffsetX[slot] + sin(wobblePhase + index * 2.1f) * 1.4f
            val ry = gateOffsetY[slot] + cos(wobblePhase * 0.8f + index) * 1f
            if (!scene.project(rx, ry, -ahead)) continue
            val fog = scene.fog(ahead)
            racerMesh.glow(scene.screenX, scene.screenY, size.minDimension * 0.03f * (0.5f + fog), state.palette.argb(index * 0.33f + genes.walk, value = 1f, alpha = presence * lift))
            kit.place(index + 1, scene.screenX / size.width, scene.screenY / size.height)
        }
        drawMesh(racerMesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    // A grid floor under the course, in perspective, fading into the distance.
    private fun DrawScope.drawFloor(state: VizRenderState, lift: Float) {
        val floor = -4.5f
        val far = gates * spacing
        val colour = state.palette.mid
        val line = (size.minDimension * 0.004f).coerceAtLeast(1f)
        val shift = travel % 2f
        var z = -2f + shift
        while (z > -far) {
            val fog = scene.fog(-z)
            if (scene.project(-30f, floor, z)) {
                val fromX = scene.screenX
                val fromY = scene.screenY
                if (scene.project(30f, floor, z)) {
                    drawLine(colour.copy(alpha = (0.4f * fog * lift).coerceIn(0f, 1f)), Offset(fromX, fromY), Offset(scene.screenX, scene.screenY), line)
                }
            }
            z -= 2f
        }
        for (column in -6..6) {
            val x = column * 5f
            if (!scene.project(x, floor, -2f)) continue
            val fromX = scene.screenX
            val fromY = scene.screenY
            if (!scene.project(x, floor, -far)) continue
            drawLine(colour.copy(alpha = (0.3f * lift).coerceIn(0f, 1f)), Offset(fromX, fromY), Offset(scene.screenX, scene.screenY), line)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(streamers) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        writeSlot = 0
        travel = 0f
        course = 0f
        wobblePhase = 0f
        gateRadius.fill(3.2f)
        gateOffsetX.fill(0f)
        gateOffsetY.fill(0f)
        gateTint.fill(0f)
        rig.reset()
        racerPush.fill(0f)
        rollExtra = 0f
        rollLeft = 0f
        look = 0f
        streamers.clear()
        comets.clear()
    }
}
