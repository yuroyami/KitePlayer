package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import io.github.yuroyami.kiteplayer.audioviz.viz.vividColour
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Marbled ink on a black tray of water.
 *
 * The drums drop ink: a kick drops orange where the loudest low band points, as wide as the kick is
 * hard, and a snare drops blue. Each drop pushes the older ink outward into rings, the way paint
 * does on a marbling tray. Invisible vortices comb the rings into swirls. They sit along the
 * spectrum's shape, low bands left and high bands right, turn as hard as their bands are loud and
 * faster with the bass, two of them in calm music and five in lively music. Inside the ink a
 * reaction grows lighter lace, spots or maze lines, faster when the music is loud, and a hat
 * sprouts tiny new spots. A section changes the lace's recipe, and a breakdown stops the vortices
 * while the lace grows into calm coral. On a drop one huge gold drop lands in the middle and pushes
 * everything into a bullseye, which the vortices comb into feathers over the next bar. The grids
 * are small, but the shader cuts every ink edge one pixel wide, so the edges stay sharp at any size.
 */
internal class Marble : ShaderPreset(
    source = SOURCE,
    name = "Marble",
    bucket = VizEnergy.Mid,
    seed = 175f,
    kit = Kit(1_751L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 1_751)),
) {

    override val mapping: VizMapping by mappingOf(
        // A kick drops orange ink as wide as it is hard, a snare drops blue, and each pushes the old ink out.
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(8f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(8f)),
        // A song without drums drops ink on its onsets.
        VizDrive(VizDriver.Onset, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(8f)),
        // The vortices follow the spectrum's shape, and the bass stirs them faster.
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Bass, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
        // Loud music lights the tray and grows the lace fast; a hat sprouts new spots in it, which glint.
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.lifetime(0.5f)),
        VizDrive(VizDriver.Section, VizProperty.Texture, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Speed, VizCurve.Discrete, VizResponse.envelope(1f, delaySeconds = 0.5f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(4f)),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        silence = VizSilence.Still,
    )

    // The edges are cut sharp in the shader; a general glow would only soften them again.
    override val post: PostSpec get() = PostSpec.Off

    override val hasFallback: Boolean get() = true

    // The tray, in cells: wide in landscape, tall in portrait.
    private var columns = LONG
    private var rows = SHORT
    private var portrait = false
    private var cells = columns * rows

    // What lies in each cell: three inks, orange, blue and gold, and the reaction's two chemicals.
    // The second set is where a move or a drop is written before the two sets swap.
    private var ink = Array(INKS) { FloatArray(cells) }
    private var feedChem = FloatArray(cells) { 1f }
    private var laceChem = FloatArray(cells)
    private var spareInk = Array(INKS) { FloatArray(cells) }
    private var spareFeed = FloatArray(cells)
    private var spareLace = FloatArray(cells)
    private var nextFeed = FloatArray(cells)
    private var nextLace = FloatArray(cells)
    private var wet = FloatArray(cells)
    /** A brief white glint where a hat has just sprouted a spot. */
    private var glint = FloatArray(cells)
    private var inkImage = PixelImage(columns, rows)
    private var laceImage = PixelImage(columns, rows)
    private var fallbackImage: PixelImage? = null
    private var prepared = false

    // The vortices, in cells, and their signed peak speed in cells a second.
    private var vortexCount = 3
    private val vortexX = FloatArray(MOST_VORTICES)
    private val vortexY = FloatArray(MOST_VORTICES)
    private val vortexSpeed = FloatArray(MOST_VORTICES)
    private val vortexLevel = FloatArray(MOST_VORTICES)

    // The reaction: its recipe now and where it is heading, and steps it is owed.
    private var feed = RECIPES[0]
    private var kill = RECIPES[1]
    private var recipe = 0
    private var feedTarget = feed
    private var killTarget = kill
    private var owed = 0f
    private var warmUp = WARM_UP_STEPS

    private var breakdown = false
    private var calm = 0f
    private var combing = 0f
    private var pipetteX = 0.5f
    private var pipetteTime = 0f
    private var sinceDrum = LONG_AGO
    private var sinceFallback = LONG_AGO
    private var light = IDLE_LIGHT
    private val keyTurn = Slew(maxPerSecond = 20f)
    private val colours = Array(INKS) { FloatArray(3) }
    private var colourFor: VizPalette? = null
    private var colourTurn = Float.NaN

    /** Drops of ink since the start, and where the last one landed in shares of the tray, for tests. */
    internal var drops = 0
        private set
    internal var lastDropX = 0f
        private set
    internal var lastDropY = 0f
        private set

    /** The share of the tray's inked cells whose lace has grown, for tests. */
    internal fun laceShare(): Float {
        var inked = 0
        var laced = 0
        for (cell in 0 until cells) {
            if (max(ink[0][cell], max(ink[1][cell], ink[2][cell])) <= 0.5f) continue
            inked++
            if (laceChem[cell] > 0.25f) laced++
        }
        return if (inked == 0) 0f else laced.toFloat() / inked
    }

    /** Reaction steps run since the start, for tests. */
    internal var grown = 0L
        private set

    /** The share of the tray under ink, for tests. */
    internal fun inkedShare(): Float {
        var inked = 0
        for (cell in 0 until cells) if (max(ink[0][cell], max(ink[1][cell], ink[2][cell])) > 0.5f) inked++
        return inked.toFloat() / cells
    }

    /** The strongest ink at a point in shares of the tray, for tests. */
    internal fun inkAt(x: Float, y: Float, which: Int): Float = sample(ink[which], x * columns, y * rows)

    /** How fast the vortices stir now: the largest peak speed, in cells a second. For tests. */
    internal val stirring: Float get() = (0 until vortexCount).maxOf { kotlin.math.abs(vortexSpeed[it]) }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        val step = state.stepSeconds
        val wantPortrait = kit.aspect < 1f
        if (!prepared || wantPortrait != portrait) prepare(wantPortrait)

        if (gestures.breakdown) breakdown = true else if (gestures.turn || gestures.surge) breakdown = false
        calm += ((if (breakdown) 1f else 0f) - calm) * (1f - exp(-step / 0.5f))
        val loud = ((state.lift / state.lightScale.coerceAtLeast(1e-3f) - 0.06f) / 0.94f).coerceIn(0f, 1f)
        light = state.lightScale.coerceIn(0f, 1f) * (IDLE_LIGHT + (1f - IDLE_LIGHT) * loud)
        updateColours(state, step)

        // A section changes the lace's recipe: the old lace fades and new seeds sprout at once.
        if (gestures.turn) {
            recipe = (recipe + 1 + (kit.random.next() * 2f).toInt()) % 3
            feedTarget = RECIPES[recipe * 2]
            killTarget = RECIPES[recipe * 2 + 1]
            for (cell in 0 until cells) laceChem[cell] *= 0.25f
            sprout(40, 1.6f)
        }
        // A breakdown grows calm, even coral.
        if (breakdown) {
            feedTarget = CORAL_FEED
            killTarget = CORAL_KILL
        }
        val ease = 1f - exp(-step / 1.5f)
        feed += (feedTarget - feed) * ease
        kill += (killTarget - kill) * ease

        // The drums drop ink through one pipette, which drifts to where the loudest low band points.
        // Drops at nearly one place push each other out into rings, orange and blue in turn: the
        // bullseyes the vortices comb into marbling.
        pipetteX += (loudestIn(frame.bands, 0f, 0.34f) - pipetteX) * (1f - exp(-step / 1.5f))
        pipetteTime += step
        val atX = columns * (0.18f + 0.64f * pipetteX)
        val atY = rows * (0.5f + 0.22f * sin(pipetteTime * 0.21f))
        sinceDrum += step
        sinceFallback += step
        if (gestures.kick > 0f) {
            drop(atX + kit.random.signed() * 2f, atY + kit.random.signed() * 2f,
                rows * (0.05f + 0.1f * gestures.kick.coerceIn(0f, 1f)), ORANGE)
            sinceDrum = 0f
        }
        if (gestures.snare > 0f) {
            drop(atX + kit.random.signed() * 2f, atY + kit.random.signed() * 2f,
                rows * (0.04f + 0.07f * gestures.snare.coerceIn(0f, 1f)), BLUE)
            sinceDrum = 0f
        }
        // A song without drums drops ink on its onsets instead, orange and blue in turn, at most once a beat.
        if (sinceDrum > gestures.cycleSeconds && sinceFallback > gestures.beatSeconds * 0.9f) {
            val onset = onsetIn(frame)
            if (onset > 0f) {
                drop(atX, atY, rows * (0.04f + 0.07f * onset), if (drops % 2 == 0) ORANGE else BLUE)
                sinceFallback = 0f
            }
        }
        if (gestures.surge) {
            drop(columns * 0.5f, rows * 0.5f, rows * GOLD_RADIUS, GOLD)
            combing = 1f
        }
        // A hat sprouts tiny new spots in the lace, each glinting for a moment as it appears.
        val fade = exp(-step / 0.35f)
        for (cell in 0 until cells) glint[cell] *= fade
        if (gestures.hat > 0f) sprout((6 + 8 * gestures.hat).roundToInt(), 1.2f, shine = true)

        // The vortices comb the ink, and the reaction grows lace inside it.
        placeVortices(state)
        combing = (combing - step / gestures.cycleSeconds.coerceAtLeast(0.5f)).coerceAtLeast(0f)
        if (step > 0f && stirring > 0.01f) advect(step)
        owed += step * (120f + 600f * frame.energy) * (1f - 0.75f * calm)
        if (warmUp > 0) {
            val extra = min(warmUp, WARM_UP_PER_FRAME) * frame.audible
            owed += extra
            warmUp -= extra.toInt()
        }
        var steps = 0
        while (owed >= 1f && steps < MOST_STEPS) {
            owed -= 1f
            react()
            steps++
        }
        owed = owed.coerceAtMost(1f)
        grown += steps
    }

    /** Builds the tray for this orientation and fills it with a first marbled pattern. */
    private fun prepare(tall: Boolean) {
        portrait = tall
        columns = if (tall) SHORT else LONG
        rows = if (tall) LONG else SHORT
        cells = columns * rows
        ink = Array(INKS) { FloatArray(cells) }
        spareInk = Array(INKS) { FloatArray(cells) }
        feedChem = FloatArray(cells) { 1f }
        laceChem = FloatArray(cells)
        spareFeed = FloatArray(cells)
        spareLace = FloatArray(cells)
        nextFeed = FloatArray(cells)
        nextLace = FloatArray(cells)
        wet = FloatArray(cells)
        glint = FloatArray(cells)
        inkImage = PixelImage(columns, rows)
        laceImage = PixelImage(columns, rows)
        fallbackImage = null
        prepared = true
        // Drops at three places, orange and blue in turn, pushed into rings, then one comb across
        // them: marbled paper from the first frame.
        val places = floatArrayOf(0.26f, 0.4f, 0.74f, 0.6f, 0.5f, 0.5f)
        for (round in 0 until 6) {
            for (place in 0 until 3) {
                drop(columns * places[place * 2], rows * places[place * 2 + 1], rows * (0.08f + 0.015f * round),
                    if ((round + place) % 2 == 0) ORANGE else BLUE, counted = false)
            }
        }
        comb(rows * 0.07f, rows * 0.45f)
        sprout(60, 1.6f)
        warmUp = WARM_UP_STEPS
    }

    /** The strongest onset delivered this frame, 0 when there is none. */
    private fun onsetIn(frame: SpectrumFrame): Float {
        var strongest = 0f
        val delivery = frame.events
        val detections = frame.detections
        when {
            delivery != null -> for (index in 0 until delivery.size) {
                val detection = delivery[index].event.detection
                if (detection.kind == AudioEventKind.Onset && detection.isHit) strongest = max(strongest, detection.strength.coerceIn(0.05f, 1f))
            }
            detections != null -> for (index in 0 until detections.size) {
                val detection = detections[index]
                if (detection.kind == AudioEventKind.Onset && detection.isHit) strongest = max(strongest, detection.strength.coerceIn(0.05f, 1f))
            }
        }
        return strongest
    }

    /** The share across [from] to [to] of the spectrum, 0 to 1, where its loudest band sits. */
    private fun loudestIn(bands: FloatArray, from: Float, to: Float): Float {
        if (bands.isEmpty()) return kit.random.next()
        val first = (from * bands.size).toInt().coerceIn(0, bands.size - 1)
        val last = (to * bands.size).toInt().coerceIn(first + 1, bands.size)
        var loudest = first
        for (band in first until last) if (bands[band] > bands[loudest]) loudest = band
        return if (last - 1 > first) (loudest - first).toFloat() / (last - 1 - first) else 0.5f
    }

    /**
     * A drop of [colour] ink [radius] cells across at [x], [y]. Everything outside it is pushed
     * outward, as paint on a marbling tray is: a point at distance d moves to the square root of d
     * squared plus the radius squared, so the old ink closes into rings round the new drop.
     */
    private fun drop(x: Float, y: Float, radius: Float, colour: Int, counted: Boolean = true) {
        val squared = radius * radius
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = row * columns + column
                val dx = column + 0.5f - x
                val dy = row + 0.5f - y
                val distance = sqrt(dx * dx + dy * dy)
                // New ink fills the drop, blended across its edge cell so the circle stays round.
                val inside = (radius - distance + 0.5f).coerceIn(0f, 1f)
                if (distance > 1e-4f && distance * distance > squared) {
                    val shrink = sqrt(1f - squared / (distance * distance))
                    sampleAll(x + dx * shrink, y + dy * shrink, cell)
                } else {
                    for (which in 0 until INKS) spareInk[which][cell] = 0f
                    spareFeed[cell] = 1f
                    spareLace[cell] = 0f
                }
                if (inside > 0f) {
                    for (which in 0 until INKS) {
                        val fresh = if (which == colour) 1f else 0f
                        spareInk[which][cell] += (fresh - spareInk[which][cell]) * inside
                    }
                    spareFeed[cell] += (1f - spareFeed[cell]) * inside
                    spareLace[cell] *= 1f - inside
                }
            }
        }
        swap()
        if (counted) {
            drops++
            lastDropX = x / columns
            lastDropY = y / rows
        }
    }

    /** One comb across the tray: every row shifts sideways by a wave [reach] cells high and [wave] cells long. */
    private fun comb(reach: Float, wave: Float) {
        for (row in 0 until rows) {
            val shift = reach * sin((row + 0.5f) / wave * TAU_F)
            for (column in 0 until columns) sampleAll(column + 0.5f - shift, row + 0.5f, row * columns + column)
        }
        swap()
    }

    /** Places the vortices along the spectrum's shape and sets how hard each stirs. */
    private fun placeVortices(state: VizRenderState) {
        val frame = state.frame
        val step = state.stepSeconds
        val bands = frame.bands
        vortexCount = (2f + 3f * frame.mood).roundToInt().coerceIn(2, MOST_VORTICES)
        val ease = 1f - exp(-step / 0.6f)
        val stir = (BASE_STIR + BASS_STIR * frame.bass) * (1f - calm) * (0.3f + 0.7f * state.motionScale) *
            (1f + 1.2f * combing)
        for (vortex in 0 until vortexCount) {
            var level = 0f
            if (bands.isNotEmpty()) {
                val first = vortex * bands.size / vortexCount
                val last = ((vortex + 1) * bands.size / vortexCount).coerceAtLeast(first + 1)
                for (band in first until last) level += bands[band]
                level /= (last - first)
            }
            vortexLevel[vortex] += (level - vortexLevel[vortex]) * ease
            // Low bands on the left, high on the right, each as high up the tray as its band is loud.
            // While a drop is being combed they line up across the middle, which makes feathers.
            val wantX = columns * (vortex + 0.5f) / vortexCount
            val wantY = rows * (0.72f - 0.44f * vortexLevel[vortex].coerceIn(0f, 1f))
            val targetY = wantY + (rows * 0.5f - wantY) * combing
            vortexX[vortex] += (wantX - vortexX[vortex]) * ease
            vortexY[vortex] += (targetY - vortexY[vortex]) * ease
            val sign = if (vortex % 2 == 0) 1f else -1f
            vortexSpeed[vortex] = sign * stir * (0.35f + 0.65f * vortexLevel[vortex].coerceIn(0f, 1f) * 2f).coerceAtMost(1.3f)
        }
        for (vortex in vortexCount until MOST_VORTICES) vortexSpeed[vortex] = 0f
    }

    /** The vortices' flow at a point, in cells a second, into [flowX] and [flowY]. */
    private var flowX = 0f
    private var flowY = 0f

    private fun flowAt(x: Float, y: Float) {
        var fx = 0f
        var fy = 0f
        val core = rows * CORE
        val coreSquared = core * core
        for (vortex in 0 until vortexCount) {
            val dx = x - vortexX[vortex]
            val dy = y - vortexY[vortex]
            // Fastest at the core's edge, slowing with distance beyond it, still at the centre.
            val scale = vortexSpeed[vortex] * 2f * core / (coreSquared + dx * dx + dy * dy)
            fx -= dy * scale
            fy += dx * scale
        }
        flowX = fx
        flowY = fy
    }

    /** Carries everything along the flow for [seconds], tracing each cell back through the middle of its step. */
    private fun advect(seconds: Float) {
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = column + 0.5f
                val y = row + 0.5f
                flowAt(x, y)
                val midX = x - flowX * seconds * 0.5f
                val midY = y - flowY * seconds * 0.5f
                flowAt(midX, midY)
                val backX = (flowX * seconds).coerceIn(-MOST_MOVE, MOST_MOVE)
                val backY = (flowY * seconds).coerceIn(-MOST_MOVE, MOST_MOVE)
                sampleAll(x - backX, y - backY, row * columns + column)
            }
        }
        swap()
    }

    /** Reads every field at a point in cells, smoothly between cells, into the spare set at [cell]. */
    private fun sampleAll(x: Float, y: Float, cell: Int) {
        val px = (x - 0.5f).coerceIn(0f, columns - 1f)
        val py = (y - 0.5f).coerceIn(0f, rows - 1f)
        val left = px.toInt().coerceAtMost(columns - 2)
        val top = py.toInt().coerceAtMost(rows - 2)
        val tx = px - left
        val ty = py - top
        val a = top * columns + left
        val b = a + 1
        val c = a + columns
        val d = c + 1
        val wa = (1f - tx) * (1f - ty)
        val wb = tx * (1f - ty)
        val wc = (1f - tx) * ty
        val wd = tx * ty
        for (which in 0 until INKS) {
            val field = ink[which]
            spareInk[which][cell] = field[a] * wa + field[b] * wb + field[c] * wc + field[d] * wd
        }
        spareFeed[cell] = feedChem[a] * wa + feedChem[b] * wb + feedChem[c] * wc + feedChem[d] * wd
        spareLace[cell] = laceChem[a] * wa + laceChem[b] * wb + laceChem[c] * wc + laceChem[d] * wd
    }

    private fun swap() {
        val oldInk = ink
        ink = spareInk
        spareInk = oldInk
        val oldFeed = feedChem
        feedChem = spareFeed
        spareFeed = oldFeed
        val oldLace = laceChem
        laceChem = spareLace
        spareLace = oldLace
    }

    /** A field read smoothly at a point in cells. */
    private fun sample(field: FloatArray, x: Float, y: Float): Float {
        val px = (x - 0.5f).coerceIn(0f, columns - 1f)
        val py = (y - 0.5f).coerceIn(0f, rows - 1f)
        val left = px.toInt().coerceAtMost(columns - 2)
        val top = py.toInt().coerceAtMost(rows - 2)
        val tx = px - left
        val ty = py - top
        val a = top * columns + left
        return (field[a] * (1f - tx) + field[a + 1] * tx) * (1f - ty) + (field[a + columns] * (1f - tx) + field[a + columns + 1] * tx) * ty
    }

    /**
     * One step of the reaction: one chemical is fed in everywhere, the other eats it and spreads,
     * and the recipe of feed and kill decides whether spots, mazes or coral settle. It grows only
     * under ink: open water keeps no lace.
     */
    private fun react() {
        for (cell in 0 until cells) {
            val total = max(ink[0][cell], max(ink[1][cell], ink[2][cell]))
            val t = ((total - 0.3f) / 0.3f).coerceIn(0f, 1f)
            wet[cell] = t * t * (3f - 2f * t)
        }
        for (row in 0 until rows) {
            val here = row * columns
            val up = max(row - 1, 0) * columns
            val down = min(row + 1, rows - 1) * columns
            for (column in 0 until columns) {
                val left = max(column - 1, 0)
                val right = min(column + 1, columns - 1)
                val cell = here + column
                val a = feedChem[cell]
                val b = laceChem[cell]
                val spreadA = 0.2f * (feedChem[here + left] + feedChem[here + right] + feedChem[up + column] + feedChem[down + column]) +
                    0.05f * (feedChem[up + left] + feedChem[up + right] + feedChem[down + left] + feedChem[down + right]) - a
                val spreadB = 0.2f * (laceChem[here + left] + laceChem[here + right] + laceChem[up + column] + laceChem[down + column]) +
                    0.05f * (laceChem[up + left] + laceChem[up + right] + laceChem[down + left] + laceChem[down + right]) - b
                val meeting = a * b * b
                val wetHere = wet[cell]
                nextFeed[cell] = ((a + spreadA - meeting + feed * (1f - a)) * wetHere + (1f - wetHere)).coerceIn(0f, 1f)
                nextLace[cell] = ((b + 0.5f * spreadB + meeting - (kill + feed) * b) * wetHere).coerceIn(0f, 1f)
            }
        }
        val oldFeed = feedChem
        feedChem = nextFeed
        nextFeed = oldFeed
        val oldLace = laceChem
        laceChem = nextLace
        nextLace = oldLace
    }

    /** Sprouts [count] tiny seeds of lace, each [radius] cells across, at random places under ink, glinting if [shine]. */
    private fun sprout(count: Int, radius: Float, shine: Boolean = false) {
        var planted = 0
        var tries = 0
        while (planted < count && tries < count * 8) {
            tries++
            val x = kit.random.next() * columns
            val y = kit.random.next() * rows
            val cell = y.toInt().coerceIn(0, rows - 1) * columns + x.toInt().coerceIn(0, columns - 1)
            if (max(ink[0][cell], max(ink[1][cell], ink[2][cell])) < 0.6f) continue
            val reach = radius.toInt() + 1
            for (dy in -reach..reach) for (dx in -reach..reach) {
                if (dx * dx + dy * dy > radius * radius) continue
                val column = x.toInt() + dx
                val row = y.toInt() + dy
                if (column !in 0 until columns || row !in 0 until rows) continue
                laceChem[row * columns + column] = 0.9f
                feedChem[row * columns + column] = 0.3f
            }
            if (shine) {
                for (dy in -3..3) for (dx in -3..3) {
                    val column = x.toInt() + dx
                    val row = y.toInt() + dy
                    if (column !in 0 until columns || row !in 0 until rows) continue
                    val near = (1f - sqrt((dx * dx + dy * dy).toFloat()) / 3.2f).coerceAtLeast(0f)
                    glint[row * columns + column] = max(glint[row * columns + column], near)
                }
            }
            planted++
        }
    }

    /** The key turns the three inks by up to thirty degrees; a chosen palette brings its own. */
    private fun updateColours(state: VizRenderState, step: Float) {
        val frame = state.frame
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - ORANGE_HUE
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyTurn.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, step)
        val degrees = kotlin.math.round(keyTurn.value)
        val palette = state.palette
        if (palette === colourFor && degrees == colourTurn) return
        colourFor = palette
        colourTurn = degrees
        if (palette.name == VizPalette.Prism.name) {
            vividColour(ORANGE_HUE + degrees).into(colours[ORANGE])
            val blueHue = BLUE_HUE + degrees
            colourOf(BLUE_L, min(BLUE_C, mostChroma(BLUE_L, blueHue) - 0.005f).coerceAtLeast(0f), blueHue).into(colours[BLUE])
            vividColour(GOLD_HUE + degrees).into(colours[GOLD])
        } else {
            palette.vivid(0f).into(colours[ORANGE])
            palette.vivid(0.95f).into(colours[BLUE])
            palette.vivid(0.5f).into(colours[GOLD])
        }
    }

    private fun Color.into(target: FloatArray) {
        target[0] = red
        target[1] = green
        target[2] = blue
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        for (cell in 0 until cells) {
            inkImage.pixels[cell] = (0xFF shl 24) or (byte(ink[0][cell]) shl 16) or (byte(ink[1][cell]) shl 8) or byte(ink[2][cell])
            laceImage.pixels[cell] = (0xFF shl 24) or (byte(laceChem[cell]) shl 16) or (byte(glint[cell]) shl 8)
        }
        inkImage.upload()
        laceImage.upload()
        program.child("uInk", inkImage.image)
        program.child("uLace", laceImage.image)
        program.uniform("uGrid", columns.toFloat(), rows.toFloat())
        program.uniform("uOrange", colours[ORANGE][0], colours[ORANGE][1], colours[ORANGE][2])
        program.uniform("uBlue", colours[BLUE][0], colours[BLUE][1], colours[BLUE][2])
        program.uniform("uGold", colours[GOLD][0], colours[GOLD][1], colours[GOLD][2])
        program.uniform("uLight", light)
    }

    /** Where shaders cannot run: the same tray at its own resolution, drawn smoothly over the screen. */
    override fun DrawScope.drawFallback(state: VizRenderState) {
        val picture = fallbackImage?.takeIf { it.width == columns && it.height == rows }
            ?: PixelImage(columns, rows).also { fallbackImage = it }
        for (cell in 0 until cells) {
            val o = ink[0][cell]
            val b = ink[1][cell]
            val g = ink[2][cell]
            val top = max(o, max(b, g))
            val which = if (top == o) ORANGE else if (top == b) BLUE else GOLD
            val cover = ((top - 0.35f) / 0.3f).coerceIn(0f, 1f)
            val lace = ((laceChem[cell] - 0.2f) / 0.15f).coerceIn(0f, 1f) * 0.45f
            val colour = colours[which]
            val red = (colour[0] + (1f - colour[0]) * lace) * cover * light
            val green = (colour[1] + (1f - colour[1]) * lace) * cover * light
            val blue = (colour[2] + (1f - colour[2]) * lace) * cover * light
            picture.pixels[cell] = (0xFF shl 24) or (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
        }
        picture.upload()
        drawImage(
            image = picture.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(columns, rows),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Low,
        )
    }

    override fun onReset() {
        prepared = false
        vortexX.fill(0f)
        vortexY.fill(0f)
        vortexSpeed.fill(0f)
        vortexLevel.fill(0f)
        vortexCount = 3
        feed = RECIPES[0]
        kill = RECIPES[1]
        recipe = 0
        feedTarget = feed
        killTarget = kill
        owed = 0f
        breakdown = false
        calm = 0f
        combing = 0f
        pipetteX = 0.5f
        pipetteTime = 0f
        sinceDrum = LONG_AGO
        sinceFallback = LONG_AGO
        light = IDLE_LIGHT
        keyTurn.reset()
        colourFor = null
        drops = 0
        grown = 0L
    }

    internal companion object {
        /** The tray's long and short sides, in cells. */
        const val LONG = 160
        const val SHORT = 90
        const val INKS = 3
        const val ORANGE = 0
        const val BLUE = 1
        const val GOLD = 2
        const val MOST_VORTICES = 5

        /** A vortex's core radius as a share of the tray's short side, and its stirring in cells a second. */
        private const val CORE = 0.14f
        private const val BASE_STIR = 5f
        private const val BASS_STIR = 16f
        /** The furthest a cell's content moves in one frame, in cells. */
        private const val MOST_MOVE = 1.5f
        /** The gold drop's radius as a share of the short side. */
        private const val GOLD_RADIUS = 0.36f

        /** The most reaction steps in one frame, and the steps that grow the first lace. */
        private const val MOST_STEPS = 18
        private const val WARM_UP_STEPS = 360
        private const val WARM_UP_PER_FRAME = 24

        /** Feed and kill for spots, a maze and coral, and the calm coral of a breakdown. */
        private val RECIPES = floatArrayOf(0.037f, 0.06f, 0.029f, 0.057f, 0.0545f, 0.062f)
        private const val CORAL_FEED = 0.0545f
        private const val CORAL_KILL = 0.062f

        /** The share of full light the tray keeps in a silence. */
        private const val IDLE_LIGHT = 0.4f
        private const val LONG_AGO = 99f
        private const val KEY_TURN = 30f
        private const val TAU_F = 6.2831855f

        // The inks: orange for kicks, electric blue for snares, gold for a drop.
        private const val ORANGE_HUE = 50f
        private const val BLUE_L = 0.55f
        private const val BLUE_C = 0.24f
        private const val BLUE_HUE = 262f
        private const val GOLD_HUE = 85f

        private fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        const val SOURCE: String = """
uniform shader uInk;
uniform shader uLace;
uniform float2 uGrid;
uniform float3 uOrange;
uniform float3 uBlue;
uniform float3 uGold;
uniform float uLight;

float3 inkAt(float2 g) { return uInk.eval(g).rgb; }

float strongest(float3 c) { return max(c.r, max(c.g, c.b)); }

// Each ink's lead over the strongest of the other two.
float3 leads(float3 c) {
    return c - float3(max(c.g, c.b), max(c.r, c.b), max(c.r, c.g));
}

half4 main(float2 position) {
    // Where this pixel sits in cells, and how many cells one pixel spans.
    float2 cell = uGrid / uResolution;
    float2 g = position * cell;
    // Slopes are read one cell either side. A smooth read's slope jumps at every cell edge, and a
    // slope read one pixel either side would show the grid as stripes in the light; this one does not.
    float3 c = inkAt(g);
    float3 right = inkAt(g + float2(1.0, 0.0));
    float3 left = inkAt(g - float2(1.0, 0.0));
    float3 below = inkAt(g + float2(0.0, 1.0));
    float3 above = inkAt(g - float2(0.0, 1.0));

    // The ink's edge is cut at half strength, one pixel wide, so it is a sharp smooth curve at any size.
    float h = strongest(c);
    float2 slope = float2(strongest(right) - strongest(left), strongest(below) - strongest(above)) * 0.5;
    float change = max(length(slope * cell), 0.0005);
    float edge = (h - 0.5) / change;
    float cover = clamp(edge + 0.5, 0.0, 1.0);

    // Where two inks meet, the boundary is cut the same way, from each ink's lead and how fast it moves.
    float3 lead = leads(c);
    float3 leadX = (leads(right) - leads(left)) * 0.5 * cell.x;
    float3 leadY = (leads(below) - leads(above)) * 0.5 * cell.y;
    float3 leadChange = max(sqrt(leadX * leadX + leadY * leadY), float3(0.0005));
    float3 weight = clamp(lead / leadChange + 0.5, 0.0, 1.0);
    float total = max(weight.r + weight.g + weight.b, 0.0001);
    float3 ink = (uOrange * weight.r + uBlue * weight.g + uGold * weight.b) / total;

    // A thin white rim on every ink edge, against water or against another ink. Only inks that are
    // there can meet: in open water every lead is zero and would otherwise read as an edge. A thread
    // of ink too thin to show keeps no rim, or it would float on the water as a grey hairline.
    float3 between = mix(float3(1000.0), abs(lead / leadChange), step(float3(0.25), c));
    float nearest = min(abs(edge), min(between.r, min(between.g, between.b)));
    float thickest = max(h, max(max(strongest(right), strongest(left)), max(strongest(below), strongest(above))));
    float rim = (1.0 - smoothstep(0.4, 1.4, nearest)) * smoothstep(0.52, 0.68, thickest);

    // The lace inside the ink is lighter than the ink, cut one pixel wide like the ink's edge, and
    // casts a little shadow away from the light.
    float laceHere = uLace.eval(g).r;
    float2 laceSlope = float2(uLace.eval(g + float2(1.0, 0.0)).r - uLace.eval(g - float2(1.0, 0.0)).r,
        uLace.eval(g + float2(0.0, 1.0)).r - uLace.eval(g - float2(0.0, 1.0)).r) * 0.5;
    float laceChange = max(length(laceSlope * cell), 0.0005);
    float lace = clamp((laceHere - 0.29) / laceChange + 0.5, 0.0, 1.0);
    float raised = smoothstep(0.26, 0.32, uLace.eval(g - float2(2.0, 2.0) * cell).r);
    float3 surface = mix(ink, mix(ink, float3(1.0), 0.45), lace) * (1.0 - 0.22 * max(raised - lace, 0.0));

    // A light from the upper left: the ink's slope makes its edges look raised and glossy.
    float3 normal = normalize(float3(-slope.x * 1.2, -slope.y * 1.2, 1.0));
    float3 toLight = normalize(float3(-0.55, -0.65, 0.75));
    float facing = dot(normal, toLight) - toLight.z;
    float3 halfway = normalize(toLight + float3(0.0, 0.0, 1.0));
    float gloss = max(pow(max(dot(normal, halfway), 0.0), 48.0) - pow(halfway.z, 48.0), 0.0);
    surface = surface * (1.0 + 0.6 * facing) + gloss * 0.7;

    // A hat's new spots glint white for a moment.
    surface += float3(0.7) * uLace.eval(g).g;
    float3 colour = surface * cover;
    colour = mix(colour, float3(1.0), rim * 0.6);
    return half4(clamp(colour * uLight, 0.0, 1.0), 1.0);
}
"""
    }
}
