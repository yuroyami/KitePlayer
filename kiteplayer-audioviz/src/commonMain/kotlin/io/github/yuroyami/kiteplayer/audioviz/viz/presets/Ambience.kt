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
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
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
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Soft lobes of light on three depths, crossing the screen on wide looping paths.
 *
 * Near lobes are bigger, move further with the camera and pass in front of far ones. A kick splits
 * the loud lobes in two for a beat, a drop sends the whole cloud rushing outward for a bar, and the
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
        stage.advance(dt)
        phase += dt * TAU / (gestures.barSeconds * 1.5f) * (0.75f + 0.6f * state.drive)
        bulge.kick(gestures.kickHit * 6f)
        bulge.advance(dt)
        if (gestures.drop) rush = 1f
        rush = (rush - dt / gestures.barSeconds).coerceAtLeast(0f)
        val loud = state.percentile(0.7f)
        for (index in 0 until MOST) {
            splitting[index] = (splitting[index] - dt / gestures.beatSeconds).coerceAtLeast(0f)
            if (gestures.kickHit > 0f && state.frame.bandsRel.sampleAt(index / MOST.toFloat()) > loud) splitting[index] = 1f
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
        dustOwed += dt * (8f + 26f * state.air)
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
 * the screen. Drops that land start rings on the pool, the wind changes side every phrase, a big drop
 * arcs over the middle every bar, and the spray is mirrored in the pool below.
 */
internal class Fountain : Layered(
    name = "Fountain",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 301L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.07f, seed = 301)),
) {
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
        // The wind's side comes from the gene and flips every phrase.
        wind.advance(windGene.value * if (gestures.phrases % 2 == 0) 1f else -1f, dt)
        val count = fountains.count(1)
        trickle += dt * (40f + 260f * state.drive)
        while (trickle >= 1f) {
            trickle -= 1f
            spray((random.next() * count).toInt().coerceAtMost(count - 1), 0.75f + 0.35f * random.next(), 1)
        }
        if (gestures.kickHit > 0f) spray(0, 1.35f, (18 + 20 * gestures.kickHit).toInt())
        if (gestures.snareHit > 0f) for (index in 1 until count) spray(index, 1.25f, 12)
        if (gestures.drop) geyser = gestures.barSeconds
        if (geyser > 0f) {
            geyser -= dt
            spray(0, 1.8f, 6)
        }
        // One big drop a bar arcs from one side to the other, high over the middle.
        if (gestures.bar) {
            val from = if (fromLeft) 0.1f else 0.9f
            leaders.spawn(from, POOL, 1f - from, POOL, gestures.barSeconds * 0.9f, PathShape.Arc, if (fromLeft) -0.62f else 0.62f, 0.028f, random.next(), 0f, Sprite.GLOW)
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
 * way for a moment, the tilt changes side every phrase, and a drop lets the floor fall away for a bar.
 */
internal class Gravity : Layered(
    name = "Gravity",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 302L, groundKind = GroundKind.Fog, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.06f, seed = 302)),
) {
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
        if (gestures.phrase) side = -side
        nudge.kick(gestures.snareHit * 3f * -side)
        nudge.advance(dt)
        tilt.advance(side * slope.value, dt)
        val lean = tilt.value + 0.3f * nudge.value
        if (gestures.drop) fall = 1f
        fall = (fall - dt / gestures.barSeconds).coerceAtLeast(0f)
        // Thrown up and back in one beat: both halves come from the tempo.
        val pull = 8f / gestures.beatSeconds
        val throwSpeed = pull * gestures.beatSeconds * 0.5f
        val bands = state.frame.bandsRel
        val kick = gestures.kickHit
        val soft = if (kick > 0f) 0f else state.frame.kick
        val live = balls.count(36, 18)
        for (index in 0 until MOST) {
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(index.toFloat() / MOST)
            if (kick > 0f) vh[index] += kick * throwSpeed * (0.4f + 0.8f * energy)
            if (soft > 0f) vh[index] += soft * throwSpeed * 0.3f * energy
            if (gestures.hatHit > 0f) vh[index] += gestures.hatHit * throwSpeed * 0.12f * energy
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
        stage.advance(dt)
        // The whole field slides one screen every four bars.
        var dx = 0f
        var dy = 0f
        for (option in 0 until 4) {
            val share = drift.weight(option)
            dx += share * DRIFT_X[option]
            dy += share * DRIFT_Y[option]
        }
        val step = dt / (gestures.barSeconds * 4f)
        twinkles.advance(dt, drag = 1.2f)
        val pool = twinkles.pool
        for (slot in 0 until pool.capacity) {
            if (pool.life[slot] <= 0f) continue
            pool.x[slot] = wrap(pool.x[slot] + dx * step)
            pool.y[slot] = wrap(pool.y[slot] + dy * step)
        }
        credit += dt * (60f + 390f * state.air * (0.3f + 0.7f * state.mood)) + gestures.hatHit * 30f
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
        if (gestures.kickHit > 0f) {
            var loudest = 0
            for (index in 1 until 3) if (split.level(index) > split.level(loudest)) loudest = index
            twinkles.burst(stars[loudest].x, stars[loudest].y, 14, 0.35f, 0.8f, 0.03f, loudest / 3f, Sprite.SPARK)
        }
        val beat = (gestures.barPhase * 4f).toInt()
        val newBeat = beat != lastBeat
        lastBeat = beat
        val rate = cometRate.value
        if (gestures.snareHit > 0f || (rate >= 1 && gestures.bar) || (rate >= 2 && newBeat && beat % 2 == 0)) launchComet()
        if (gestures.drop) showerLeft = gestures.barSeconds
        if (showerLeft > 0f) {
            showerLeft -= dt
            showerCredit += dt * 20f / gestures.barSeconds
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

/**
 * Lines filling the whole height and travelling across it one screen every two bars, with a second
 * sheet behind that the snare tilts, a small boat riding the loudest line, and swells that cross every
 * line on the kick.
 */
internal class Wave : Layered(
    name = "Wave",
    family = VizFamily.Ambience,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 304L, groundKind = GroundKind.Water, groundDim = 0.7f, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 304)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.62f, livelyTrail = 0.5f)

    private val lines = genes.choice("Lines", 3, start = 1)
    private val sheets = genes.toggle("Second sheet", start = true)
    private val tiltGene = genes.number("Tilt", -0.25f, 0.25f, 0.08f)
    private val backwards = genes.toggle("Travel backwards", start = false)

    // Moves the whole sheet up and down a little, so the picture eight seconds on is a different one.
    private val stage = Stage(reachX = 0f, reachY = 0.1f, start = 0.3f)
    private var travel = 0f
    private var ripple = 0f
    private val swellAt = FloatArray(SWELLS) { -1f }
    private var nextSwell = 0
    private val lean = Spring(stiffness = 40f, damping = 0.6f)
    private var dropHold = 0f
    private val full = Envelope(attackPerSecond = 4f, releasePerSecond = 1f)
    private var boatTravel = 0f
    private var boatX = 0f
    private var boatY = 0.5f
    private var boatLine = 0.5f
    private val foam = Sprites(300, 1_304L)
    private var foamCredit = 0f
    private val boatMesh = TriangleMesh(maxVertices = 64)
    private val path = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        val way = if (backwards.on) -1f else 1f
        travel += dt / (gestures.barSeconds * 2f) * way
        ripple += dt * 1.2f * state.tempo
        lean.kick(gestures.snareHit * 2f)
        lean.advance(dt)
        if (gestures.drop) dropHold = gestures.barSeconds
        dropHold -= dt
        full.advance(if (dropHold > 0f) 1f else 0f, dt)
        if (gestures.kickHit > 0f) {
            swellAt[nextSwell] = 0f
            nextSwell = (nextSwell + 1) % SWELLS
        }
        for (slot in 0 until SWELLS) {
            if (swellAt[slot] < 0f) continue
            swellAt[slot] += dt / (gestures.beatSeconds * 3f)
            if (swellAt[slot] > 1.2f) swellAt[slot] = -1f
        }
        val bands = state.frame.bandsRel
        var loudest = 0.5f
        if (bands.isNotEmpty()) {
            var best = 0
            for (index in bands.indices) if (bands[index] > bands[best]) best = index
            loudest = best.toFloat() / (bands.size - 1)
        }
        boatLine += (loudest - boatLine) * (dt * 1.5f).coerceAtMost(1f)
        boatTravel += dt / (gestures.barSeconds * 1.5f) * way
        boatX = wrap(boatTravel)
        boatY = lineY(boatLine, boatX, state, 1f)
        kit.place(0, boatX, boatY)
        foamCredit += dt * (6f + 30f * state.drive) + gestures.hatHit * 4f
        while (foamCredit >= 1f) {
            foamCredit -= 1f
            val along = random.next()
            val at = random.next()
            foam.burst(at, lineY(along, at, state, 1f), 1, 0.03f, 1.2f, 0.01f, along, Sprite.GLOW)
        }
        if (gestures.kickHit > 0f) foam.burst(boatX, boatY, 12, 0.35f, 0.8f, 0.012f, boatLine, Sprite.GLOW, UP, 1.4f)
        foam.advance(dt, drag = 0.6f, gravity = 0.3f)
    }

    /** Where line [along] (0 the top, 1 the bottom) sits at [x], as a share of the height. */
    private fun lineY(along: Float, x: Float, state: VizRenderState, speed: Float): Float {
        val bands = state.frame.bandsRel
        val energy = if (bands.isEmpty()) 0f else bands.sampleAt(along)
        val reach = (0.025f + 0.16f * energy + 0.05f * state.body) * (1f + 1.2f * full.value)
        val phase = (x + travel * speed) * TAU * (1.2f + along * 0.6f) + ripple * (0.9f + along) + along * 5f
        var y = stage.y - 0.45f + 0.9f * along + sin(phase) * reach
        for (slot in 0 until SWELLS) {
            val at = swellAt[slot]
            if (at < 0f) continue
            val distance = (x - at) / 0.08f
            y -= 0.06f * exp(-distance * distance)
        }
        val scope = state.frame.scope
        return y + (if (scope.isEmpty()) 0f else scope.sampleAt(x)) * 0.08f
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val walk = genes.walk
        val lift = 0.3f + 0.7f * state.lift
        val back = sheets.weight(1)
        if (back > 0.01f) {
            rotate((tiltGene.value + 0.12f * lean.value) * 57.29578f * 0.5f, center) {
                drawSheet(state, 12, 1f, 0.6f, 0.5f * back * lift, walk + 0.5f, 0.6f)
            }
        }
        drawSheet(state, lines.drawn(12, 6), lines.count(12, 6).toFloat(), 1f, lift, walk, 1f)
    }

    private fun DrawScope.drawSheet(state: VizRenderState, drawn: Int, count: Float, speed: Float, alpha: Float, tint: Float, thin: Float) {
        val bands = state.frame.bandsRel
        for (line in 0 until drawn) {
            val presence = if (line < count) 1f else (count + 1f - line).coerceIn(0f, 1f)
            if (presence <= 0.01f) continue
            val along = line.toFloat() / (drawn - 1).coerceAtLeast(1)
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(along)
            path.reset()
            for (step in 0..STEPS) {
                val x = step.toFloat() / STEPS
                val y = lineY(along, x, state, speed) * size.height
                if (step == 0) path.moveTo(0f, y) else path.lineTo(x * size.width, y)
            }
            drawPath(
                path,
                state.palette.cycled(along + tint, value = 0.35f + 0.65f * energy, alpha = (alpha * presence).coerceIn(0f, 1f)),
                style = Stroke((size.minDimension * (0.006f + 0.01f * state.body) * thin).coerceAtLeast(1f), cap = StrokeCap.Round),
            )
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // The boat: a small fan of the spectrum riding the loudest line.
        boatMesh.clear()
        val at = Offset(boatX * size.width, boatY * size.height)
        val reach = size.minDimension * 0.06f
        val bands = state.frame.bandsRel
        for (spoke in 0 until 8) {
            val along = spoke / 7f
            val value = if (bands.isEmpty()) 0f else bands.sampleAt(along)
            val angle = -1.2f + 2.4f * along
            val tip = polar(at, angle, reach * (0.3f + 0.7f * value))
            val colour = state.palette.argb(along + genes.walk, value = 1f, alpha = 0.9f)
            val left = polar(at, angle - 0.12f, reach * 0.1f)
            val right = polar(at, angle + 0.12f, reach * 0.1f)
            boatMesh.triangle(boatMesh.vertex(left.x, left.y, colour), boatMesh.vertex(tip.x, tip.y, colour), boatMesh.vertex(right.x, right.y, colour))
        }
        drawMesh(boatMesh, BlendMode.Plus)
        with(foam) { drawSprites(state.palette, genes.walk, alpha = 0.3f + 0.6f * state.lift, saturation = 0.3f) }
    }

    override fun onReset() {
        stage.reset()
        travel = 0f
        ripple = 0f
        swellAt.fill(-1f)
        nextSwell = 0
        lean.reset()
        dropHold = 0f
        full.reset()
        boatTravel = 0f
        boatLine = 0.5f
        foam.clear()
        foamCredit = 0f
    }

    private companion object {
        const val SWELLS = 6
        const val STEPS = 72
    }
}

/**
 * Bolts from a core on an orbit, reaching the edges and branching twice, their echoes turning. A snare
 * flashes them and sends a strike to the nearest edge, the bolts step round every bar, and a drop
 * brings a second core, the two arcing to each other.
 */
internal class Ionizer : Layered(
    name = "Ionizer",
    family = VizFamily.Ambience,
    bucket = VizEnergy.High,
    kit = Kit(seed = 305L, groundKind = GroundKind.Fog, detailKind = DetailKind.Hatch, detailStrength = 0.6f, camera = Camera2D(wander = 0.08f, seed = 305)),
) {
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.82f, livelyTrail = 0.64f, calmSpin = 0.2f, livelySpin = 0.8f)

    private val bolts = genes.choice("Bolts", 3, start = 1)
    private val branches = genes.choice("Branches", 3, start = 1)
    private val twin = genes.toggle("Second core", start = false)
    private val reach = genes.number("Reach", 0.9f, 1.4f, 1.15f)

    private val stage = Stage(start = 1.3f)
    private val cores = Array(2) { Orbiter(radiusX = 0.32f, radiusY = 0.24f, lapsPerBar = 0.8f, phase = it * 0.5f) }
    private val angle = FloatArray(MOST) { TAU * it / MOST }
    private val wander = Noise1(seed = 4_211)
    private var time = 0f
    private var flash = 0f
    private var stepTurn = 0f
    private val stepped = Slew(maxPerSecond = 2f)
    private val punch = Spring(stiffness = 220f, damping = 0.45f)
    private var dropHold = 0f
    private val second = Envelope(attackPerSecond = 3f, releasePerSecond = 0.8f)
    private val strikes = Travellers(8)
    private val strikeMesh = TriangleMesh(maxVertices = 8 * 12 + 8)
    private val comets = Comets(kind = Sprite.SPARK, size = 0.04f)
    private val sparks = Sprites(320, 1_305L)
    private val dust = Swarm(50, 2_305L)
    private val dustMesh = TriangleMesh(maxVertices = 50 * 7 + 8)
    private val main = Path()
    private val branch = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        time += dt * state.tempo
        flash = maxOf(flash - dt * 3.5f, gestures.snareHit)
        punch.kick(gestures.kickHit * 6f)
        punch.advance(dt)
        if (gestures.bar) stepTurn += TAU / MOST * 3f
        stepped.advance(stepTurn, dt)
        if (gestures.drop) dropHold = gestures.barSeconds * 2f
        dropHold -= dt
        second.advance(if (dropHold > 0f || twin.on) 1f else 0f, dt)
        for (index in 0 until 2) {
            cores[index].centreX = stage.x
            cores[index].centreY = stage.y
            cores[index].advance(state, gestures)
            kit.place(index, cores[index].x, cores[index].y)
        }
        for (bolt in 0 until MOST) angle[bolt] += wander.at(time + bolt * 3.7f) * dt * 0.9f * state.tempo
        val core = cores[0]
        if (gestures.snareHit > 0f) {
            // A strike to the nearest edge.
            val toX = if (core.x < 0.5f) -0.1f else 1.1f
            strikes.spawn(core.x, core.y, toX, core.y + random.signed() * 0.3f, gestures.beatSeconds * 0.8f, PathShape.Wave, 0.04f, 0.03f, random.next(), 0f, Sprite.SPARK)
        }
        strikes.advance(dt)
        if (gestures.hatHit > 0f) {
            val bolt = (random.next() * bolts.count(9, 4)).toInt()
            val a = angle[bolt] + stepped.value
            val length = 0.5f * reach.value
            sparks.burst(core.x + sin(a) * length / kit.aspect, core.y - kotlin.math.cos(a) * length, 5, 0.3f, 0.5f, 0.01f, random.next(), Sprite.SPARK)
        }
        sparks.advance(dt, drag = 1f)
        dust.targetX = core.x
        dust.targetY = core.y
        dust.advance(dt, speed = 0.2f + 0.5f * state.drive)
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
        kit.follow(3, strikes)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val lift = 0.3f + 0.7f * state.lift
        val count = bolts.drawn(9, 4)
        val width = (size.minDimension * 0.004f).coerceAtLeast(1.2f)
        for (index in 0 until 2) {
            val share = if (index == 0) 1f else second.value
            if (share <= 0.01f) continue
            val at = Offset(cores[index].x * size.width, cores[index].y * size.height)
            val length = sceneRadius * reach.value * (0.55f + 0.3f * state.air + 0.25f * flash)
            for (bolt in 0 until count) {
                val presence = bolts.presence(bolt, 9, 4) * share
                if (presence <= 0.01f) continue
                val alpha = ((0.15f + 0.45f * lift + 0.4f * flash) * presence).coerceIn(0f, 1f)
                val colour = state.palette.cycled(bolt.toFloat() / count + time * 0.08f + genes.walk, saturation = 0.45f, alpha = alpha)
                strike(state, at, angle[bolt] + stepped.value + index * 0.4f, length, bolt, colour, width)
            }
            val glow = (size.minDimension * (0.07f + 0.16f * flash + 0.05f * punch.value.coerceIn(0f, 1.5f))).coerceAtLeast(1f)
            drawCircle(
                Brush.radialGradient(0f to state.palette.cap.copy(alpha = ((0.15f + 0.6f * flash) * share).coerceIn(0f, 1f)), 1f to Color.Transparent, center = at, radius = glow),
                glow,
                at,
            )
        }
        val pair = second.value
        if (pair > 0.01f) {
            // The two cores arc to each other.
            val from = Offset(cores[0].x * size.width, cores[0].y * size.height)
            val to = Offset(cores[1].x * size.width, cores[1].y * size.height)
            main.reset()
            main.moveTo(from.x, from.y)
            for (step in 1..12) {
                val along = step / 12f
                val jitter = wander.at(time * 3f + step * 1.7f) * size.minDimension * 0.05f * sin(along * 3.1415927f)
                main.lineTo(from.x + (to.x - from.x) * along + jitter, from.y + (to.y - from.y) * along - jitter)
            }
            drawPath(main, state.palette.cap.copy(alpha = (0.7f * pair * lift).coerceIn(0f, 1f)), style = Stroke(width * 1.5f, cap = StrokeCap.Round))
        }
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    /** One bolt out from [from], wandering more near the tip, with up to two levels of branches. */
    private fun DrawScope.strike(state: VizRenderState, from: Offset, heading: Float, length: Float, seed: Int, colour: Color, width: Float) {
        main.reset()
        main.moveTo(from.x, from.y)
        branch.reset()
        val depth = branches.value
        for (step in 1..SEGMENTS) {
            val along = step.toFloat() / SEGMENTS
            val wobble = wander.at(time * (1f + 3f * state.air) + seed * 13f + step * 2.1f) * (0.12f + 0.2f * state.air) * along
            val point = polar(from, heading + wobble, length * along)
            main.lineTo(point.x, point.y)
            if (depth > 0 && (step == SEGMENTS / 3 || step == SEGMENTS * 2 / 3)) {
                val side = if (step == SEGMENTS / 3) 0.6f else -0.6f
                twig(point, heading + wobble + side, length * 0.35f * (1f - along * 0.5f), seed + step, depth)
            }
        }
        drawPath(main, colour, style = Stroke(width, cap = StrokeCap.Round))
        if (depth > 0) drawPath(branch, colour.copy(alpha = colour.alpha * 0.7f), style = Stroke(width * 0.6f, cap = StrokeCap.Round))
    }

    private fun twig(from: Offset, heading: Float, length: Float, seed: Int, depth: Int) {
        branch.moveTo(from.x, from.y)
        var last = from
        for (step in 1..5) {
            val along = step / 5f
            val wobble = wander.at(time * 2f + seed * 7f + step * 1.3f) * 0.3f * along
            last = polar(from, heading + wobble, length * along)
            branch.lineTo(last.x, last.y)
            if (depth > 1 && step == 3) {
                branch.moveTo(last.x, last.y)
                val tip = polar(last, heading - 0.7f, length * 0.4f)
                branch.lineTo(tip.x, tip.y)
                branch.moveTo(last.x, last.y)
            }
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        dustMesh.clear()
        for (index in 0 until dust.count) {
            dustMesh.glow(dust.x[index] * size.width, dust.y[index] * size.height, size.minDimension * 0.005f, state.palette.argb(0.6f + index * 0.01f + genes.walk, saturation = 0.4f, value = 1f, alpha = 0.3f + 0.4f * state.lift))
        }
        drawMesh(dustMesh, BlendMode.Plus)
        drawTravellers(strikes, strikeMesh, state.palette, genes.walk)
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        cores.forEach { it.reset() }
        for (index in 0 until MOST) angle[index] = TAU * index / MOST
        time = 0f
        flash = 0f
        stepTurn = 0f
        stepped.reset()
        punch.reset()
        dropHold = 0f
        second.reset()
        strikes.clear()
        comets.clear()
        sparks.clear()
        dust.scatter()
    }

    private companion object {
        const val MOST = 17
        const val SEGMENTS = 10
    }
}
