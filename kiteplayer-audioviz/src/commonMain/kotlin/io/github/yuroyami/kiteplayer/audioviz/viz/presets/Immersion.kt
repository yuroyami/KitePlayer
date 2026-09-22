package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.CameraRig
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoCopy
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The flat camera that goes with a drawing flown by its own camera in three dimensions: it only punches. */
private fun stillCamera(seed: Long): Camera2D = Camera2D(wander = 0f, punch = 0.03f, roll = 0f, shake = 0f, cuts = false, seed = seed.toInt())

/**
 * A configurable flight through stars, with short luminous dashes by default, a drifting cloud,
 * planets and asteroids. Speed, dot shape and echoes can change without selecting another pattern.
 */
internal class Drift : Layered(
    name = "Drift",
    family = VizFamily.Immersion,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 712L, groundKind = GroundKind.Cloud, groundDim = 0.8f, detailKind = DetailKind.Specks, detailStrength = 0.5f, camera = stillCamera(712L)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Bass, VizProperty.Shape),
        VizDrive(VizDriver.Timbre, VizProperty.Size),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        // Camera nudges remain active; the separate cuts control permits larger lane jumps.
        VizDrive(VizDriver.BodyHit, VizProperty.Camera, VizCurve.Scaled),
        VizDrive(VizDriver.Section, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        echoes = true,
        softBuffer = true,
    )
    override val cameraOnEcho: Boolean get() = false

    private val starDensity = genes.number("Star density", 0.5f, 1f, 0.85f)
    private val planetRate = genes.choice("Planets", 3, start = 1)
    private val asteroidRate = genes.number("Asteroids", 0.5f, 2f, 1f)
    private val bank = genes.number("Bank", 0f, 0.6f, 0.3f)

    private val count = 2400
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
    private val rig = CameraRig(topSpeed = 48.5f, restSpeed = 1f, shove = 14f, sway = 0.4f, lean = 0.25f, baseFov = 82f, cuts = false, seed = 3_331)

    private val speedParam = VizParam("Speed", 0.1f, 3f, 0.65f)
    private val streakParam = VizParam("Dot length", 0f, 3f, 0.35f)
    private val sizeParam = VizParam("Dot size", 0.4f, 2f, 1f)
    private val countParam = VizParam("Dot count", 200f, count.toFloat(), 1400f).apply { step = 100f }
    private val cutsParam = VizParam("Camera cuts", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val trailParam = VizParam("Echo trail", 0f, 0.94f, 0.62f)
    private val zoomParam = VizParam("Echo expansion", -1f, 1f, 0f)
    private val glowParam = VizParam("Echo glow", 0f, 2f, 0f).apply { step = 1f }
    private val wanderParam = VizParam("Echo drift", 0f, 1f, 0f)
    private val copyParam = VizParam("Rotated echo", 0f, 0.4f, 0f)
    override val params: List<VizParam> = listOf(
        speedParam, streakParam, sizeParam, countParam, cutsParam, trailParam,
        zoomParam, glowParam, wanderParam, copyParam,
    )
    override val trail: Float get() = trailParam.value
    override val feedbackZoom: Float get() = 1f + zoomParam.value * 0.05f
    override val bloom: Int get() = glowParam.value.roundToInt()

    private var wander = 0f
    private val stage = Stage(reachX = 0.3f, reachY = 0.22f, start = 1.2f)
    private val nebula = TriangleMesh(maxVertices = 32)

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(
            zoomX = base.zoomX,
            spin = base.spin,
            centreX = 0.5f + 0.25f * sin(wander) * wanderParam.value,
            centreY = 0.5f + 0.2f * cos(wander * 0.8f) * wanderParam.value,
            copy = if (copyParam.value > 0f) EchoCopy(angle = PI.toFloat(), share = copyParam.value) else null,
        )
    }

    // A glowing dot uses at most twelve vertices, including its core. All 2400 fit in one mesh.
    private val mesh = TriangleMesh(maxVertices = count * 12)
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

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (!seeded) {
            for (index in 0 until count) respawn(index, anywhere = true)
            seeded = true
        }
        // Nothing playing, nothing flying. Without this the stars cross the screen in a silence
        // at the rig's rest speed, which is the picture moving on its own.
        val cruise = if (gestures.silence) 0f else -1f
        rig.cutsEnabled = cutsParam.value >= 0.5f
        moved = rig.advance(state, speedParam.value, cruise)
        roll += dt * state.paced(0.55f) * 0.5f
        look += dt * TAU / 16f
        // A snare cuts to a new lane: the camera jumps sideways rather than sliding there.
        if (gestures.snare > 0f && cutsParam.value >= 0.5f) {
            val aside = 0.4f + 0.6f * gestures.snare
            laneX = random.signed() * 3f * aside
            laneY = random.signed() * 2f * aside
        }
        for (index in 0 until count) {
            z[index] += moved
            if (z[index] > -0.6f) respawn(index, anywhere = false)
        }
        // A planet every four, two or one phrases, and a drop sends one right through the frame.
        if (gestures.section) {
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
            planet += dt / (gestures.cycleSeconds * if (planetBig) 1f else 3f) * speedParam.value.coerceAtLeast(0.3f)
            if (planet > 1f) planet = -1f
        }
        // Asteroids tumble across, one a bar at least and more when the music drives.
        rockCredit += dt * asteroidRate.value * state.drive / gestures.beatSeconds * 0.5f
        val flown = asteroids.anyNewest && asteroids.progress[asteroids.newest] > 0.6f
        if (!asteroids.anyNewest || ((gestures.section || rockCredit >= 1f) && flown)) {
            rockCredit = 0f
            asteroids.across(random, gestures.cycleSeconds * 0.7f / speedParam.value.coerceAtLeast(0.6f), PathShape.Line, 0f, 0.025f + 0.03f * random.next(), random.next(), random.signed() * 3f, Sprite.HEX)
        }
        asteroids.advance(dt)
        kit.follow(0, asteroids)
        if (planet >= 0f) kit.place(1, planetX(), planetY)
        stage.advance(state.deltaSeconds * state.idle)
        wander += state.stepSeconds * 0.4f * state.tempo
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
        val lift = 0.1f + 1.2f * state.lift
        val live = (countParam.value * starDensity.value * (0.55f + 0.45f * state.texture)).toInt().coerceIn(40, count)
        // A reference exposure keeps dots equally long at 60 Hz and 120 Hz.
        val exposure = if (state.deltaSeconds > 0f) moved / state.deltaSeconds / 60f else 0f
        val streak = ((0.25f + exposure * (4f + 4f * state.bassMotion)) * streakParam.value).coerceIn(0f, 7f)
        val round = streakParam.value <= 0.01f
        mesh.clear()
        for (index in 0 until live) {
            if (!scene.project(x[index], y[index], z[index])) continue
            val headX = scene.screenX
            val headY = scene.screenY
            val fog = scene.fog(-z[index])
            val colour = state.palette.argb(tint[index], saturation = 0.75f, value = 1f, alpha = (0.5f + 0.5f * fog) * lift)
            val dotSize = sizeParam.value
            val coreWidth = (size.minDimension * 0.01f * fog).coerceAtLeast(1.6f) * dotSize
            mesh.glow(headX, headY, (size.minDimension * 0.016f * fog).coerceAtLeast(1f) * dotSize, colour)
            if (round || !scene.project(x[index], y[index], z[index] - streak)) {
                mesh.polygon(headX, headY, coreWidth * 0.5f, 4, 0.785f, colour)
                continue
            }
            val dx = headX - scene.screenX
            val dy = headY - scene.screenY
            val length = hypot(dx, dy)
            // A little elongation remains visible even in distant, slow-moving dots.
            val drawnLength = length.coerceAtLeast(coreWidth * (0.5f + streakParam.value * 2f))
            val directionX = if (length > 0.001f) dx / length else 0f
            val directionY = if (length > 0.001f) dy / length else 1f
            mesh.streak(headX - directionX * drawnLength, headY - directionY * drawnLength, headX, headY, coreWidth, colour)
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

    override fun DrawScope.drawTop(state: VizRenderState) {
        nebula.clear()
        val colour = state.palette.argb(0.7f + genes.walk, saturation = 0.6f, value = 0.8f, alpha = 0.5f * (0.1f + 1.2f * state.lift))
        nebula.glow(stage.x * size.width, stage.y * size.height, size.minDimension * 0.5f, colour, sides = 24)
        drawMesh(nebula, BlendMode.Plus)
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
        stage.reset()
        wander = 0f
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

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Speed),
        VizDrive(VizDriver.Bass, VizProperty.Size),
        VizDrive(VizDriver.Mid, VizProperty.Shape),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.LowHit, VizProperty.Shape),
    )
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
    private val rig = CameraRig(topSpeed = 14.4f, restSpeed = 0.7f, shove = 9f, sway = 0f, lean = 0.26f, baseFov = 76f, seed = 8_123)
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
        val cruise = if (state.frame.rhythm?.usable == true) spacing * state.frame.bpm / 60f else -1f
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
        if (gestures.snare > 0f) racerPush[(random.next() * racers.count(1)).toInt().coerceIn(0, 2)] = -3f * gestures.snare
        for (index in 0 until 3) {
            racerPush[index] += (0f - racerPush[index]) * (dt * 1.5f).coerceAtMost(1f)
            racerAhead[index] = 6f + 4f * index + 2f * sin(wobblePhase * 0.5f + index * 2f) + racerPush[index]
        }
        if (gestures.drop) rollLeft = gestures.cycleSeconds
        if (rollLeft > 0f) {
            rollLeft -= dt
            rollExtra += dt * TAU / gestures.cycleSeconds
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
        val lift = 0.1f + 1.2f * state.lift
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
