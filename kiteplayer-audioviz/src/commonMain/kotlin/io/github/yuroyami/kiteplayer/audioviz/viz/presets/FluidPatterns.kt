package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.fluid.FluidGrid
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * Drawings built on a simulated liquid. The simulation runs on a small grid on the processor, a few
 * thousand cells, and the result is stretched over the screen. A liquid survives the stretch well,
 * because it has no hard edges to lose. Each one is stirred from places that move.
 */

private const val GRID_WIDTH = 128
private const val GRID_HEIGHT = 72

/** A camera that stays put, so the stretched picture always covers the screen. */
private fun stillTank(seed: Long): Camera2D = Camera2D(wander = 0f, punch = 0.02f, roll = 0f, shake = 0f, cuts = false, seed = seed.toInt())

/** Draws [picture] over the whole canvas, blended smoothly between its cells. */
private fun DrawScope.stretch(picture: PixelImage) {
    drawImage(
        image = picture.image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(picture.width, picture.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Low,
    )
}

/** Any amount of light squeezed under one without a hard ceiling, so thick dye glows rather than clips. */
private fun soft(amount: Float): Float = 1f - exp(-amount.coerceAtLeast(0f))

/** An opaque colour from three channels between 0 and 1. */
private fun opaque(red: Float, green: Float, blue: Float): Int = see(red, green, blue, 1f)

/** A colour that lets [alpha] of itself through, premultiplied the way the picture stores it. */
private fun see(red: Float, green: Float, blue: Float, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    return ((a * 255f + 0.5f).toInt() shl 24) or
        ((red.coerceIn(0f, 1f) * a * 255f + 0.5f).toInt() shl 16) or
        ((green.coerceIn(0f, 1f) * a * 255f + 0.5f).toInt() shl 8) or
        (blue.coerceIn(0f, 1f) * a * 255f + 0.5f).toInt()
}

/**
 * Two chemicals that feed on each other and grow spots, stripes and mazes by themselves, over fog that
 * shows through wherever the pattern is empty. The recipe changes at each supported section boundary, from mazes to spots to
 * worms to coral. A seeder crosses the screen every two visual cycles, dropping a seed on every supported
 * beat, the whole
 * pattern slides sideways eight cells a beat, and a light circling the dish shades it from one side.
 * Drums drop seeds where their band sits, and a drop seeds everywhere at once.
 *
 * This is the Gray-Scott model: one chemical is fed in everywhere, the other eats it and spreads, and
 * how fast the first is fed decides which pattern settles.
 */
internal class ReactionDiffusion : Layered(
    name = "Reaction Diffusion",
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 1_002L, groundKind = GroundKind.Fog, groundDim = 0.8f, camera = stillTank(1_002L)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Spawn),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(1f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.HighHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Pulse, VizProperty.Spawn, VizCurve.Discrete),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
    )
    override val cameraOnEcho: Boolean get() = false

    private val feed = genes.number("Feed", 0.029f, 0.0545f, 0.037f)
    private val kill = genes.number("Kill", 0.057f, 0.065f, 0.06f)
    private val scrollLeft = genes.toggle("Slide left", start = false)
    private val lightPath = genes.choice("Light path", 2)

    private val cells = GRID_WIDTH * GRID_HEIGHT
    private var food = FloatArray(cells) { 1f }
    private var eater = FloatArray(cells)
    private var nextFood = FloatArray(cells)
    private var nextEater = FloatArray(cells)
    private val leftOf = IntArray(GRID_WIDTH) { (it - 1 + GRID_WIDTH) % GRID_WIDTH }
    private val rightOf = IntArray(GRID_WIDTH) { (it + 1) % GRID_WIDTH }
    private val picture = PixelImage(GRID_WIDTH, GRID_HEIGHT)
    private var owed = 0f
    private var seeded = false
    private var grown = 0
    private var lastBeat = -1
    private var slideCredit = 0f
    private var lightAngle = 0f
    private var seederTravel = 0f
    private var seederX = 0.05f
    private var seederY = 0.5f
    private val sparks = Sprites(160, 1_002L)
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (!seeded) {
            seeded = true
            for (index in 0 until 14) seed((index % 5 + 0.5f) / 5f, (index / 5 + 0.5f) / 3f, 6f)
        }
        // A new species at each supported section boundary: mazes, spots, worms or coral.
        if (gestures.section) {
            val species = (random.next() * 4f).toInt().coerceIn(0, 3)
            feed.target = SPECIES[species * 2]
            kill.target = SPECIES[species * 2 + 1]
        }
        seederTravel += state.stepSeconds / (gestures.cycleSeconds * 2f)
        val leg = seederTravel % 2f
        seederX = 0.05f + 0.9f * (if (leg < 1f) leg else 2f - leg)
        seederY = 0.5f + 0.35f * sin(seederTravel * TAU * 0.5f)
        kit.place(1, seederX, seederY)
        val beat = (gestures.cyclePhase * 4f).toInt()
        if (beat != lastBeat && gestures.pulseUsable) {
            lastBeat = beat
            seed(seederX, seederY, 2f + 2f * state.drive)
        }
        // The whole pattern slides sideways, eight cells a beat, while there is a beat to slide on.
        // Without that guard a held note slides the picture on the tracker's guess, which is a beat
        // the music does not have. The credit is dropped rather than banked when the pulse goes,
        // because a tracker that flickers in and out on a held note otherwise saves up enough for
        // one slide about once a second, and one slide moves the whole picture.
        if (gestures.pulseUsable) slideCredit += state.stepSeconds * 8f / gestures.beatSeconds else slideCredit = 0f
        while (slideCredit >= 1f) {
            slideCredit -= 1f
            slide(if (scrollLeft.on) -1 else 1)
        }
        if (gestures.kick > 0f) {
            val x = 0.1f + 0.8f * random.next()
            val y = 0.1f + 0.8f * random.next()
            seed(x, y, 2.5f + 3f * gestures.kick)
            sparks.burst(x, y, gestures.kickSpawn(8), 0.3f, 0.6f, 0.01f, random.next(), Sprite.SPARK)
        }
        val bands = state.frame.bandsRel
        val hit = maxOf(gestures.snare, gestures.hat)
        if (hit > 0f && bands.isNotEmpty()) {
            var loudest = 0
            for (band in bands.indices) if (bands[band] > bands[loudest]) loudest = band
            seed((loudest + 0.5f) / bands.size, random.next(), 1.5f + 2f * hit)
        }
        if (gestures.drop) {
            for (index in 0 until 20) seed(random.next(), random.next(), 3f)
        }
        owed += dt * (300f * state.idle + 900f * state.drive)
        // The pattern takes a few thousand steps to form, so the first second runs extra ones.
        if (grown < WARM_UP) owed += WARM_UP_PER_FRAME
        var steps = 0
        while (owed >= 1f && steps < MOST_STEPS + WARM_UP_PER_FRAME) {
            owed -= 1f
            react(feed.value, kill.value)
            steps++
        }
        grown += steps
        owed = owed.coerceAtMost(1f)
        lightAngle += dt * (0.5f * state.idle + 0.5f * state.frame.motionRate)
        sparks.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    /** Drops a round seed of the eating chemical at [x], [y], each 0 to 1, [radius] cells across. */
    private fun seed(x: Float, y: Float, radius: Float) {
        val middleX = (x * GRID_WIDTH).toInt()
        val middleY = (y * GRID_HEIGHT).toInt()
        val reach = radius.toInt() + 1
        for (dy in -reach..reach) {
            for (dx in -reach..reach) {
                if (dx * dx + dy * dy > radius * radius) continue
                val at = (middleX + dx + GRID_WIDTH) % GRID_WIDTH + (middleY + dy + GRID_HEIGHT) % GRID_HEIGHT * GRID_WIDTH
                eater[at] = 0.9f
                food[at] = 0.3f
            }
        }
    }

    /** Moves the whole pattern one cell sideways, wrapping round, so it travels across the screen. */
    private fun slide(way: Int) {
        for (layer in arrayOf(food, eater)) {
            for (y in 0 until GRID_HEIGHT) {
                val row = y * GRID_WIDTH
                if (way > 0) {
                    val last = layer[row + GRID_WIDTH - 1]
                    layer.copyInto(layer, row + 1, row, row + GRID_WIDTH - 1)
                    layer[row] = last
                } else {
                    val first = layer[row]
                    layer.copyInto(layer, row, row + 1, row + GRID_WIDTH)
                    layer[row + GRID_WIDTH - 1] = first
                }
            }
        }
    }

    private fun react(feed: Float, kill: Float) {
        for (y in 0 until GRID_HEIGHT) {
            val row = y * GRID_WIDTH
            val up = ((y - 1 + GRID_HEIGHT) % GRID_HEIGHT) * GRID_WIDTH
            val down = ((y + 1) % GRID_HEIGHT) * GRID_WIDTH
            for (x in 0 until GRID_WIDTH) {
                val left = leftOf[x]
                val right = rightOf[x]
                val here = row + x
                val a = food[here]
                val b = eater[here]
                val spreadA = 0.2f * (food[row + left] + food[row + right] + food[up + x] + food[down + x]) +
                    0.05f * (food[up + left] + food[up + right] + food[down + left] + food[down + right]) - a
                val spreadB = 0.2f * (eater[row + left] + eater[row + right] + eater[up + x] + eater[down + x]) +
                    0.05f * (eater[up + left] + eater[up + right] + eater[down + left] + eater[down + right]) - b
                val meeting = a * b * b
                nextFood[here] = (a + spreadA - meeting + feed * (1f - a)).coerceIn(0f, 1f)
                nextEater[here] = (b + 0.5f * spreadB + meeting - (kill + feed) * b).coerceIn(0f, 1f)
            }
        }
        val oldFood = food
        food = nextFood
        nextFood = oldFood
        val oldEater = eater
        eater = nextEater
        nextEater = oldEater
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val light = 0.14f + 1.25f * state.lift
        // The lamp circles the dish or swings across it, and each cell is lit by how it slopes towards it.
        val angle = if (lightPath.value == 0) lightAngle else sin(lightAngle) * 1.5f
        val towardsX = cos(angle)
        val towardsY = sin(angle)
        var pixel = 0
        for (y in 0 until GRID_HEIGHT) {
            val row = y * GRID_WIDTH
            val up = ((y - 1 + GRID_HEIGHT) % GRID_HEIGHT) * GRID_WIDTH
            val down = ((y + 1) % GRID_HEIGHT) * GRID_WIDTH
            for (x in 0 until GRID_WIDTH) {
                val amount = eater[row + x]
                // A steep ramp gives the pattern a clean edge instead of a blur.
                val ramp = ((amount - 0.08f) / 0.22f).coerceIn(0f, 1f)
                val level = ramp * ramp * (3f - 2f * ramp)
                val slope = (eater[row + leftOf[x]] - eater[row + rightOf[x]]) * towardsX +
                    (eater[up + x] - eater[down + x]) * towardsY
                val shade = (0.8f + slope * 3f).coerceIn(0.35f, 1.4f) * light
                val colour = state.palette.ramp(level)
                // Empty cells let the fog through.
                picture.pixels[pixel++] = see(colour.red * shade, colour.green * shade, colour.blue * shade, 0.12f + 0.88f * level)
            }
        }
        picture.upload()
        stretch(picture)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        drawCircle(state.palette.cap.copy(alpha = 0.12f + 1.15f * state.lift), size.minDimension * 0.012f, Offset(seederX * size.width, seederY * size.height))
        with(sparks) { drawSprites(state.palette, genes.walk) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
    }

    override fun onReset() {
        food.fill(1f)
        eater.fill(0f)
        owed = 0f
        seeded = false
        grown = 0
        lastBeat = -1
        slideCredit = 0f
        lightAngle = 0f
        seederTravel = 0f
        sparks.clear()
        comets.clear()
    }

    private companion object {
        /** The most steps taken in one frame, so a slow frame cannot snowball. */
        const val MOST_STEPS = 18

        /** Steps run while the pattern first forms, and how many extra each frame gets meanwhile. */
        const val WARM_UP = 900
        const val WARM_UP_PER_FRAME = 30

        /** Feed and kill for a maze, spots, worms and coral. */
        val SPECIES = floatArrayOf(0.029f, 0.057f, 0.037f, 0.06f, 0.046f, 0.063f, 0.0545f, 0.062f)
    }
}

/**
 * Smoke rising from two emitters that cross back and forth along the bottom, blown by a wind that
 * changes side at each supported section boundary and curling under a ceiling set by how loud the song has been. A lamp
 * circling the tank lights the smoke from one side, embers rise through it, a kick lets out a puff,
 * and a drop lifts the ceiling out of the way. Two orbiting black holes curl and absorb the smoke;
 * smoke condenses and stretches around their moving horizons, then disappears through them.
 */
internal class SmokeRise : Layered(
    name = "Smoke Rise",
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 1_003L, camera = stillTank(1_003L)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bass, VizProperty.Spawn),
        VizDrive(VizDriver.Level, VizProperty.Spawn),
        VizDrive(VizDriver.Bass, VizProperty.Shape),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.3f)),
        VizDrive(VizDriver.SlowLevel, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

    private val emitters = VizParam("Second emitter", 0f, 1f, 1f).apply { step = 1f; toggle = true }
    private val windGene = VizParam("Wind", -1f, 1f, 0.3f)
    private val lightPath = VizParam("Swinging light", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val ceilingGene = VizParam("Ceiling", 0.1f, 0.45f, 0.25f)
    private val holes = VizParam("Black holes", 0f, 2f, 2f).apply { step = 1f }
    private val holeSize = VizParam("Influence size", 0.035f, 0.14f, 0.085f)
    private val orbit = VizParam("Travel speed", 0f, 2f, 1f)
    private val curl = VizParam("Vortex curl", 0f, 2.5f, 1f)
    private val pull = VizParam("Attraction", 0f, 2f, 0.8f)
    private val lens = VizParam("Lensing", 0f, 2f, 1f)
    private val capture = VizParam("Horizon absorption", 0f, 2f, 1f)
    private val smokeAmount = VizParam("Smoke density", 0.3f, 2f, 1f)
    private val smokeDetail = VizParam("Smoke detail", 0f, 1f, 0.65f)
    private val brightness = VizParam("Brightness", 0.2f, 1.5f, 1f)
    private val emberAmount = VizParam("Embers", 0f, 1f, 0.8f)
    override val params: List<VizParam> = listOf(holes, holeSize, orbit, curl, pull, lens,
        capture, smokeAmount, smokeDetail, brightness, emberAmount, emitters, windGene,
        lightPath, ceilingGene)
    private val vortices = SmokeVortices()

    private val grid = FluidGrid(GRID_WIDTH, GRID_HEIGHT, dyeChannels = 1)
    private val picture = PixelImage(GRID_WIDTH, GRID_HEIGHT)
    private var puffs = 0f
    private var travel = 0f
    private var lightAngle = 0f
    private var shimmer = 0f
    private val wind = Slew(maxPerSecond = 0.4f)
    private var liftHold = 0f
    private val lifted = Envelope(attackPerSecond = 2f, releasePerSecond = 0.5f)
    private val ceiling = Slew(maxPerSecond = 0.1f)
    private val emitterX = FloatArray(2) { 0.5f }
    private val embers = Sprites(200, 1_003L)
    private var emberCredit = 0f

    override fun advance(state: VizRenderState) {
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.1f)
        val frame = state.frame
        vortices.advance(state, orbit.value, holeSize.value,
            maxOf(gestures.kickAccent, gestures.snareAccent * 0.75f))
        for (hole in 0 until holes.value.toInt()) kit.place(2 + hole, vortices.x[hole], vortices.y[hole], parallax = 0f)
        travel += state.stepSeconds * TAU / (gestures.cycleSeconds * 4f)
        for (index in 0 until 2) emitterX[index] = 0.5f + 0.4f * sin(travel + index * PI.toFloat())
        kit.place(1, emitterX[0], 0.92f)
        wind.advance(windGene.value * if (gestures.sections % 2 == 0) 1f else -1f, dt)
        if (gestures.drop) liftHold = gestures.cycleSeconds * 2f
        liftHold -= dt
        lifted.advance(if (liftHold > 0f) 1f else 0f, dt)
        // The ceiling hangs lower after a quiet stretch, and a drop lifts it out of the way.
        ceiling.advance(ceilingGene.value + 0.3f * (1f - frame.loudLong), dt)
        val count = if (emitters.value >= 0.5f) 2 else 1
        puffs += dt * (12f * state.idle + 36f * frame.bassRel * (0.3f + 0.7f * state.drive))
        while (puffs >= 1f) {
            puffs -= 1f
            val index = (random.next() * count).toInt().coerceIn(0, 1)
            grid.splat(emitterX[index] + random.signed() * 0.04f, 0.92f, 0.07f, random.signed() * 12f, -30f, (0.5f + frame.bassRel) * smokeAmount.value)
        }
        if (gestures.kick > 0f) {
            for (index in 0 until count) grid.splat(emitterX[index], 0.95f, 0.1f, random.signed() * 20f, -120f * (0.5f + gestures.kick), 1.2f * gestures.kick * smokeAmount.value)
        }
        // In a silence a thin thread still rises, so the picture never freezes.
        grid.splat(emitterX[0], 0.97f, 0.03f, 0f, -10f, 0.4f * dt * smokeAmount.value)
        grid.rise(strength = 80f + 90f * state.mood, deltaSeconds = dt)
        blow(wind.value * (8f + 10f * state.drive) * dt)
        cap((ceiling.value * (1f - lifted.value)).coerceIn(0f, 0.6f))
        vortices.stir(grid, dt, holes.value.toInt(), kit.aspect, curl.value, pull.value, state.motionScale)
        grid.step(dt, curl = 8f + 28f * state.mood, dyeKept = 0.88f, speedKept = 0.8f)
        vortices.condense(grid, dt, holes.value.toInt(), kit.aspect, curl.value, pull.value,
            capture.value, state.motionScale)
        lightAngle += dt * (0.4f * state.idle + 0.5f * frame.motionRate)
        shimmer += dt * (1f * state.idle + 2.5f * state.drive)
        emberCredit += dt * (30f * state.idle + 60f * state.drive) * emberAmount.value
        while (emberCredit >= 1f) {
            emberCredit -= 1f
            val index = (random.next() * count).toInt().coerceIn(0, 1)
            embers.burst(emitterX[index], 0.95f, 1, 0.3f, 2.5f, 0.02f, 0.05f + 0.1f * random.next(), Sprite.SPARK, UP + wind.value * 0.3f, 0.5f)
        }
        vortices.carryEmbers(embers.pool, dt, holes.value.toInt(), kit.aspect, curl.value,
            pull.value, capture.value, state.motionScale)
        embers.advance(dt, drag = 0.3f, gravity = -0.1f)
    }

    /** Adds the wind to the whole tank. */
    private fun blow(amount: Float) {
        val speeds = grid.velocityX
        for (index in speeds.indices) speeds[index] += amount
    }

    /** Stops smoke rising past the ceiling at [height], turning it sideways so it curls underneath. */
    private fun cap(height: Float) {
        val top = (height * GRID_HEIGHT).toInt().coerceIn(0, GRID_HEIGHT - 1)
        for (y in 0..top) {
            for (x in 0 until GRID_WIDTH) {
                val at = grid.index(x, y)
                val rising = grid.velocityY[at]
                if (rising >= 0f) continue
                grid.velocityX[at] += rising * if (x < GRID_WIDTH / 2) 0.5f else -0.5f
                grid.velocityY[at] = 0f
            }
        }
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val ground = state.palette.background
        val smoke = grid.dye[0]
        val light = 0.5f + 0.5f * state.lift
        // The lamp circles the tank or swings across it; the edge of the smoke that faces it is lit.
        val angle = if (lightPath.value < 0.5f) lightAngle else sin(lightAngle) * 1.5f
        val towardsX = cos(angle)
        val towardsY = sin(angle)
        var pixel = 0
        for (y in 0 until GRID_HEIGHT) {
            for (x in 0 until GRID_WIDTH) {
                vortices.lens((x + 0.5f) / GRID_WIDTH, (y + 0.5f) / GRID_HEIGHT,
                    holes.value.toInt(), kit.aspect, lens.value)
                val sx = vortices.sampleX
                val sy = vortices.sampleY
                val here = sampleSmoke(smoke, sx, sy)
                val colour = state.palette.ramp(1f - sy.coerceIn(0f, 1f))
                val slope = (sampleSmoke(smoke, sx - 1f / GRID_WIDTH, sy) -
                    sampleSmoke(smoke, sx + 1f / GRID_WIDTH, sy)) * towardsX +
                    (sampleSmoke(smoke, sx, sy - 1f / GRID_HEIGHT) -
                    sampleSmoke(smoke, sx, sy + 1f / GRID_HEIGHT)) * towardsY
                val haze = 0.12f + 0.07f * sin(shimmer + sx * GRID_WIDTH * 0.15f - sy * GRID_HEIGHT * 0.11f)
                val shade = (0.75f + slope * (0.4f + smokeDetail.value * 0.7f)).coerceIn(0.35f, 1.35f)
                val thick = ((haze + soft(here * 4f) * light * shade) * brightness.value).coerceAtMost(1f)
                picture.pixels[pixel++] = opaque(
                    ground.red + (colour.red - ground.red) * thick,
                    ground.green + (colour.green - ground.green) * thick,
                    ground.blue + (colour.blue - ground.blue) * thick,
                )
            }
        }
        picture.upload()
        stretch(picture)
    }

    private fun sampleSmoke(smoke: FloatArray, x: Float, y: Float): Float {
        val px = (x * GRID_WIDTH - 0.5f).coerceIn(0f, GRID_WIDTH - 1f)
        val py = (y * GRID_HEIGHT - 0.5f).coerceIn(0f, GRID_HEIGHT - 1f)
        val left = px.toInt(); val top = py.toInt()
        val right = (left + 1).coerceAtMost(GRID_WIDTH - 1)
        val bottom = (top + 1).coerceAtMost(GRID_HEIGHT - 1)
        val tx = px - left; val ty = py - top
        val a = smoke[grid.index(left, top)] * (1f - tx) + smoke[grid.index(right, top)] * tx
        val b = smoke[grid.index(left, bottom)] * (1f - tx) + smoke[grid.index(right, bottom)] * tx
        return a * (1f - ty) + b * ty
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(embers) { drawSprites(state.palette, genes.walk, alpha = (0.5f + 0.4f * state.lift) * emberAmount.value) }
    }

    override fun onReset() {
        grid.clear()
        vortices.reset()
        puffs = 0f
        travel = 0f
        lightAngle = 0f
        shimmer = 0f
        wind.reset()
        liftHold = 0f
        lifted.reset()
        ceiling.reset()
        embers.clear()
        emberCredit = 0f
    }
}
