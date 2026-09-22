package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoCopy
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.abs
import kotlin.math.sin

/**
 * Soft lobes of light on three depths, crossing the screen on wide looping paths.
 *
 * Near lobes are bigger, move further with the camera and pass in front of far ones. A kick splits
 * the loud lobes in two for a beat, a drop sends the whole cloud rushing outward for a visual cycle, and the
 * shape of the paths changes as the song goes on.
 */
internal class Blur : Layered(
    name = "Blur",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Calm,
    kit = Kit(
        seed = 202L,
        groundKind = GroundKind.Cloud,
        detailKind = DetailKind.Specks,
        camera = Camera2D(wander = 0.09f, punch = 0.05f, roll = 0.05f, shake = 0f, cuts = false, seed = 202),
    ),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Size),
        VizDrive(VizDriver.SlowLevel, VizProperty.Size),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
        VizDrive(VizDriver.Pulse, VizProperty.Shape),
    )
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(
        calmTrail = 0.88f,
        livelyTrail = 0.78f,
        calmZoom = 1.002f,
        livelyZoom = 1.012f,
    )

    private val lobes = genes.choice("Lobes", 3, start = 2)
    private val depths = genes.choice("Depths", 3, start = 2)
    private val paths = genes.choice("Paths", 4)
    private val softness = genes.number("Softness", 0.7f, 1.4f, 1f)

    private val stage = Stage(reachX = 0.1f, reachY = 0.08f, start = 0.7f)
    private var phase = 0f
    private val bulge = Spring(stiffness = 80f, damping = 0.5f)
    private val splitting = FloatArray(MOST)
    private var rush = 0f
    private val lobeX = FloatArray(MOST) { 0.5f }
    private val lobeY = FloatArray(MOST) { 0.5f }
    private val lobeDepth = FloatArray(MOST) { 1f }
    private val dust = Sprites(220, 2_202L)
    private var dustOwed = 0f

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX + 0.03f * rush, spin = base.spin)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt * state.idle)
        phase += dt * TAU / (gestures.cycleSeconds * 1.5f) * (0.75f + 0.6f * state.drive)
        bulge.kick(gestures.kick * 6f)
        bulge.advance(dt)
        if (gestures.drop) rush = 1f
        rush = (rush - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        val loud = LOUD_BAND
        for (index in 0 until MOST) {
            splitting[index] = (splitting[index] - dt / gestures.beatSeconds).coerceAtLeast(0f)
            if (gestures.kick > 0f && state.frame.bandsRel.sampleAt(index / MOST.toFloat()) > loud) splitting[index] = 1f
            var x = 0f
            var y = 0f
            var weight = 0f
            for (family in 0 until PATHS) {
                val share = paths.weight(family)
                if (share <= 0f) continue
                x += (stage.x + 0.4f * sin(FX[family] * phase + index * GOLDEN)) * share
                y += (stage.y + 0.36f * sin(FY[family] * phase + index * GOLDEN * 1.7f + SHIFT[family])) * share
                weight += share
            }
            var depth = 0f
            for (layers in 0 until 3) {
                val share = depths.weight(layers)
                if (share > 0f) depth += DEPTH_TABLE[layers][index % (layers + 1)] * share
            }
            lobeDepth[index] = depth
            lobeX[index] = x / weight.coerceAtLeast(1e-4f) + camera.panX * (depth - 1f) * 0.6f
            lobeY[index] = y / weight.coerceAtLeast(1e-4f) + camera.panY * (depth - 1f) * 0.6f
            if (index < 4) kit.place(index, lobeX[index], lobeY[index])
        }
        dustOwed += dt * (8f * state.idle + 26f * state.air)
        while (dustOwed >= 1f) {
            dustOwed -= 1f
            dust.sprinkle(1, 3f, 0.006f, random.next(), Sprite.GLOW, drift = 0.05f)
        }
        dust.advance(dt, drag = 0.1f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val swell = (1f + 0.2f * bulge.value.coerceIn(0f, 1.5f)) * (0.75f + 0.5f * state.frame.loudLong) * softness.value
        // Far first, so the near lobes pass in front.
        for (pass in 0 until 3) {
            for (index in 0 until MOST) {
                val depth = lobeDepth[index]
                val band = if (depth < 0.8f) 0 else if (depth < 1.15f) 1 else 2
                if (band != pass) continue
                val presence = lobes.presence(index, 9, 3)
                if (presence <= 0.01f) continue
                val energy = state.frame.bandsRel.sampleAt(index / MOST.toFloat())
                val radius = sceneRadius * (0.08f + 0.2f * energy) * depth * swell
                val alpha = ((0.06f + 0.2f * state.lift) * presence).coerceIn(0f, 1f)
                val tint = index.toFloat() / MOST + genes.walk
                val x = lobeX[index] * size.width
                val y = lobeY[index] * size.height
                val apart = splitting[index] * radius * 0.7f
                if (apart > 1f) {
                    lobe(x - apart, y, radius * 0.8f, tint, alpha, state)
                    lobe(x + apart, y, radius * 0.8f, tint + 0.1f, alpha, state)
                } else {
                    lobe(x, y, radius, tint, alpha, state)
                }
            }
        }
    }

    private fun DrawScope.lobe(x: Float, y: Float, radius: Float, tint: Float, alpha: Float, state: VizRenderState) {
        val reach = radius.coerceAtLeast(1f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to state.palette.cycled(tint, alpha = alpha),
                1f to Color.Transparent,
                center = Offset(x, y),
                radius = reach,
            ),
            radius = reach,
            center = Offset(x, y),
        )
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(dust) { drawSprites(state.palette, genes.walk, alpha = 0.7f) }
    }

    override fun onReset() {
        stage.reset()
        phase = 0f
        bulge.reset()
        splitting.fill(0f)
        rush = 0f
        dust.clear()
        dustOwed = 0f
    }

    private companion object {
        const val MOST = 15
        const val PATHS = 4
        const val GOLDEN = 2.3999632f
        val FX = floatArrayOf(1f, 2f, 3f, 1f)
        val FY = floatArrayOf(2f, 3f, 2f, 1f)
        val SHIFT = floatArrayOf(0f, 0f, 0f, 1.5707964f)
        val DEPTH_TABLE = arrayOf(floatArrayOf(1f), floatArrayOf(0.6f, 1.2f), floatArrayOf(0.45f, 1f, 1.35f))
    }
}

/**
 * Three fountains, one for each part of the spectrum, spraying toward each other so the drops cross
 * the screen. Drops that land start rings on the pool, the wind changes side at each supported section boundary, a big drop
 * arcs over the middle every bar, and the spray is mirrored in the pool below.
 */
internal class Fountain : Layered(
    name = "Fountain",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 301L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.07f, seed = 301)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Spawn),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2f)),
        VizDrive(VizDriver.Section, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.86f, livelyTrail = 0.74f)

    private val fountains = genes.choice("Fountains", 3, start = 2)
    private val windGene = genes.number("Wind", -1f, 1f, 0.4f)
    private val heavy = genes.number("Gravity", 0.8f, 1.5f, 1.1f)
    private val reflection = genes.toggle("Reflection", start = true)

    private val drops = Sprites(900, 1_301L)
    private val mist = Sprites(160, 2_301L)
    private val leaders = Travellers(4)
    private val leaderMesh = TriangleMesh(maxVertices = 4 * 12 + 8)
    private val wind = Slew(maxPerSecond = 0.8f)
    private var trickle = 0f
    private var geyser = 0f
    private var fromLeft = true
    private val rippleX = FloatArray(RIPPLES)
    private val rippleAge = FloatArray(RIPPLES)
    private val rippleTint = FloatArray(RIPPLES)
    private var nextRipple = 0

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val mirror = reflection.weight(1)
        return EchoFrame(
            zoomX = base.zoomX,
            driftX = wind.value * 0.06f,
            copy = if (mirror > 0.05f) EchoCopy(mirrorY = true, share = 0.22f * mirror) else null,
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        // The wind's side comes from the gene and flips at each supported section boundary.
        wind.advance(windGene.value * if (gestures.sections % 2 == 0) 1f else -1f, dt)
        val count = fountains.count(1)
        trickle += dt * (40f * state.idle + 260f * state.drive)
        while (trickle >= 1f) {
            trickle -= 1f
            spray((random.next() * count).toInt().coerceAtMost(count - 1), 0.75f + 0.35f * random.next(), 1)
        }
        if (gestures.kick > 0f) spray(0, 1.35f, (18 + 20 * gestures.kick).toInt())
        if (gestures.snare > 0f) for (index in 1 until count) spray(index, 1.25f, gestures.snareSpawn(12))
        if (gestures.drop) geyser = gestures.cycleSeconds
        if (geyser > 0f) {
            geyser -= dt
            spray(0, 1.8f, 6)
        }
        // One big drop a bar arcs from one side to the other, high over the middle.
        if (gestures.section) {
            val from = if (fromLeft) 0.1f else 0.9f
            leaders.spawn(from, POOL, 1f - from, POOL, gestures.cycleSeconds * 0.9f, PathShape.Arc, if (fromLeft) -0.62f else 0.62f, 0.028f, random.next(), 0f, Sprite.GLOW)
            fromLeft = !fromLeft
        }
        leaders.advance(dt)
        kit.follow(0, leaders)
        kit.place(1, SPOT_X[0], POOL)
        val gravity = 1.2f * heavy.value
        drops.advance(dt, drag = 0.1f, gravity = gravity)
        val pool = drops.pool
        for (slot in 0 until pool.capacity) {
            if (pool.life[slot] <= 0f) continue
            pool.velocityX[slot] += wind.value * 0.25f * dt
            val rising = pool.velocityY[slot]
            // At the crest a few drops leave a puff of mist.
            if (rising > 0f && rising <= gravity * dt && random.next() < 0.08f) {
                mist.burst(pool.x[slot], pool.y[slot], 1, 0.02f, 1.2f, 0.02f, pool.tint[slot], Sprite.GLOW)
            }
            if (pool.y[slot] >= POOL && rising > 0f) {
                pool.life[slot] = 0f
                if (random.next() < 0.5f) ripple(pool.x[slot], pool.tint[slot])
            }
        }
        mist.advance(dt, drag = 0.5f)
        for (slot in 0 until RIPPLES) {
            if (rippleAge[slot] <= 0f) continue
            rippleAge[slot] += dt / 1.2f
            if (rippleAge[slot] >= 1f) rippleAge[slot] = 0f
        }
    }

    private fun spray(index: Int, speed: Float, count: Int) {
        drops.burst(SPOT_X[index], POOL, count, speed, 2.4f, 0.024f, index * 0.3f + random.next() * 0.1f, Sprite.GLOW, UP + AIM[index], 0.45f)
    }

    private fun ripple(x: Float, tint: Float) {
        rippleX[nextRipple] = x
        rippleAge[nextRipple] = 0.001f
        rippleTint[nextRipple] = tint
        nextRipple = (nextRipple + 1) % RIPPLES
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val alpha = 0.35f + 0.65f * state.lift
        with(drops) { drawSprites(state.palette, genes.walk, alpha = alpha) }
        with(mist) { drawSprites(state.palette, genes.walk, alpha = 0.3f * alpha, saturation = 0.3f) }
        drawTravellers(leaders, leaderMesh, state.palette, genes.walk)
        // Rings spreading on the pool where drops land.
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        val y = POOL * size.height + size.height * 0.03f
        for (slot in 0 until RIPPLES) {
            val age = rippleAge[slot]
            if (age <= 0f) continue
            val wide = size.width * 0.08f * age
            drawOval(
                state.palette.cycled(rippleTint[slot] + genes.walk, value = 1f, alpha = (0.6f * (1f - age) * alpha).coerceIn(0f, 1f)),
                topLeft = Offset(rippleX[slot] * size.width - wide, y - wide * 0.18f),
                size = Size(wide * 2f, wide * 0.36f),
                style = Stroke(thin),
            )
        }
    }

    override fun onReset() {
        drops.clear()
        mist.clear()
        leaders.clear()
        wind.reset()
        trickle = 0f
        geyser = 0f
        fromLeft = true
        rippleAge.fill(0f)
        nextRipple = 0
    }

    private companion object {
        const val POOL = 0.9f
        const val RIPPLES = 40
        val SPOT_X = floatArrayOf(0.5f, 0.1f, 0.9f)
        val AIM = floatArrayOf(0f, 0.45f, -0.45f)
    }
}

/**
 * Balls on up to three depths, bouncing on a floor that tilts, so they roll across the screen while
 * they bounce and leave streaks behind. A kick throws everything up, a snare tips the floor the other
 * way for a moment, the tilt changes side at each supported section boundary, and a drop lets the floor fall away for a visual cycle.
 */
internal class Gravity : Layered(
    name = "Gravity",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 302L, groundKind = GroundKind.Fog, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.06f, seed = 302)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.BodyHit, VizProperty.Camera, VizCurve.Scaled, VizResponse.spring(0.4f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.78f, livelyTrail = 0.66f)

    private val balls = genes.choice("Balls", 3, start = 2)
    private val depths = genes.choice("Depths", 3, start = 2)
    private val slope = genes.number("Tilt", 0.25f, 0.8f, 0.5f)
    private val floorKind = genes.choice("Floor", 2)

    private val x = FloatArray(MOST)
    private val height = FloatArray(MOST)
    private val vx = FloatArray(MOST)
    private val vh = FloatArray(MOST)
    private var side = 1f
    private val tilt = Slew(maxPerSecond = 1.5f)
    private val nudge = Spring(stiffness = 40f, damping = 0.6f)
    private var fall = 0f
    private var started = false
    private val sparks = Sprites(260, 1_302L)
    private val mesh = TriangleMesh(maxVertices = MOST * 18 + 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (!started) {
            for (index in 0 until MOST) {
                x[index] = random.next()
                height[index] = random.next() * 0.5f
                vx[index] = random.signed() * 0.1f
                vh[index] = 0f
            }
            started = true
        }
        if (gestures.section) side = -side
        nudge.kick(gestures.snare * 3f * -side)
        nudge.advance(dt)
        tilt.advance(side * slope.value, dt)
        val lean = tilt.value + 0.3f * nudge.value
        if (gestures.drop) fall = 1f
        fall = (fall - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        // Thrown up and back in one beat: both halves come from the tempo.
        val pull = 8f / gestures.beatSeconds
        val throwSpeed = pull * gestures.beatSeconds * 0.5f
        val bands = state.frame.bandsRel
        val kick = gestures.kick
        val live = balls.count(36, 18)
        for (index in 0 until MOST) {
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(index.toFloat() / MOST)
            if (kick > 0f) vh[index] += kick * throwSpeed * (0.4f + 0.8f * energy)
            if (gestures.hat > 0f) vh[index] += gestures.hat * throwSpeed * 0.12f * energy
            vh[index] -= pull * dt
            height[index] += vh[index] * dt
            if (height[index] <= 0f) {
                height[index] = 0f
                if (vh[index] < -throwSpeed * 0.5f && index < live) {
                    sparks.burst(x[index], floorY(index), 3, 0.15f, 0.4f, 0.008f, index.toFloat() / MOST, Sprite.SPARK, UP, 1.6f)
                }
                // Half the energy is kept, which is what a bounce that eventually stops looks like.
                vh[index] = -vh[index] * 0.5f
                if (abs(vh[index]) < 0.05f) vh[index] = 0f
            }
            if (height[index] > 1f) {
                height[index] = 1f
                vh[index] = 0f
            }
            // The tilt rolls them along the floor, and a ball in the air keeps its speed.
            val grip = if (height[index] <= 0.01f) 1f else 0.25f
            vx[index] = ((vx[index] + lean * 0.7f * grip * dt) * (1f - 0.35f * dt)).coerceIn(-0.7f, 0.7f)
            x[index] = wrap(x[index] + vx[index] * dt * state.tempo)
        }
        for (index in 0 until 3) kit.place(index, x[index], screenY(index))
        sparks.advance(dt, drag = 1.2f, gravity = 0.8f)
    }

    private fun near(index: Int): Float {
        val layers = depths.count(1)
        return NEAR[layers - 1][index % layers]
    }

    private fun floorY(index: Int): Float = HORIZON + (1f - HORIZON) * near(index) * (1f - 0.6f * fall) - 0.02f

    private fun screenY(index: Int): Float {
        val floor = floorY(index)
        return floor - height[index] * (floor - 0.05f) * 0.9f
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val unit = size.minDimension * 0.024f
        val walk = genes.walk
        val bands = state.frame.bandsRel
        for (index in 0 until balls.drawn(36, 18)) {
            val presence = balls.presence(index, 36, 18)
            if (presence <= 0.01f) continue
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(index.toFloat() / MOST)
            val radius = unit * (0.5f + near(index)) * (0.8f + 1.4f * energy) * (1f - 0.6f * fall)
            val colour = state.palette.argb(index.toFloat() / MOST + walk, value = 0.45f + 0.55f * energy, alpha = (0.35f + 0.6f * state.lift) * presence)
            val bx = x[index] * size.width
            val by = screenY(index) * size.height
            mesh.glow(bx, by, radius * 1.8f, colour)
            mesh.polygon(bx, by, radius * 0.6f, 8, 0f, colour)
        }
        drawMesh(mesh, BlendMode.Plus)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val lean = tilt.value + 0.3f * nudge.value
        val fade = (0.1f + 0.15f * state.lift) * (1f - fall)
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        rotate(lean * 6f, Offset(center.x, size.height * HORIZON)) {
            val lines = floorKind.weight(0)
            if (lines > 0.01f) {
                val colour = state.palette.mid.copy(alpha = fade * lines)
                for (row in 0..6) {
                    val depth = row / 6f
                    val y = (HORIZON + (1f - HORIZON) * depth * depth) * size.height
                    drawLine(colour, Offset(-size.width * 0.2f, y), Offset(size.width * 1.2f, y), thin)
                }
                for (column in -6..6) {
                    drawLine(
                        colour,
                        Offset(center.x + column * size.width * 0.05f, HORIZON * size.height),
                        Offset(center.x + column * size.width * 0.25f, size.height * 1.1f),
                        thin,
                    )
                }
            }
            val dots = floorKind.weight(1)
            if (dots > 0.01f) {
                val colour = state.palette.mid.copy(alpha = fade * dots * 1.6f)
                for (row in 1..6) {
                    val depth = row / 6f
                    val y = (HORIZON + (1f - HORIZON) * depth * depth) * size.height
                    for (column in -8..8) {
                        val spread = size.width * (0.05f + 0.2f * depth)
                        drawCircle(colour, thin * (0.8f + 1.5f * depth), Offset(center.x + column * spread, y))
                    }
                }
            }
        }
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        started = false
        side = 1f
        tilt.reset()
        nudge.reset()
        fall = 0f
        sparks.clear()
    }

    private companion object {
        const val MOST = 72
        const val HORIZON = 0.42f
        val NEAR = arrayOf(floatArrayOf(0.8f), floatArrayOf(0.45f, 0.9f), floatArrayOf(0.25f, 0.6f, 0.95f))
    }
}

/**
 * A field of twinkles sliding one screen every four bars, so stars enter one side and leave the other,
 * with three big stars on orbits, one for each part of the spectrum, and comets with long tails.
 */
internal class Sparkle : Layered(
    name = "Sparkle",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 303L, groundKind = GroundKind.Stars, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.08f, seed = 303)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Spawn),
        VizDrive(VizDriver.Treble, VizProperty.Spawn),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.8f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(3f)),
        VizDrive(VizDriver.Section, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.8f, calmSpin = 0.05f, livelySpin = 0.15f)

    private val drift = genes.choice("Drift", 4)
    private val cometRate = genes.choice("Comets", 3, start = 1)
    private val bigStars = genes.choice("Big stars", 3, start = 2)
    private val tail = genes.number("Tail", -0.06f, 0.05f, 0f)

    private val stage = Stage(start = 0.6f)
    private val twinkles = Sprites(900, 1_303L)
    private var credit = 0f
    private val stars = Array(3) { Orbiter(radiusX = 0.3f + 0.05f * it, radiusY = 0.26f, lapsPerBar = 0.4f + 0.1f * it, phase = it / 3f) }
    private val comets = Travellers(24)
    private val cometMesh = TriangleMesh(maxVertices = 24 * 12 + 8)
    private val starMesh = TriangleMesh(maxVertices = 3 * 16 + 16)
    private var showerLeft = 0f
    private var showerCredit = 0f
    private var lastBeat = -1

    override fun trailAt(mood: Float): Float = (super.trailAt(mood) + tail.value).coerceIn(0f, 0.97f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt * state.idle)
        // The whole field slides one screen every four bars.
        var dx = 0f
        var dy = 0f
        for (option in 0 until 4) {
            val share = drift.weight(option)
            dx += share * DRIFT_X[option]
            dy += share * DRIFT_Y[option]
        }
        val step = dt / (gestures.cycleSeconds * 4f)
        twinkles.advance(dt, drag = 1.2f)
        val pool = twinkles.pool
        for (slot in 0 until pool.capacity) {
            if (pool.life[slot] <= 0f) continue
            pool.x[slot] = wrap(pool.x[slot] + dx * step)
            pool.y[slot] = wrap(pool.y[slot] + dy * step)
        }
        credit += dt * (60f * state.idle + 390f * state.air * (0.3f + 0.7f * state.mood)) + gestures.hat * 30f
        while (credit >= 1f) {
            credit -= 1f
            twinkles.sprinkle(1, 0.5f + 0.9f * state.calm, 0.035f, random.next(), Sprite.SPARK, drift = 0.01f)
        }
        for (index in 0 until 3) {
            val star = stars[index]
            star.centreX = stage.x
            star.centreY = stage.y
            star.direction = if (index % 2 == 0) 1f else -1f
            star.advance(state, gestures)
            kit.place(index, star.x, star.y)
        }
        if (gestures.kick > 0f) {
            var loudest = 0
            for (index in 1 until 3) if (split.level(index) > split.level(loudest)) loudest = index
            twinkles.burst(stars[loudest].x, stars[loudest].y, gestures.kickSpawn(14), 0.35f, 0.8f, 0.03f, loudest / 3f, Sprite.SPARK)
        }
        val beat = (gestures.cyclePhase * 4f).toInt()
        val newBeat = beat != lastBeat && gestures.pulseUsable
        lastBeat = beat
        val rate = cometRate.value
        if (gestures.snare > 0f || (rate >= 1 && gestures.section) || (rate >= 2 && newBeat && beat % 2 == 0)) launchComet()
        if (gestures.drop) showerLeft = gestures.cycleSeconds
        if (showerLeft > 0f) {
            showerLeft -= dt
            showerCredit += dt * 20f / gestures.cycleSeconds * state.idle
            while (showerCredit >= 1f) {
                showerCredit -= 1f
                launchComet()
            }
        }
        comets.advance(dt)
        kit.follow(3, comets)
    }

    private fun launchComet() {
        comets.across(random, gestures.beatSeconds * 3f, PathShape.Line, 0f, 0.02f, random.next(), 0f, Sprite.GLOW)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val lift = 0.3f + 0.7f * state.lift
        with(twinkles) { drawSprites(state.palette, genes.walk, alpha = lift, saturation = 0.4f) }
        drawTravellers(comets, cometMesh, state.palette, genes.walk, alpha = lift)
        // Halos for the big stars, which the echo spreads into soft glows.
        for (index in 0 until bigStars.drawn(1)) {
            val presence = bigStars.presence(index, 1)
            if (presence <= 0.01f) continue
            val at = Offset(stars[index].x * size.width, stars[index].y * size.height)
            val radius = size.minDimension * (0.05f + 0.07f * split.level(index))
            drawCircle(
                Brush.radialGradient(0f to state.palette.cycled(index * 0.3f + genes.walk, alpha = (0.25f * lift * presence).coerceIn(0f, 1f)), 1f to Color.Transparent, center = at, radius = radius),
                radius,
                at,
            )
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        starMesh.clear()
        for (index in 0 until bigStars.drawn(1)) {
            val presence = bigStars.presence(index, 1)
            if (presence <= 0.01f) continue
            val arm = size.minDimension * (0.05f + 0.08f * split.level(index))
            starMesh.spark(stars[index].x * size.width, stars[index].y * size.height, arm, arm * 0.12f, state.palette.argb(index * 0.3f + genes.walk, saturation = 0.3f, value = 1f, alpha = presence))
        }
        drawMesh(starMesh, BlendMode.Plus)
    }

    override fun onReset() {
        stage.reset()
        twinkles.clear()
        credit = 0f
        stars.forEach { it.reset() }
        comets.clear()
        showerLeft = 0f
        showerCredit = 0f
        lastBeat = -1
    }

    private companion object {
        val DRIFT_X = floatArrayOf(1f, -1f, 0f, 0f)
        val DRIFT_Y = floatArrayOf(0f, 0f, -1f, 1f)
    }
}
