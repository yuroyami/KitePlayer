package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.CameraRig
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.headlight
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Journey
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A flight down a pipe whose walls are the last few seconds of the music.
 *
 * Each rib is a snapshot of the spectrum taken when it was born at the far end, and ribs travel
 * toward the camera, so flying forward means flying back through what was already played. Around
 * that there is a world: gates to fly through on every supported beat, debris rushing past, a light that runs
 * down the wall on every kick, windows where the quiet bands open the wall onto the stars outside,
 * and a camera that cuts to a new lane on some bar lines. A drop widens the pipe for a visual cycle and turns
 * its twist the other way.
 */
internal class Pipe : Layered(
    name = "Pipe",
    family = VizFamily.Immersion,
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 701L,
        groundKind = GroundKind.Stars,
        detailKind = DetailKind.Specks,
        detailStrength = 0.6f,
        camera = Camera2D(wander = 0f, punch = 0.03f, roll = 0f, shake = 0f, cuts = false, seed = 701),
    ),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Speed),
        VizDrive(VizDriver.Bass, VizProperty.Shape),
        VizDrive(VizDriver.Mid, VizProperty.Shape),
        VizDrive(VizDriver.Width, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Camera, VizCurve.Scaled),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        echoes = true,
        softBuffer = true,
    )
    // The pipe has its own camera in three dimensions; the flat one would move it a second time.
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = PostSpec.Off

    private val automatic = VizParam("Journey", 0f, 1f, 1f).apply { toggle = true; step = 1f }
    private val form = VizParam("Form", 0f, 2f, 0f).apply {
        step = 1f; choices = listOf("Pipe", "Tunnel", "Horizon planes")
        shownWhen = { automatic.value < 0.5f }
    }
    private val pace = VizParam("Transformation pace", 0.35f, 2f, 1f)
    internal val journey = Journey(3, 7_018L)
    private val affinity = FloatArray(3)
    private val shapePoint = FloatArray(2)

    private val twistGene = genes.number("Twist", 0f, 1f, 0.5f)
    private val radiusRule = genes.choice("Radius rule", 2)
    private val gateKind = genes.choice("Gates", 3)
    private val holes = genes.number("Holes", 0.1f, 0.4f, 0.28f)
    private val colourRate = genes.number("Colour rate", 0.15f, 0.6f, 0.34f)

    private val speed = VizParam("Speed", 0.25f, 3f, 1f)
    private val twistAmount = VizParam("Twist", 0f, 1f, 0.12f)
    private val twistDrift = VizParam("Twist drift", 0f, 1f, 0f)
    private val sidesParam = VizParam("Sides", 6f, MAX_SIDES.toFloat(), 24f).apply { step = 1f }
    private val ribsParam = VizParam("Rings", 16f, MAX_RIBS.toFloat(), 32f).apply { step = 1f }
    private val spacingParam = VizParam("Ring spacing", 0.8f, 2f, 1.4f)
    private val wallParam = VizParam("Solid walls", 0f, 1f, 1f).apply { step = 1f; toggle = true }
    private val panelParam = VizParam("Panel lights", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val nestedParam = VizParam("Inner shaft", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val cutsParam = VizParam("Camera cuts", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val cloudParam = VizParam("Cloud background", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val dustParam = VizParam("Dust", 0f, 40f, 20f)
    private val trailParam = VizParam("Echo trail", 0f, 0.94f, 0f)
    private val zoomParam = VizParam("Echo expansion", -1f, 1f, 0f)
    private val spinParam = VizParam("Echo rotation", -1f, 1f, 0f)
    private val glowParam = VizParam("Echo glow", 0f, 2f, 0f).apply { step = 1f }
    override val params: List<VizParam> = listOf(
        automatic, form, pace,
        speed, twistAmount, twistDrift, sidesParam, ribsParam, spacingParam, wallParam, panelParam,
        nestedParam, cutsParam, cloudParam, dustParam, trailParam, zoomParam, spinParam, glowParam,
    )
    override val trail: Float get() = trailParam.value
    override val feedbackZoom: Float get() = 1f + zoomParam.value * 0.03f
    override val feedbackSpin: Float get() = spinParam.value
    override val bloom: Int get() = glowParam.value.roundToInt()

    private var sides = 24
    private var ribs = 32
    private var spacing = 1.4f
    private val filled: Boolean get() = wallParam.value >= 0.5f
    private val panelLights: Boolean get() = panelParam.value >= 0.5f
    private val nestedShaft: Boolean get() = nestedParam.value >= 0.5f

    private val scene = Scene3D()
    private val path = Path()

    // One spectrum snapshot per rib. Slot [writeSlot] is the newest, at the far end.
    private val ribRadius = Array(MAX_RIBS) { FloatArray(MAX_SIDES) { 1f } }
    private val ribTint = FloatArray(MAX_RIBS)
    private var writeSlot = 0

    // Projected screen positions for the whole pipe, so the long lines need no second projection.
    private val pointX = FloatArray(MAX_RIBS * MAX_SIDES)
    private val pointY = FloatArray(MAX_RIBS * MAX_SIDES)
    private val pointOk = BooleanArray(MAX_RIBS * MAX_SIDES)
    private val ribFog = FloatArray(MAX_RIBS)
    private val smoothing = FloatArray(MAX_SIDES)
    private val ribDistance = FloatArray(MAX_RIBS)
    private val pointRadius = FloatArray(MAX_RIBS * MAX_SIDES)

    // The walls, as one batch of triangles, and which corner number each projected point became.
    private val mesh = TriangleMesh(maxVertices = MAX_RIBS * MAX_SIDES, maxIndices = MAX_RIBS * MAX_SIDES * 6)
    private val vertexOf = IntArray(MAX_RIBS * MAX_SIDES)
    // Cached colour conversion; the mesh interpolates the small steps between vertices.
    private val wallColours = IntArray(128 * 64)
    private var wallPalette: VizPalette? = null

    private var travel = 0f
    private var moved = 0f
    private val rig = CameraRig(
        topSpeed = 11.2f,
        restSpeed = 1.2f,
        shove = 6f,
        sway = 1.1f,
        lean = 0.3f,
        baseFov = 74f,
        cuts = true,
        seed = 7_717,
    )

    private val gateDistance = FloatArray(GATES) { -1f }
    private val gateTint = FloatArray(GATES)
    private var nextGate = 0
    private var lastBeat = -1
    private val debrisX = FloatArray(DEBRIS)
    private val debrisY = FloatArray(DEBRIS)
    private val debrisZ = FloatArray(DEBRIS)
    private var seeded = false
    private var lightDistance = -1f
    private var twistSign = 1f
    private var targetTwistSign = 1f
    private var widenHold = 0f
    private val widen = Envelope(attackPerSecond = 3f, releasePerSecond = 1.5f)
    /** How far the panel lights have scrolled, in ribs. */
    private var laneShift: Float = 0f
    private val comets = Comets(kind = Sprite.STREAK, size = 0.03f)
    private val dust = Sprites(160, 702L)
    private var vanishX = 0.5f
    private var vanishY = 0.5f
    private var look = 0f
    private var dustCredit = 0f

    // The echoes grow from the far end of the shaft, wherever the camera has turned it.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, spin = base.spin, centreX = vanishX, centreY = vanishY)
    }

    override fun advance(state: VizRenderState) {
        if (state.frame.held) return
        val dt = state.stepSeconds.coerceIn(0f, 0.1f) * state.motionScale
        affinity[0] = 0.35f + state.bassMotion
        affinity[1] = 0.2f + state.frame.density + state.frame.novelty.coerceIn(0f, 2f) * 0.3f
        affinity[2] = 0.25f + (1f - state.drive) * 0.7f + state.frame.width * 0.4f
        journey.advance(state, gestures, affinity, automatic.value >= 0.5f, form.value.toInt(), pace.value)
        updateGeometry(state)
        rig.cutsEnabled = cutsParam.value >= 0.5f
        ground?.kind = if (cloudParam.value >= 0.5f) GroundKind.Cloud else GroundKind.Stars
        val far = ribs * spacing
        if (!seeded) {
            for (index in 0 until DEBRIS) respawnDebris(index, far, anywhere = true)
            seeded = true
        }
        // Speed answers the music, and the kick shoves a spring rather than adding straight to it.
        moved = rig.advance(state, speed.value * state.motionScale)
        travel += moved
        while (travel >= spacing) {
            travel -= spacing
            writeSlot = (writeSlot - 1 + ribs) % ribs
            captureInto(writeSlot, state)
        }
        if (gestures.section) {
            twistGene.target = random.next()
            radiusRule.choose(1 - radiusRule.value)
        }
        if (gestures.drop) {
            widenHold = gestures.cycleSeconds
            targetTwistSign = -targetTwistSign
        }
        twistSign += (targetTwistSign - twistSign) * (1f - exp(-dt * 0.7f))
        widenHold -= dt
        widen.advance(if (widenHold > 0f) 1f else 0f, dt)
        // A gate on every supported beat, born at the far end and flown through a few beats later.
        val beat = (gestures.cyclePhase * 4f).toInt()
        if (beat != lastBeat && gestures.pulseUsable) {
            lastBeat = beat
            gateDistance[nextGate] = far * 0.95f
            gateTint[nextGate] = state.musicTime * colourRate.value + 0.5f
            nextGate = (nextGate + 1) % GATES
        }
        for (gate in 0 until GATES) {
            if (gateDistance[gate] < 0f) continue
            gateDistance[gate] -= moved
            if (gateDistance[gate] < NEAR_CLIP) gateDistance[gate] = -1f
        }
        // Debris rushes past faster than the walls.
        for (index in 0 until DEBRIS) {
            debrisZ[index] += moved * 1.6f + dt * 2f * speed.value
            if (debrisZ[index] > -1f) respawnDebris(index, far, anywhere = false)
        }
        // A kick sends a bright rib down the wall that reaches the camera in one beat.
        if (gestures.kick > 0f) lightDistance = far * (0.3f + 0.3f * gestures.kick)
        if (lightDistance >= 0f) {
            lightDistance -= dt * far * 0.6f / gestures.beatSeconds
            if (lightDistance < NEAR_CLIP) lightDistance = -1f
        }
        laneShift += dt / gestures.beatSeconds * speed.value
        look += dt * (0.04f + state.drive * 0.2f + state.frame.density * 0.12f)
        dustCredit += dt * dustParam.value * (0.5f + state.drive)
        while (dustCredit >= 1f) {
            dustCredit -= 1f
            val a = random.next() * TAU
            val reach = 0.05f + 0.25f * random.next()
            dust.burst(0.5f + cos(a) * reach / kit.aspect, 0.5f + sin(a) * reach, 1, 0.05f, 0.8f, 0.006f, random.next(), Sprite.GLOW)
        }
        dust.advance(dt, drag = 0.5f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    /** Re-sample the wall when its topology changes; the buffers already cover every setting. */
    private fun updateGeometry(state: VizRenderState) {
        val nextSides = sidesParam.value.roundToInt()
        val nextRibs = ribsParam.value.roundToInt()
        val nextSpacing = spacingParam.value
        if (nextSides == sides && nextRibs == ribs && nextSpacing == spacing) return
        sides = nextSides
        ribs = nextRibs
        spacing = nextSpacing
        travel = 0f
        writeSlot = 0
        for (slot in 0 until ribs) captureInto(slot, state)
        gateDistance.fill(-1f)
        lightDistance = -1f
        seeded = false
    }

    private fun respawnDebris(index: Int, far: Float, anywhere: Boolean) {
        debrisX[index] = random.signed() * 1.8f
        debrisY[index] = random.signed() * 1.8f
        debrisZ[index] = if (anywhere) -1f - random.next() * far else -far
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        scene.lens(size, fovDegrees = rig.fov, near = 0.5f, far = ribs * spacing)
        // A slow wander off the middle, so the flight reads as flying rather than sliding on a rail.
        scene.camera(
            eyeX = rig.eyeX,
            eyeY = rig.eyeY,
            eyeZ = 0f,
            // A slow look round, so the far end of the shaft is somewhere else every few seconds.
            targetX = rig.lookX + 1.4f * sin(look),
            targetY = rig.lookY + 0.9f * cos(look * 0.8f),
            targetZ = -spacing * 4f,
            roll = rig.roll,
        )
        if (scene.project(0f, 0f, -ribs * spacing * 0.9f)) {
            vanishX = scene.screenX / size.width
            vanishY = scene.screenY / size.height
        }
        projectPipe(state.bassMotion, state.body, state.frame.width, size.width, size.height)
        if (filled) drawWalls(state)
        drawRibs(state)
        drawLongLines(state)
        if (nestedShaft) drawNested(state)
        drawGates(state)
        drawDebris(state)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(dust) { drawSprites(state.palette, genes.walk, alpha = 0.5f, saturation = 0.3f) }
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    /** Takes the spectrum as the shape of one rib, wrapped so the loop closes without a seam. */
    private fun captureInto(slot: Int, state: VizRenderState) {
        val bands = state.frame.bandsRel
        val target = ribRadius[slot]
        val steady = radiusRule.weight(1)
        for (side in 0 until sides) {
            // Out and back, so the first point and the last point read the same band.
            val folded = if (bands.isEmpty()) 0f else bands.foldedAt(side.toFloat() / sides)
            target[side] = 0.6f + (0.62f + 0.2f * steady) * folded
        }
        // Rounded off with its neighbours, so a loud band swells the wall rather than spiking it.
        for (side in 0 until sides) {
            val before = target[(side - 1 + sides) % sides]
            val after = target[(side + 1) % sides]
            smoothing[side] = (before + 2f * target[side] + after) * 0.25f
        }
        smoothing.copyInto(target)
        // Fast enough that consecutive ribs differ, which lays colour bands down the pipe.
        ribTint[slot] = state.musicTime * colourRate.value + state.bassMotion * 0.12f + genes.walk
    }

    private fun baseRadius(bass: Float): Float {
        val breathing = 2.4f + 0.7f * bass
        return (breathing + (2.6f - breathing) * radiusRule.weight(1)) * (1f + widen.value)
    }

    private fun spinAt(distance: Float, body: Float): Float {
        val drifting = 0.5f + 0.5f * sin(genes.walk * TAU)
        val twist = twistGene.value + (drifting - twistGene.value) * twistDrift.value
        return distance * (twistAmount.value + journey.weights[1] * 0.12f) * (0.3f + 1.4f * twist) * twistSign +
            sin(distance * 0.28f + travel * 0.18f) * body * 0.18f
    }

    private fun projectPipe(bass: Float, body: Float, width: Float, screenWidth: Float, screenHeight: Float) {
        val baseRadius = baseRadius(bass)
        var firstVisible = true
        for (index in 0 until ribs) {
            val slot = (writeSlot + index) % ribs
            val distance = 0.7f + index * spacing - travel
            ribDistance[index] = distance
            val z = -distance
            val spin = spinAt(distance, body)
            // A rib almost touching the camera projects to a circle several screens across and costs
            // more than the whole far end, so it is dropped and the first one kept fades in.
            if (distance < NEAR_CLIP) {
                ribFog[index] = 0f
                val base = index * sides
                for (side in 0 until sides) pointOk[base + side] = false
                continue
            }
            val arriving = ((distance - NEAR_CLIP) / NEAR_FADE).coerceIn(0f, 1f)
            ribFog[index] = scene.fog(distance) * arriving
            val radii = ribRadius[slot]
            val base = index * sides
            for (side in 0 until sides) {
                val angle = TAU * side / sides
                val radius = baseRadius * (radii[side] * (1f - journey.weights[2] * 0.9f) + journey.weights[2] * 0.9f)
                pointRadius[base + side] = radius
                PipeShape.point(shapePoint, angle, radius, spin, journey.weights)
                val ok = scene.project(shapePoint[0] * (1f + width * 0.22f), shapePoint[1], z)
                pointOk[base + side] = ok
                if (ok) {
                    pointX[base + side] = scene.screenX
                    pointY[base + side] = scene.screenY
                    // The camera is inside the shaft, so its closest wall continues beyond the frame.
                    if (filled && firstVisible) {
                        val x = scene.screenX - screenWidth * 0.5f
                        val y = scene.screenY - screenHeight * 0.5f
                        val fullStretch = (hypot(screenWidth, screenHeight) * 0.7f / hypot(x, y).coerceAtLeast(1f)).coerceAtLeast(1f)
                        val stretch = 1f + (fullStretch - 1f) * (1f - journey.weights[2])
                        pointX[base + side] = screenWidth * 0.5f + x * stretch
                        pointY[base + side] = screenHeight * 0.5f + y * stretch
                    }
                }
            }
            if (firstVisible && filled) ribFog[index] = scene.fog(distance)
            firstVisible = false
        }
    }

    /** True where a quiet band has opened the wall onto the outside. */
    private fun open(index: Int, side: Int): Boolean {
        val radii = ribRadius[(writeSlot + index) % ribs]
        return (radii[side] - 0.6f) / 0.62f < holes.value
    }

    /**
     * The walls as solid lit surfaces, in one call. The light is a lamp at the camera: the ratio of
     * the wall's radius to its distance is the falloff, so no normals are needed. Each patch also glows
     * with its own part of the spectrum, the kick's light rib runs over it, and quiet stretches are
     * left open as windows.
     */
    private fun DrawScope.drawWalls(state: VizRenderState) {
        mesh.clear()
        vertexOf.fill(-1)
        if (wallPalette !== state.palette) {
            wallPalette = state.palette
            wallColours.fill(0)
        }
        val background = state.palette.background.toArgb()
        val push = 0.4f + 0.6f * state.drive
        for (index in 0 until ribs) {
            val fog = ribFog[index]
            if (fog <= 0.02f) continue
            val slot = (writeSlot + index) % ribs
            val distance = ribDistance[index]
            val tint = ribTint[slot]
            val radii = ribRadius[slot]
            val light = if (lightDistance >= 0f) (1f - abs(distance - lightDistance) / (spacing * 1.2f)).coerceIn(0f, 1f) else 0f
            val base = index * sides
            for (side in 0 until sides) {
                val at = base + side
                if (!pointOk[at]) continue
                val facing = headlight(pointRadius[at], distance)
                val glow = ((radii[side] - 0.6f) / 0.62f).coerceIn(0f, 1f)
                val panel = if (panelLights && side % 2 == 0 && ((index + laneShift.toInt()) % 4 == 0)) 0.35f else 0f
                val lit = (0.16f + 0.48f * facing + 0.36f * glow * push + 0.5f * light + panel).coerceIn(0f, 1f)
                val hue = tint + side.toFloat() / sides * 0.28f + glow * 0.1f
                val hueSlot = ((hue - floor(hue)) * 128f).toInt().coerceIn(0, 127)
                val lightSlot = (lit * 63f + 0.5f).toInt().coerceIn(0, 63)
                val key = hueSlot * 64 + lightSlot
                var colour = wallColours[key]
                if (colour == 0) {
                    colour = state.palette.cycled(hueSlot / 128f, saturation = 0.92f, value = lightSlot / 63f).toArgb()
                    wallColours[key] = colour
                }
                // Distance fog baked into opaque walls; transparency would blend the overlaps repeatedly.
                val fade = (fog * 255f).toInt().coerceIn(0, 255)
                val rest = 255 - fade
                val red = (((colour ushr 16) and 255) * fade + ((background ushr 16) and 255) * rest) / 255
                val green = (((colour ushr 8) and 255) * fade + ((background ushr 8) and 255) * rest) / 255
                val blue = ((colour and 255) * fade + (background and 255) * rest) / 255
                val opacity = (255f * (1f - 0.88f * journey.weights[1] - 0.82f * journey.weights[2])).toInt().coerceIn(0, 255)
                vertexOf[at] = mesh.vertex(pointX[at], pointY[at], (opacity shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
        // Far to near, so the near walls are laid over the far ones.
        for (index in ribs - 2 downTo 0) {
            val here = index * sides
            val there = (index + 1) * sides
            for (side in 0 until sides) {
                val next = (side + 1) % sides
                val a = vertexOf[here + side]
                val b = vertexOf[here + next]
                val c = vertexOf[there + next]
                val d = vertexOf[there + side]
                if (a < 0 || b < 0 || c < 0 || d < 0) continue
                if (open(index, side) && open(index + 1, side)) continue
                mesh.quad(a, b, c, d)
            }
        }
        drawMesh(mesh)
    }

    /** The rings, drawn far to near so the close ones sit on top. */
    private fun DrawScope.drawRibs(state: VizRenderState) {
        // Over solid walls the rings are seams, not the picture, so they step back.
        val seamStrength = if (filled) 0.5f + 0.5f * (1f - journey.weights[0]) else 1f
        for (index in ribs - 1 downTo 0) {
            val fog = ribFog[index]
            if (fog <= 0.02f) continue
            val base = index * sides
            path.reset()
            var started = false
            for (side in 0 until sides) {
                if (!pointOk[base + side]) continue
                if (started) {
                    path.lineTo(pointX[base + side], pointY[base + side])
                } else {
                    path.moveTo(pointX[base + side], pointY[base + side])
                    started = true
                }
            }
            if (!started) continue
            if (pointOk[base]) path.lineTo(pointX[base], pointY[base])
            val tint = ribTint[(writeSlot + index) % ribs]
            // The wide glow pass, only on the near ribs and only without a blooming echo: it is the
            // single most expensive stroke here.
            if (!filled && bloom == 0 && fog > 0.62f) {
                drawPath(path, state.palette.cycled(tint, value = 0.5f + 0.5f * fog, alpha = fog * 0.34f), style = Stroke((size.minDimension * 0.013f * fog).coerceAtLeast(1.5f)))
            }
            drawPath(
                path,
                state.palette.cycled(
                    tint,
                    value = (0.28f + 0.3f * fog + 0.42f * state.energy).coerceIn(0f, 1f),
                    alpha = (fog * (0.55f + 0.45f * state.energy) * seamStrength).coerceIn(0f, 1f),
                ),
                style = Stroke((size.minDimension * 0.0065f * fog).coerceAtLeast(0.9f)),
            )
        }
    }

    /** The lines running the length of the pipe, all in one path. These are what sell the depth. */
    private fun DrawScope.drawLongLines(state: VizRenderState) {
        path.reset()
        for (side in 0 until sides) {
            var started = false
            for (index in 0 until ribs) {
                val at = index * sides + side
                if (!pointOk[at]) {
                    started = false
                    continue
                }
                if (started) {
                    path.lineTo(pointX[at], pointY[at])
                } else {
                    path.moveTo(pointX[at], pointY[at])
                    started = true
                }
            }
        }
        drawPath(
            path,
            state.palette.cycled(
                ribTint[writeSlot] + 0.5f,
                value = 0.45f + 0.45f * state.energy,
                alpha = (0.16f + 0.24f * state.energy) * (if (filled) 0.45f else 1f),
            ),
            style = Stroke((size.minDimension * 0.0028f).coerceAtLeast(0.7f), cap = StrokeCap.Round),
        )
    }

    /** A thinner shaft of rib outlines inside, turning the other way. */
    private fun DrawScope.drawNested(state: VizRenderState) {
        val radius = baseRadius(state.bassMotion) * 0.45f
        for (index in ribs - 1 downTo 0 step 2) {
            val fog = ribFog[index]
            if (fog <= 0.05f) continue
            val distance = ribDistance[index]
            val spin = -spinAt(distance, state.body) * 1.5f
            path.reset()
            var started = false
            for (side in 0..sides) {
                val angle = TAU * side / sides
                PipeShape.point(shapePoint, angle, radius, spin, journey.weights)
                if (!scene.project(shapePoint[0], shapePoint[1], -distance)) {
                    started = false
                    continue
                }
                if (started) path.lineTo(scene.screenX, scene.screenY) else path.moveTo(scene.screenX, scene.screenY)
                started = true
            }
            drawPath(path, state.palette.cycled(ribTint[(writeSlot + index) % ribs] + 0.5f, value = 1f, alpha = fog * 0.6f), style = Stroke((size.minDimension * 0.004f * fog).coerceAtLeast(0.8f)))
        }
    }

    /** One gate a beat: a bright ring, hexagon or ring of glyphs the camera flies through. */
    private fun DrawScope.drawGates(state: VizRenderState) {
        val radius = baseRadius(state.bassMotion) * 0.82f
        val lift = 0.4f + 0.6f * state.lift
        for (gate in 0 until GATES) {
            val distance = gateDistance[gate]
            if (distance < 0f) continue
            val fog = scene.fog(distance)
            if (fog <= 0.02f) continue
            val colour = state.palette.cycled(gateTint[gate], value = 1f, alpha = (fog * 0.85f * lift).coerceIn(0f, 1f))
            val turn = distance * 0.1f
            when (gateKind.value) {
                2 -> for (point in 0 until 12) {
                    val angle = TAU * point / 12
                    PipeShape.point(shapePoint, angle, radius, turn, journey.weights)
                    if (!scene.project(shapePoint[0], shapePoint[1], -distance)) continue
                    drawCircle(colour, (size.minDimension * 0.012f * fog).coerceAtLeast(1f), Offset(scene.screenX, scene.screenY))
                }
                else -> {
                    val points = if (gateKind.value == 0) 40 else 6
                    path.reset()
                    var started = false
                    for (point in 0..points) {
                        val angle = TAU * point / points
                        PipeShape.point(shapePoint, angle, radius, turn, journey.weights)
                        if (!scene.project(shapePoint[0], shapePoint[1], -distance)) {
                            started = false
                            continue
                        }
                        if (started) path.lineTo(scene.screenX, scene.screenY) else path.moveTo(scene.screenX, scene.screenY)
                        started = true
                    }
                    drawPath(path, colour.copy(alpha = colour.alpha * 0.3f), style = Stroke((size.minDimension * 0.03f * fog).coerceAtLeast(2f)))
                    drawPath(path, colour, style = Stroke((size.minDimension * 0.009f * fog).coerceAtLeast(1f)))
                }
            }
        }
    }

    /** Debris as streaks from where it was to where it is. */
    private fun DrawScope.drawDebris(state: VizRenderState) {
        val lift = 0.4f + 0.6f * state.lift
        var placed = false
        for (index in 0 until DEBRIS) {
            val z = debrisZ[index]
            if (!scene.project(debrisX[index], debrisY[index], z)) continue
            val headX = scene.screenX
            val headY = scene.screenY
            val fog = scene.fog(-z)
            if (!scene.project(debrisX[index], debrisY[index], z - 0.8f - moved * 3f)) continue
            drawLine(
                state.palette.cycled(index * 0.07f + genes.walk, value = 1f, alpha = (fog * lift).coerceIn(0f, 1f)),
                Offset(scene.screenX, scene.screenY),
                Offset(headX, headY),
                (size.minDimension * 0.006f * fog).coerceAtLeast(1f),
                StrokeCap.Round,
            )
            if (!placed && headX in 0f..size.width && headY in 0f..size.height) {
                kit.place(1, headX / size.width, headY / size.height)
                placed = true
            }
        }
    }

    override fun onReset() {
        journey.reset()
        for (rib in ribRadius) rib.fill(1f)
        ribTint.fill(0f)
        writeSlot = 0
        travel = 0f
        moved = 0f
        rig.reset()
        gateDistance.fill(-1f)
        nextGate = 0
        lastBeat = -1
        seeded = false
        lightDistance = -1f
        twistSign = 1f
        targetTwistSign = 1f
        widenHold = 0f
        widen.reset()
        laneShift = 0f
        comets.clear()
        dust.clear()
        dustCredit = 0f
        vanishX = 0.5f
        vanishY = 0.5f
        look = 0f
    }

    private companion object {
        /** World units in front of the camera where the shaft starts being worth drawing. */
        const val NEAR_CLIP = 1.6f
        const val MAX_SIDES = 34
        const val MAX_RIBS = 48

        /** How far it takes to fade in from there. */
        const val NEAR_FADE = 0.4f
        const val GATES = 12
        const val DEBRIS = 90
    }
}
