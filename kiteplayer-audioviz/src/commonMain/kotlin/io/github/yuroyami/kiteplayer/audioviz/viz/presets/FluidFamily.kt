package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
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

/** The band that rose most since the last frame, which is where a new sound just arrived. */
private fun risingBand(now: FloatArray, before: FloatArray): Int {
    var best = 0
    var most = -1f
    for (band in now.indices) {
        val rise = now[band] - before.getOrElse(band) { 0f }
        if (rise > most) {
            most = rise
            best = band
        }
    }
    return best
}

/** -1, 0 or 1: which way a light at [angle] sits along one axis, for finding the neighbour it comes from. */
private fun towards(component: Float): Int = if (component > 0.38f) 1 else if (component < -0.38f) -1 else 0

/**
 * Coloured dye in a tank of moving water, stirred from places that move. A kick shoves the water up
 * from a point sliding along the bottom, hats and snares drip dye in from the top, and a stirrer
 * crosses the tank every two bars dragging the water along its path. Two springs pour dye in from the
 * top and bottom, the whole tank turns slowly the other way each phrase, and up to three discs drift
 * through it as obstacles the dye has to flow round. The low half of the spectrum pours one colour and the
 * high half another, and a drop shoves the water in from every edge at once.
 */
internal class StableFluids : Layered(
    name = "Stable Fluids",
    family = VizFamily.Fluid,
    bucket = VizEnergy.High,
    kit = Kit(seed = 1_001L, camera = stillTank(1_001L)),
) {
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false

    private val obstacles = genes.choice("Obstacles", 3, start = 1)
    private val stirPath = genes.choice("Stirrer path", 3)
    private val colourRule = genes.toggle("Colour by half", start = true)
    private val curl = genes.number("Curl", 0.6f, 1.6f, 1f)

    private val grid = FluidGrid(GRID_WIDTH, GRID_HEIGHT)
    private val picture = PixelImage(GRID_WIDTH, GRID_HEIGHT)
    private var before = FloatArray(0)
    private var trickle = 0f
    private var shovePhase = 0f
    private var stirTravel = 0f
    private var stirX = 0.08f
    private var stirY = 0.5f
    private var drift = 0f
    private var swirlWay = 1f
    private val discX = FloatArray(DISCS) { 0.5f }
    private val discY = FloatArray(DISCS) { 0.5f }
    private val bubbles = Sprites(160, 1_001L)
    private var bubbleCredit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val bands = state.frame.bandsRel
        if (before.size != bands.size) before = FloatArray(bands.size)
        drift += dt * (0.3f + 0.4f * state.frame.motionRate)
        shovePhase += dt * TAU / (gestures.barSeconds * 3f)
        // The kick shoves from a point sliding back and forth along the bottom.
        if (gestures.kickHit > 0f) {
            val x = 0.5f + 0.4f * sin(shovePhase)
            val colour = colourFor(x, state)
            val strength = 0.6f + 0.8f * gestures.kickHit
            grid.splat(x, 0.95f, 0.2f, random.signed() * 30f, -340f * strength, colour.red * strength * 1.4f, colour.green * strength * 1.4f, colour.blue * strength * 1.4f)
        }
        // A soft onset under calm music drops a little dye as well, so the tank never runs dry.
        val soft = state.frame.kick
        if (gestures.kickHit <= 0f && soft > 0f) {
            val x = 0.5f + 0.4f * sin(shovePhase)
            val colour = colourFor(x, state)
            grid.splat(x, 0.9f, 0.1f, random.signed() * 20f, -120f * (0.3f + soft), colour.red * soft * 2f, colour.green * soft * 2f, colour.blue * soft * 2f)
        }
        // A slow fountain at the bottom that never stops, so there is always dye to stir.
        val spring = 0.5f + 0.35f * sin(shovePhase * 0.7f)
        val tint = colourFor(spring, state)
        val flowing = dt * (6f + 4f * state.drive)
        grid.splat(spring, 0.96f, 0.1f, 0f, -90f, tint.red * flowing, tint.green * flowing, tint.blue * flowing)
        // And one pouring in from the top on the other side, in the other colour.
        val other = colourFor(1f - spring, state)
        grid.splat(1f - spring, 0.04f, 0.08f, 0f, 70f, other.red * flowing, other.green * flowing, other.blue * flowing)
        // Hats and snares drip dye in from the top, over the band that just rose.
        val hit = maxOf(gestures.hatHit * 0.7f, gestures.snareHit)
        if (hit > 0f) {
            val x = if (bands.isEmpty()) random.next() else (risingBand(bands, before) + 0.5f) / bands.size
            val colour = colourFor(x, state)
            grid.splat(x, 0.05f, 0.08f + 0.06f * hit, random.signed() * 40f, 160f * (0.5f + hit), colour.red * (0.6f + hit), colour.green * (0.6f + hit), colour.blue * (0.6f + hit))
        }
        bands.copyInto(before)
        // A steady trickle off the loudest bands, counted per second.
        trickle += dt * (6f + 14f * state.drive)
        val loud = state.percentile(0.7f)
        while (trickle >= 1f && bands.isNotEmpty()) {
            trickle -= 1f
            val band = (random.next() * bands.size).toInt().coerceIn(0, bands.lastIndex)
            val energy = bands[band]
            if (energy < loud) continue
            val along = (band + 0.5f) / bands.size
            val colour = colourFor(along, state)
            grid.splat(along, 0.25f + 0.65f * random.next(), 0.06f, random.signed() * 15f, -60f * (0.4f + energy), colour.red * 0.35f * energy, colour.green * 0.35f * energy, colour.blue * 0.35f * energy)
        }
        stir(state)
        // A drop shoves the water in from every edge at once.
        if (gestures.drop) {
            for (edge in 0 until 4) {
                val colour = colourFor(edge * 0.25f, state)
                val x = if (edge == 0) 0.03f else if (edge == 1) 0.97f else 0.5f
                val y = if (edge == 2) 0.03f else if (edge == 3) 0.97f else 0.5f
                val pushX = if (edge == 0) 300f else if (edge == 1) -300f else 0f
                val pushY = if (edge == 2) 300f else if (edge == 3) -300f else 0f
                grid.splat(x, y, 0.25f, pushX, pushY, colour.red * 1.5f, colour.green * 1.5f, colour.blue * 1.5f)
            }
        }
        // The whole tank turns slowly round its middle, the other way each phrase, so the dye never settles.
        if (gestures.phrase) swirlWay = -swirlWay
        swirl(swirlWay * dt * (30f + 60f * state.drive))
        grid.step(dt, curl = (8f + 34f * state.mood) * curl.value, dyeKept = 0.9f - 0.3f * state.mood, speedKept = 0.5f)
        // The discs drift, and the water stops dead inside them, so the dye has to flow round.
        val count = obstacles.count(1)
        for (disc in 0 until DISCS) {
            discX[disc] = 0.5f + 0.32f * sin(drift * (0.7f + 0.2f * disc) + disc * 2.1f)
            discY[disc] = 0.5f + 0.26f * sin(drift * (0.9f - 0.15f * disc) + disc * 1.3f)
            if (disc < count) block(discX[disc], discY[disc])
        }
        kit.place(2, discX[0], discY[0])
        bubbleCredit += dt * (4f + 10f * state.drive)
        while (bubbleCredit >= 1f) {
            bubbleCredit -= 1f
            bubbles.burst(stirX, stirY, 1, 0.12f, 1.5f, 0.01f, random.next(), Sprite.RING, UP, 0.8f)
        }
        bubbles.advance(dt, drag = 0.4f, gravity = -0.15f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    // The stirrer crosses the tank every two bars and drags the water along its path.
    private fun stir(state: VizRenderState) {
        val dt = state.deltaSeconds
        stirTravel += dt / (gestures.barSeconds * 2f)
        if (gestures.phrase) stirPath.choose((stirPath.value + 1) % 3)
        val leg = stirTravel % 2f
        val along = if (leg < 1f) leg else 2f - leg
        val lastX = stirX
        val lastY = stirY
        stirX = 0.08f + 0.84f * along
        var y = 0f
        for (option in 0 until 3) {
            val share = stirPath.weight(option)
            if (share <= 0f) continue
            y += share * when (option) {
                0 -> 0.5f
                1 -> 0.5f + 0.25f * sin(along * TAU * 1.5f)
                else -> 0.5f + 0.3f * cos(stirTravel * TAU)
            }
        }
        stirY = y
        kit.place(1, stirX, stirY)
        val step = dt.coerceAtLeast(1e-3f)
        val pushX = ((stirX - lastX) / step * GRID_WIDTH * 0.6f).coerceIn(-300f, 300f)
        val pushY = ((stirY - lastY) / step * GRID_HEIGHT * 0.6f).coerceIn(-300f, 300f)
        val colour = colourFor(stirX, state)
        val wake = 16f * dt * (0.6f + state.drive)
        grid.splat(stirX, stirY, 0.05f, pushX, pushY, colour.red * wake, colour.green * wake, colour.blue * wake)
    }

    /** The low half of the spectrum pours one colour and the high half another, or every place its own. */
    private fun colourFor(along: Float, state: VizRenderState): Color =
        if (colourRule.on) state.palette.cycled((if (along < 0.5f) 0.08f else 0.58f) + genes.walk) else state.palette.cycled(along + genes.walk)

    /** Adds a turn round the middle of the tank, [amount] at its edge. */
    private fun swirl(amount: Float) {
        for (cellY in 0 until GRID_HEIGHT) {
            val dy = (cellY + 0.5f) / GRID_HEIGHT - 0.5f
            for (cellX in 0 until GRID_WIDTH) {
                val dx = (cellX + 0.5f) / GRID_WIDTH - 0.5f
                val at = grid.index(cellX, cellY)
                grid.velocityX[at] -= dy * amount * 2f
                grid.velocityY[at] += dx * amount * 2f
            }
        }
    }

    /** Stops the water inside the disc at [x], [y] and thins the dye there. */
    private fun block(x: Float, y: Float) {
        val middleX = x * GRID_WIDTH
        val middleY = y * GRID_HEIGHT
        val reach = DISC_RADIUS * GRID_HEIGHT
        for (cellY in (middleY - reach).toInt().coerceAtLeast(0)..(middleY + reach).toInt().coerceAtMost(GRID_HEIGHT - 1)) {
            for (cellX in (middleX - reach).toInt().coerceAtLeast(0)..(middleX + reach).toInt().coerceAtMost(GRID_WIDTH - 1)) {
                val dx = cellX + 0.5f - middleX
                val dy = cellY + 0.5f - middleY
                if (dx * dx + dy * dy > reach * reach) continue
                val at = grid.index(cellX, cellY)
                grid.velocityX[at] = 0f
                grid.velocityY[at] = 0f
                for (channel in grid.dye) channel[at] *= 0.8f
            }
        }
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val ground = state.palette.background
        val red = grid.dye[0]
        val green = grid.dye[1]
        val blue = grid.dye[2]
        // The dye glows brighter when the music pushes, so a lull settles into darker water.
        val light = 0.45f + 0.55f * state.drive
        var pixel = 0
        for (y in 0 until GRID_HEIGHT) {
            for (x in 0 until GRID_WIDTH) {
                val at = grid.index(x, y)
                picture.pixels[pixel++] = opaque(
                    ground.red + (1f - ground.red) * soft(red[at] * 1.8f) * light,
                    ground.green + (1f - ground.green) * soft(green[at] * 1.8f) * light,
                    ground.blue + (1f - ground.blue) * soft(blue[at] * 1.8f) * light,
                )
            }
        }
        picture.upload()
        stretch(picture)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val edge = (size.minDimension * 0.005f).coerceAtLeast(1f)
        for (disc in 0 until obstacles.drawn(1)) {
            val presence = obstacles.presence(disc, 1)
            if (presence <= 0.01f) continue
            val at = Offset(discX[disc] * size.width, discY[disc] * size.height)
            val radius = DISC_RADIUS * size.height
            drawCircle(state.palette.low.copy(alpha = 0.85f * presence), radius, at)
            drawCircle(state.palette.cap.copy(alpha = 0.6f * presence), radius, at, style = Stroke(edge))
        }
        with(bubbles) { drawSprites(state.palette, genes.walk, alpha = 0.5f + 0.4f * state.lift, saturation = 0.3f) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
    }

    override fun onReset() {
        grid.clear()
        before.fill(0f)
        trickle = 0f
        shovePhase = 0f
        stirTravel = 0f
        stirX = 0.08f
        stirY = 0.5f
        drift = 0f
        swirlWay = 1f
        bubbles.clear()
        bubbleCredit = 0f
        comets.clear()
    }

    private companion object {
        const val DISCS = 3
        const val DISC_RADIUS = 0.09f
    }
}

/**
 * Two chemicals that feed on each other and grow spots, stripes and mazes by themselves, over fog that
 * shows through wherever the pattern is empty. The recipe changes every phrase, from mazes to spots to
 * worms to coral. A seeder crosses the screen every two bars, dropping a seed every beat, the whole
 * pattern slides sideways eight cells a beat, and a light circling the dish shades it from one side.
 * Drums drop seeds where their band sits, and a drop seeds everywhere at once.
 *
 * This is the Gray-Scott model: one chemical is fed in everywhere, the other eats it and spreads, and
 * how fast the first is fed decides which pattern settles.
 */
internal class ReactionDiffusion : Layered(
    name = "Reaction Diffusion",
    family = VizFamily.Fluid,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 1_002L, groundKind = GroundKind.Fog, groundDim = 0.8f, camera = stillTank(1_002L)),
) {
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
        // A new species every phrase: mazes, spots, worms or coral.
        if (gestures.phrase) {
            val species = (random.next() * 4f).toInt().coerceIn(0, 3)
            feed.target = SPECIES[species * 2]
            kill.target = SPECIES[species * 2 + 1]
        }
        seederTravel += dt / (gestures.barSeconds * 2f)
        val leg = seederTravel % 2f
        seederX = 0.05f + 0.9f * (if (leg < 1f) leg else 2f - leg)
        seederY = 0.5f + 0.35f * sin(seederTravel * TAU * 0.5f)
        kit.place(1, seederX, seederY)
        val beat = (gestures.barPhase * 4f).toInt()
        if (beat != lastBeat) {
            lastBeat = beat
            seed(seederX, seederY, 2f + 2f * state.drive)
        }
        // The whole pattern slides sideways, eight cells a beat.
        slideCredit += dt * 8f / gestures.beatSeconds
        while (slideCredit >= 1f) {
            slideCredit -= 1f
            slide(if (scrollLeft.on) -1 else 1)
        }
        if (gestures.kickHit > 0f) {
            val x = 0.1f + 0.8f * random.next()
            val y = 0.1f + 0.8f * random.next()
            seed(x, y, 2.5f + 3f * gestures.kickHit)
            sparks.burst(x, y, 8, 0.3f, 0.6f, 0.01f, random.next(), Sprite.SPARK)
        }
        val bands = state.frame.bandsRel
        val hit = maxOf(gestures.snareHit, gestures.hatHit)
        if (hit > 0f && bands.isNotEmpty()) {
            var loudest = 0
            for (band in bands.indices) if (bands[band] > bands[loudest]) loudest = band
            seed((loudest + 0.5f) / bands.size, random.next(), 1.5f + 2f * hit)
        }
        if (gestures.drop) {
            for (index in 0 until 20) seed(random.next(), random.next(), 3f)
        }
        owed += dt * (300f + 900f * state.drive)
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
        lightAngle += dt * (0.5f + 0.5f * state.frame.motionRate)
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
        val light = 0.55f + 0.45f * state.lift
        // The lamp circles the dish or swings across it, and each cell is lit by how it slopes towards it.
        val angle = if (lightPath.value == 0) lightAngle else sin(lightAngle) * 1.5f
        val stepX = towards(cos(angle))
        val stepY = towards(sin(angle))
        var pixel = 0
        for (y in 0 until GRID_HEIGHT) {
            val row = y * GRID_WIDTH
            val other = ((y + stepY + GRID_HEIGHT) % GRID_HEIGHT) * GRID_WIDTH
            for (x in 0 until GRID_WIDTH) {
                val amount = eater[row + x]
                // A steep ramp gives the pattern a clean edge instead of a blur.
                val ramp = ((amount - 0.08f) / 0.22f).coerceIn(0f, 1f)
                val level = ramp * ramp * (3f - 2f * ramp)
                val shade = (0.8f + (amount - eater[other + (x + stepX + GRID_WIDTH) % GRID_WIDTH]) * 6f).coerceIn(0.35f, 1.4f) * light
                val colour = state.palette.ramp(level)
                // Empty cells let the fog through.
                picture.pixels[pixel++] = see(colour.red * shade, colour.green * shade, colour.blue * shade, 0.12f + 0.88f * level)
            }
        }
        picture.upload()
        stretch(picture)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        drawCircle(state.palette.cap.copy(alpha = 0.5f + 0.4f * state.lift), size.minDimension * 0.012f, Offset(seederX * size.width, seederY * size.height))
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
 * changes side every phrase and curling under a ceiling set by how loud the song has been. A lamp
 * circling the tank lights the smoke from one side, embers rise through it, a kick lets out a puff,
 * and a drop lifts the ceiling out of the way.
 */
internal class SmokeRise : Layered(
    name = "Smoke Rise",
    family = VizFamily.Fluid,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 1_003L, camera = stillTank(1_003L)),
) {
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false

    private val emitters = genes.toggle("Second emitter", start = true)
    private val windGene = genes.number("Wind", -1f, 1f, 0.3f)
    private val lightPath = genes.choice("Light path", 2)
    private val ceilingGene = genes.number("Ceiling", 0.1f, 0.45f, 0.25f)

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
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        travel += dt * TAU / (gestures.barSeconds * 4f)
        for (index in 0 until 2) emitterX[index] = 0.5f + 0.4f * sin(travel + index * PI.toFloat())
        kit.place(1, emitterX[0], 0.92f)
        wind.advance(windGene.value * if (gestures.phrases % 2 == 0) 1f else -1f, dt)
        if (gestures.drop) liftHold = gestures.barSeconds * 2f
        liftHold -= dt
        lifted.advance(if (liftHold > 0f) 1f else 0f, dt)
        // The ceiling hangs lower after a quiet stretch, and a drop lifts it out of the way.
        ceiling.advance(ceilingGene.value + 0.3f * (1f - frame.loudLong), dt)
        val count = if (emitters.on) 2 else 1
        puffs += dt * (12f + 36f * frame.bassRel * (0.3f + 0.7f * state.drive))
        while (puffs >= 1f) {
            puffs -= 1f
            val index = (random.next() * count).toInt().coerceIn(0, 1)
            grid.splat(emitterX[index] + random.signed() * 0.04f, 0.92f, 0.07f, random.signed() * 12f, -30f, 0.5f + frame.bassRel)
        }
        if (gestures.kickHit > 0f) {
            for (index in 0 until count) grid.splat(emitterX[index], 0.95f, 0.1f, random.signed() * 20f, -120f * (0.5f + gestures.kickHit), 1.2f * gestures.kickHit)
        }
        // In a silence a thin thread still rises, so the picture never freezes.
        grid.splat(emitterX[0], 0.97f, 0.03f, 0f, -10f, 0.4f * dt)
        grid.rise(strength = 80f + 90f * state.mood, deltaSeconds = dt)
        blow(wind.value * (8f + 10f * state.drive) * dt)
        cap((ceiling.value * (1f - lifted.value)).coerceIn(0f, 0.6f))
        grid.step(dt, curl = 8f + 28f * state.mood, dyeKept = 0.88f, speedKept = 0.8f)
        lightAngle += dt * (0.4f + 0.5f * frame.motionRate)
        shimmer += dt * (1f + 2.5f * state.drive)
        emberCredit += dt * (30f + 60f * state.drive)
        while (emberCredit >= 1f) {
            emberCredit -= 1f
            val index = (random.next() * count).toInt().coerceIn(0, 1)
            embers.burst(emitterX[index], 0.95f, 1, 0.3f, 2.5f, 0.02f, 0.05f + 0.1f * random.next(), Sprite.SPARK, UP + wind.value * 0.3f, 0.5f)
        }
        embers.advance(dt, drag = 0.3f, gravity = -0.1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
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
        val angle = if (lightPath.value == 0) lightAngle else sin(lightAngle) * 1.5f
        val stepX = towards(cos(angle))
        val stepY = towards(sin(angle))
        var pixel = 0
        for (y in 0 until GRID_HEIGHT) {
            val colour = state.palette.ramp(1f - y / (GRID_HEIGHT - 1f))
            val otherY = (y + stepY).coerceIn(0, GRID_HEIGHT - 1)
            for (x in 0 until GRID_WIDTH) {
                val here = smoke[grid.index(x, y)]
                val toward = smoke[grid.index((x + stepX).coerceIn(0, GRID_WIDTH - 1), otherY)]
                // A faint haze everywhere, drifting in slow waves as if the room were lit, and the smoke thick over it.
                val haze = 0.12f + 0.07f * sin(shimmer + x * 0.15f - y * 0.11f)
                val thick = haze + soft(here * 4f) * light * (0.75f + (here - toward) * 1.5f).coerceIn(0.4f, 1.4f)
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

    override fun DrawScope.drawTop(state: VizRenderState) {
        val height = ceiling.value * (1f - lifted.value) * size.height
        if (height > 1f) {
            drawLine(state.palette.mid.copy(alpha = 0.25f), Offset(0f, height), Offset(size.width, height), (size.minDimension * 0.003f).coerceAtLeast(1f))
        }
        with(embers) { drawSprites(state.palette, genes.walk, alpha = 0.5f + 0.4f * state.lift) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
    }

    override fun onReset() {
        grid.clear()
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
        comets.clear()
    }
}
