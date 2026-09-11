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
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A flight down a pipe whose walls are the last few seconds of the music.
 *
 * Each rib is a snapshot of the spectrum taken when it was born at the far end, and ribs travel
 * toward the camera, so flying forward means flying back through what was already played. Around
 * that there is a world: gates to fly through once a beat, debris rushing past, a light that runs
 * down the wall on every kick, windows where the quiet bands open the wall onto the stars outside,
 * and a camera that cuts to a new lane on some bar lines. A drop widens the pipe for a bar and turns
 * its twist the other way.
 */
internal open class Pipe(
    name: String = "Pipe",
    /** Points around the circumference. More is rounder and costs one path each. */
    protected val sides: Int = 24,
    /** How many ribs are alive at once. This is also the length of the history. */
    protected val ribs: Int = 32,
    /** World units between ribs. */
    protected val spacing: Float = 1.4f,
    /** Radians of twist per world unit of depth. */
    protected val twist: Float = 0.12f,
    override val trail: Float = 0f,
    override val feedbackZoom: Float = 1f,
    override val feedbackSpin: Float = 0f,
    override val bloom: Int = 0,
    override val moodSpec: MoodSpec? = null,
    bucket: VizEnergy = VizEnergy.Mid,
    /** Solid lit walls, or the plain wire frame. The feedback tunnels keep the wire. */
    protected val filled: Boolean = true,
    /** Whether a bar line may jump the camera to a new lane of the shaft. */
    cuts: Boolean = true,
    family: VizFamily = VizFamily.Immersion,
    seed: Long = 701L,
    /** What shows through the windows in the wall. */
    outside: GroundKind = GroundKind.Stars,
    /** Multiplies every speed, for the slow pipes. */
    private val pace: Float = 1f,
) : Layered(
    name = name,
    family = family,
    bucket = bucket,
    kit = Kit(
        seed = seed,
        groundKind = outside,
        detailKind = DetailKind.Specks,
        detailStrength = 0.6f,
        camera = Camera2D(wander = 0f, punch = 0.03f, roll = 0f, shake = 0f, cuts = false, seed = seed.toInt()),
    ),
) {
    // The pipe has its own camera in three dimensions; the flat one would move it a second time.
    override val cameraOnEcho: Boolean get() = false

    protected val twistGene = genes.number("Twist", 0f, 1f, 0.5f)
    private val radiusRule = genes.choice("Radius rule", 2)
    private val gateKind = genes.choice("Gates", 3)
    private val holes = genes.number("Holes", 0.1f, 0.4f, 0.28f)
    private val colourRate = genes.number("Colour rate", 0.15f, 0.6f, 0.34f)

    private val scene = Scene3D()
    private val path = Path()

    // One spectrum snapshot per rib. Slot [writeSlot] is the newest, at the far end.
    private val ribRadius = Array(ribs) { FloatArray(sides) { 1f } }
    private val ribTint = FloatArray(ribs)
    private var writeSlot = 0

    // Projected screen positions for the whole pipe, so the long lines need no second projection.
    private val pointX = FloatArray(ribs * sides)
    private val pointY = FloatArray(ribs * sides)
    private val pointOk = BooleanArray(ribs * sides)
    private val ribFog = FloatArray(ribs)
    private val smoothing = FloatArray(sides)
    private val ribDistance = FloatArray(ribs)
    private val pointRadius = FloatArray(ribs * sides)

    // The walls, as one batch of triangles, and which corner number each projected point became.
    private val mesh = TriangleMesh(maxVertices = ribs * sides, maxIndices = ribs * sides * 6)
    private val vertexOf = IntArray(ribs * sides)
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
        cuts = cuts,
        seed = 7_717,
    )

    private val speed = VizParam("Speed", 0.25f, 3f, 1f)
    private val twistAmount = VizParam("Twist", 0f, 1f, twist)
    override val params: List<VizParam> = listOf(speed, twistAmount)

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
    private var widenHold = 0f
    private val widen = Envelope(attackPerSecond = 3f, releasePerSecond = 1.5f)
    /** How far the panel lights have scrolled, in ribs. */
    protected var laneShift: Float = 0f
        private set
    private val comets = Comets(kind = Sprite.STREAK, size = 0.03f)
    private val dust = Sprites(160, seed + 1L)
    private var vanishX = 0.5f
    private var vanishY = 0.5f
    private var look = 0f
    private var dustCredit = 0f

    /** Motes a second drifting in the headlight. */
    protected open val dustRate: Float get() = 20f

    /** Lights on the walls in stripes that scroll along the shaft. */
    protected open val panelLights: Boolean get() = false

    /** A thinner shaft inside this one, turning the other way. */
    protected open val nestedShaft: Boolean get() = false

    /** For a subclass to move its own things on each frame. */
    protected open fun onAdvance(state: VizRenderState) {}

    // The echoes grow from the far end of the shaft, wherever the camera has turned it.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, spin = base.spin, centreX = vanishX, centreY = vanishY)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val far = ribs * spacing
        if (!seeded) {
            for (index in 0 until DEBRIS) respawnDebris(index, far, anywhere = true)
            seeded = true
        }
        // Speed answers the music, and the kick shoves a spring rather than adding straight to it.
        moved = rig.advance(state, speed.value * pace)
        travel += moved
        while (travel >= spacing) {
            travel -= spacing
            writeSlot = (writeSlot - 1 + ribs) % ribs
            captureInto(writeSlot, state)
        }
        if (gestures.phrase) {
            twistGene.target = random.next()
            radiusRule.choose(1 - radiusRule.value)
        }
        if (gestures.drop) {
            widenHold = gestures.barSeconds
            twistSign = -twistSign
        }
        widenHold -= dt
        widen.advance(if (widenHold > 0f) 1f else 0f, dt)
        // A gate every beat, born at the far end and flown through a few beats later.
        val beat = (gestures.barPhase * 4f).toInt()
        if (beat != lastBeat) {
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
            debrisZ[index] += moved * 1.6f + dt * 2f * pace
            if (debrisZ[index] > -1f) respawnDebris(index, far, anywhere = false)
        }
        // A kick sends a bright rib down the wall that reaches the camera in one beat.
        if (gestures.kickHit > 0f) lightDistance = far * 0.6f
        if (lightDistance >= 0f) {
            lightDistance -= dt * far * 0.6f / gestures.beatSeconds
            if (lightDistance < NEAR_CLIP) lightDistance = -1f
        }
        laneShift += dt / gestures.beatSeconds * pace
        look += dt * TAU / 16f
        dustCredit += dt * dustRate * (0.5f + state.drive)
        while (dustCredit >= 1f) {
            dustCredit -= 1f
            val a = random.next() * TAU
            val reach = 0.05f + 0.25f * random.next()
            dust.burst(0.5f + cos(a) * reach / kit.aspect, 0.5f + sin(a) * reach, 1, 0.05f, 0.8f, 0.006f, random.next(), Sprite.GLOW)
        }
        dust.advance(dt, drag = 0.5f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        onAdvance(state)
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

    private fun spinAt(distance: Float, body: Float): Float =
        distance * twistAmount.value * (0.3f + 1.4f * twistGene.value) * twistSign + sin(distance * 0.28f + travel * 0.18f) * body * 0.18f

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
                val angle = TAU * side / sides + spin
                val radius = baseRadius * radii[side]
                pointRadius[base + side] = radius
                val ok = scene.project(cos(angle) * radius * (1f + width * 0.22f), sin(angle) * radius, z)
                pointOk[base + side] = ok
                if (ok) {
                    pointX[base + side] = scene.screenX
                    pointY[base + side] = scene.screenY
                    // The camera is inside the shaft, so its closest wall continues beyond the frame.
                    if (filled && firstVisible) {
                        val x = scene.screenX - screenWidth * 0.5f
                        val y = scene.screenY - screenHeight * 0.5f
                        val stretch = (hypot(screenWidth, screenHeight) * 0.7f / hypot(x, y).coerceAtLeast(1f)).coerceAtLeast(1f)
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
                vertexOf[at] = mesh.vertex(pointX[at], pointY[at], (255 shl 24) or (red shl 16) or (green shl 8) or blue)
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
        val seamStrength = if (filled) 0.5f else 1f
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
                val angle = TAU * side / sides + spin
                if (!scene.project(cos(angle) * radius, sin(angle) * radius, -distance)) {
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
                    val angle = TAU * point / 12 + turn
                    if (!scene.project(cos(angle) * radius, sin(angle) * radius, -distance)) continue
                    drawCircle(colour, (size.minDimension * 0.012f * fog).coerceAtLeast(1f), Offset(scene.screenX, scene.screenY))
                }
                else -> {
                    val points = if (gateKind.value == 0) 40 else 6
                    path.reset()
                    var started = false
                    for (point in 0..points) {
                        val angle = TAU * point / points + turn
                        if (!scene.project(cos(angle) * radius, sin(angle) * radius, -distance)) {
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

        /** How far it takes to fade in from there. */
        const val NEAR_FADE = 0.4f
        const val GATES = 12
        const val DEBRIS = 90
    }
}

/** The same flight down a shaft with flat sides, with panel lights scrolling along it and a thinner shaft inside turning the other way. */
internal class HexShaft : Pipe(
    name = "Hex Shaft",
    sides = 6,
    ribs = 48,
    spacing = 0.95f,
    twist = 0.05f,
    cuts = true,
    seed = 702L,
) {
    override val panelLights: Boolean get() = true
    override val nestedShaft: Boolean get() = true
}

/** A wider, slower pipe with more sides and a hard twist that walks its whole range every eight phrases, cloud outside and motes drifting in the hole. */
internal class Wormhole : Pipe(
    name = "Wormhole",
    sides = 34,
    ribs = 36,
    spacing = 1.3f,
    twist = 0.3f,
    bucket = VizEnergy.Calm,
    moodSpec = MoodSpec(calmTrail = 0.7f, livelyTrail = 0.58f),
    cuts = false,
    seed = 703L,
    outside = GroundKind.Cloud,
    pace = 0.6f,
) {
    override val dustRate: Float get() = 24f

    override fun onAdvance(state: VizRenderState) {
        twistGene.target = 0.5f + 0.5f * sin(genes.walk * TAU)
    }
}

/**
 * The same flight with the last frame coming back larger and turned, so every rib leaves an echo that
 * keeps growing behind it: a corridor inside a corridor with no end. Calm music gets a long slow echo
 * and a gentle turn, a chorus a short hard one that rushes.
 */
internal class AcidTunnel : Pipe(
    name = "Acid Tunnel",
    sides = 22,
    ribs = 28,
    spacing = 1.5f,
    twist = 0.42f,
    bloom = 1,
    bucket = VizEnergy.High,
    filled = false,
    family = VizFamily.Acid,
    seed = 704L,
    moodSpec = MoodSpec(
        calmTrail = 0.9f,
        livelyTrail = 0.8f,
        calmZoom = 1.006f,
        livelyZoom = 1.026f,
        calmSpin = 0.08f,
        livelySpin = 0.34f,
    ),
)

/** Pulled the other way: the echoes fall inward, so the whole picture drains towards the middle. */
internal class Drain : Pipe(
    name = "Drain",
    sides = 30,
    ribs = 26,
    spacing = 1.7f,
    twist = 0.55f,
    bloom = 1,
    bucket = VizEnergy.High,
    filled = false,
    family = VizFamily.Acid,
    seed = 705L,
    moodSpec = MoodSpec(
        calmTrail = 0.92f,
        livelyTrail = 0.85f,
        calmZoom = 0.994f,
        livelyZoom = 0.978f,
        calmSpin = -0.2f,
        livelySpin = -0.7f,
    ),
)
