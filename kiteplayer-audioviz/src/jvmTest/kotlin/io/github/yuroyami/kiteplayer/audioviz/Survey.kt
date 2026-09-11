package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders every drawing under the drum loop and the pad once, and measures how busy, full and
 * changing the picture is. The density, screen motion and change tests all read the same run.
 *
 * AUDIOVIZ_SURVEY names drawings to measure, separated by commas. Every other run measures them all.
 */
internal object Survey {

    /** False when AUDIOVIZ_SURVEY_LOOSE is set: the tests print their tables and do not fail. */
    val strict: Boolean = System.getenv("AUDIOVIZ_SURVEY_LOOSE") == null

    private const val WIDTH = 320
    private const val HEIGHT = 200

    /** A shader is drawn by the processor here, so it gets a smaller canvas. Every measure is a share. */
    private const val SHADER_WIDTH = 240
    private const val SHADER_HEIGHT = 150

    // Windows, in frames at sixty a second. The analyser has already heard three seconds by frame 0.
    private const val FROM = 90
    private const val TO = 180
    private const val LATE_FROM = FROM + 480
    private const val LATE_TO = TO + 480
    private const val DRUM_FRAMES = LATE_TO
    private const val PAD_FRAMES = 360
    private const val GAP = 3

    /** Alive compares frames half a second apart, so slow smooth motion counts as motion. */
    private const val LAG = 30

    /** Frames in one bar of the drum loop, which runs at 130 bpm. */
    private const val DRUM_BAR = 60f * 60f * 4f / 130f

    class Measure(
        val busy: Float,
        val motion: Float,
        val flow: Float,
        val flowP90: Float,
        val ink: Float,
        val alive: Float,
        val edge: Float,
        val spread: Float,
        /** The main anchor's path, in screen widths a second. */
        val travel: Float,
        /** Share of one-bar windows in which some anchor spans sixty percent of the screen. */
        val crossing: Float,
        /** Share of two-bar windows with such a crossing. */
        val crossingSlow: Float,
        val changeOverlap: Float,
        val changeDiff: Float,
        val millis: Float,
    )

    class Row(val name: String, val family: VizFamily, val bucket: VizEnergy, val drums: Measure, val pad: Measure)

    val rows: List<Row> by lazy { run() }

    private fun run(): List<Row> {
        val selected = System.getenv("AUDIOVIZ_SURVEY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val catalogue = VizCatalog.create()
        val indices = catalogue.indices.filter { selected == null || catalogue[it].name in selected }
        val started = System.nanoTime()
        val rows = RenderHarness.inParallel(indices) { index ->
            val drawing = VizCatalog.create()[index]
            Row(
                drawing.name,
                drawing.family,
                drawing.bucket,
                measure(drawing, RenderHarness.Song.Lively, DRUM_FRAMES),
                measure(drawing, RenderHarness.Song.Calm, PAD_FRAMES),
            )
        }
        println("survey of ${rows.size} drawings took ${(System.nanoTime() - started) / 1_000_000_000} s")
        return rows
    }

    private fun measure(drawing: Visualization, song: RenderHarness.Song, frames: Int): Measure {
        val shader = drawing is ShaderPreset
        val width = if (shader) SHADER_WIDTH else WIDTH
        val height = if (shader) SHADER_HEIGHT else HEIGHT
        val aspect = width.toFloat() / height
        val ground = VizPalette.Prism.background.let {
            ((it.red * 255f).toInt() + (it.green * 255f).toInt() + (it.blue * 255f).toInt())
        }
        val recent = arrayOfNulls<IntArray>(7)
        val lagged = HashMap<Int, IntArray>()
        val alive = BooleanArray((width / 2) * (height / 2))
        var busy = 0f
        var motion = 0f
        var ink = 0f
        var edge = 0f
        var spread = 0f
        var pairs = 0
        val flows = ArrayList<Float>()
        val early = LongArray(width * height * 3)
        val late = LongArray(width * height * 3)
        var earlyCount = 0
        var lateCount = 0
        val trackX = ArrayList<FloatArray>()
        val trackY = ArrayList<FloatArray>()
        var millis = 0.0
        var timed = 0
        var lastNanos = System.nanoTime()

        RenderHarness.forEachFrame(drawing, width, height, frames, VizPalette.Prism, song) { bitmap, step ->
            val now = System.nanoTime()
            if (step > 0) {
                millis += (now - lastNanos) / 1e6
                timed++
            }
            val inWindow = step in FROM..(TO + 2 * GAP)
            val inLate = step in LATE_FROM until LATE_TO
            val inLag = step in FROM..(TO + LAG)
            if (inWindow || inLate || inLag) {
                val pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                if (inLag && (step - FROM) % GAP == 0) {
                    lagged.remove(step - LAG)?.let { markAlive(it, pixels, width, height, alive) }
                    lagged[step] = pixels
                }
                if (step in FROM until TO) {
                    accumulate(early, pixels)
                    earlyCount++
                }
                if (inLate) {
                    accumulate(late, pixels)
                    lateCount++
                }
                if (inWindow) {
                    recent[step % recent.size] = pixels
                    val before = recent[(step - GAP + recent.size) % recent.size]
                    if (step >= FROM + GAP && (step - FROM) % GAP == 0 && before != null) {
                        val (changed, total) = difference(before, pixels, width, height)
                        busy += changed
                        motion += total
                        ink += inkShare(pixels, ground, width, height)
                        val (s, e) = spreadAndEdge(pixels, ground, width, height)
                        spread += s
                        edge += e
                        pairs++
                        val further = recent[(step - 2 * GAP + recent.size) % recent.size]
                        if (step >= FROM + 2 * GAP && further != null) flows += coarseFlow(further, pixels, width, height)
                    }
                }
            }
            if (step >= FROM) {
                val anchors = drawing.anchors
                val xs = FloatArray(anchors.size) { anchors[it].x.coerceIn(0f, 1f) }
                val ys = FloatArray(anchors.size) { anchors[it].y.coerceIn(0f, 1f) }
                trackX += xs
                trackY += ys
            }
            lastNanos = System.nanoTime()
        }

        val count = pairs.coerceAtLeast(1)
        flows.sort()
        val cellsToWidths = 8f * 10f / width
        val flowMean = if (flows.isEmpty()) 0f else flows.sum() / flows.size * cellsToWidths
        val flowP90 = if (flows.isEmpty()) 0f else flows[(flows.size * 0.9f).toInt().coerceAtMost(flows.size - 1)] * cellsToWidths
        val (overlap, diff) = if (earlyCount > 0 && lateCount > 0) {
            exposureChange(early, earlyCount, late, lateCount, width, height)
        } else {
            Pair(-1f, -1f)
        }
        val barFrames = if (song == RenderHarness.Song.Lively) DRUM_BAR else 240f
        return Measure(
            busy = busy / count,
            motion = motion / count,
            flow = flowMean,
            flowP90 = flowP90,
            ink = ink / count,
            alive = alive.count { it }.toFloat() / alive.size,
            edge = edge / count,
            spread = spread / count,
            travel = travel(trackX, trackY, aspect),
            crossing = crossings(trackX, trackY, barFrames.toInt()),
            crossingSlow = crossings(trackX, trackY, (barFrames * 2f).toInt()),
            changeOverlap = overlap,
            changeDiff = diff,
            millis = if (timed == 0) 0f else (millis / timed).toFloat(),
        )
    }

    private fun accumulate(into: LongArray, pixels: IntArray) {
        for (index in pixels.indices) {
            val p = pixels[index]
            into[index * 3] += (p shr 16 and 0xFF).toLong()
            into[index * 3 + 1] += (p shr 8 and 0xFF).toLong()
            into[index * 3 + 2] += (p and 0xFF).toLong()
        }
    }

    /** Share of sampled pixels that moved more than a tenth, and the mean change. */
    private fun difference(a: IntArray, b: IntArray, width: Int, height: Int): Pair<Float, Float> {
        var changed = 0
        var total = 0L
        var counted = 0
        for (y in 0 until height - 1 step 2) {
            for (x in 0 until width - 1 step 2) {
                val p = a[y * width + x]
                val q = b[y * width + x]
                val d = abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) +
                    abs((p and 0xFF) - (q and 0xFF))
                total += d
                if (d > 24) changed++
                counted++
            }
        }
        return Pair(changed.toFloat() / counted, total.toFloat() / counted / 765f)
    }

    /** Marks every sampled pixel that changed by more than 12 of 765 between [a] and [b]. */
    private fun markAlive(a: IntArray, b: IntArray, width: Int, height: Int, alive: BooleanArray) {
        val columns = width / 2
        for (y in 0 until height - 1 step 2) {
            for (x in 0 until width - 1 step 2) {
                val p = a[y * width + x]
                val q = b[y * width + x]
                val d = abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) +
                    abs((p and 0xFF) - (q and 0xFF))
                if (d > 12) alive[(y / 2) * columns + x / 2] = true
            }
        }
    }

    private fun inkShare(pixels: IntArray, ground: Int, width: Int, height: Int): Float {
        var inked = 0
        var counted = 0
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val p = pixels[y * width + x]
                if ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) - ground > 30) inked++
                counted++
            }
        }
        return inked.toFloat() / counted
    }

    /** How far the light spreads from its own middle, and how much of it sits in the outer fifth. */
    private fun spreadAndEdge(pixels: IntArray, ground: Int, width: Int, height: Int): Pair<Float, Float> {
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        var weight = 0.0
        var edgeWeight = 0.0
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val p = pixels[y * width + x]
                val light = (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) - ground
                if (light <= 30) continue
                val w = light.toDouble()
                sumX += x * w
                sumY += y * w
                sumX2 += x.toDouble() * x * w
                sumY2 += y.toDouble() * y * w
                weight += w
                if (x < width * 0.2f || x > width * 0.8f || y < height * 0.2f || y > height * 0.8f) edgeWeight += w
            }
        }
        if (weight <= 0.0) return Pair(0f, 0f)
        val mx = sumX / weight
        val my = sumY / weight
        val variance = (sumX2 / weight - mx * mx) + (sumY2 / weight - my * my)
        val half = sqrt((width * width + height * height).toDouble()) / 2.0
        return Pair((sqrt(variance.coerceAtLeast(0.0)) / half).toFloat(), (edgeWeight / weight).toFloat())
    }

    /** How far textured blocks moved between two frames 100 ms apart, in 8 px cells. */
    private fun coarseFlow(a: IntArray, b: IntArray, width: Int, height: Int): List<Float> {
        val cell = 8
        val cw = width / cell
        val ch = height / cell
        val ca = coarse(a, width, cell, cw, ch)
        val cb = coarse(b, width, cell, cw, ch)
        val block = 4
        val radius = 6
        val out = ArrayList<Float>()
        var by = 0
        while (by + block <= ch) {
            var bx = 0
            while (bx + block <= cw) {
                var sum = 0f
                var sum2 = 0f
                for (y in by until by + block) for (x in bx until bx + block) {
                    val v = ca[y * cw + x]
                    sum += v
                    sum2 += v * v
                }
                val n = block * block
                val mean = sum / n
                if (sum2 / n - mean * mean >= 40f) {
                    var best = Float.MAX_VALUE
                    var bestD = 0f
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        val sx = bx + dx
                        val sy = by + dy
                        if (sx < 0 || sy < 0 || sx + block > cw || sy + block > ch) continue
                        var sad = 0f
                        for (y in 0 until block) for (x in 0 until block) {
                            sad += abs(ca[(by + y) * cw + bx + x] - cb[(sy + y) * cw + sx + x])
                        }
                        val bias = (dx * dx + dy * dy) * 0.5f
                        if (sad + bias < best) {
                            best = sad + bias
                            bestD = sqrt((dx * dx + dy * dy).toFloat())
                        }
                    }
                    out += bestD
                }
                bx += block
            }
            by += block
        }
        return out
    }

    private fun coarse(pixels: IntArray, width: Int, cell: Int, cw: Int, ch: Int): FloatArray {
        val out = FloatArray(cw * ch)
        for (cy in 0 until ch) for (cx in 0 until cw) {
            var sum = 0f
            for (y in cy * cell until (cy + 1) * cell) for (x in cx * cell until (cx + 1) * cell) {
                val p = pixels[y * width + x]
                sum += ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10f
            }
            out[cy * cw + cx] = sum / (cell * cell)
        }
        return out
    }

    /** Overlap of the two exposures' brightest thirds on a quarter-scale grid, and their mean difference. */
    private fun exposureChange(early: LongArray, earlyCount: Int, late: LongArray, lateCount: Int, width: Int, height: Int): Pair<Float, Float> {
        val cell = 4
        val cw = width / cell
        val ch = height / cell
        fun grey(sums: LongArray, count: Int): FloatArray {
            val out = FloatArray(cw * ch)
            for (cy in 0 until ch) for (cx in 0 until cw) {
                var total = 0f
                for (y in cy * cell until (cy + 1) * cell) for (x in cx * cell until (cx + 1) * cell) {
                    val at = (y * width + x) * 3
                    total += (sums[at] * 3 + sums[at + 1] * 6 + sums[at + 2]).toFloat() / 10f / count
                }
                out[cy * cw + cx] = total / (cell * cell)
            }
            return out
        }
        val a = grey(early, earlyCount)
        val b = grey(late, lateCount)
        fun mask(values: FloatArray): BooleanArray {
            val sorted = values.copyOf().also { it.sort() }
            val cut = sorted[(sorted.size * 0.7f).toInt().coerceAtMost(sorted.size - 1)]
            return BooleanArray(values.size) { values[it] > cut }
        }
        val ma = mask(a)
        val mb = mask(b)
        var both = 0
        var either = 0
        for (index in ma.indices) {
            if (ma[index] && mb[index]) both++
            if (ma[index] || mb[index]) either++
        }
        var diff = 0.0
        for (index in 0 until width * height) {
            for (channel in 0 until 3) {
                val at = index * 3 + channel
                diff += abs(early[at].toDouble() / earlyCount - late[at].toDouble() / lateCount)
            }
        }
        val overlap = if (either == 0) 1f else both.toFloat() / either
        return Pair(overlap, (diff / (width * height) / 765.0).toFloat())
    }

    /** The main anchor's path length, in screen widths a second. */
    private fun travel(xs: List<FloatArray>, ys: List<FloatArray>, aspect: Float): Float {
        var length = 0.0
        var frames = 0
        for (index in 1 until xs.size) {
            val a = xs[index - 1]
            val b = xs[index]
            if (a.isEmpty() || b.isEmpty()) continue
            val dx = b[0] - a[0]
            val dy = (ys[index][0] - ys[index - 1][0]) / aspect
            length += sqrt((dx * dx + dy * dy).toDouble())
            frames++
        }
        return if (frames == 0) 0f else (length / (frames / 60.0)).toFloat()
    }

    /** Share of windows [window] frames long in which some anchor spans at least sixty percent of the screen. */
    private fun crossings(xs: List<FloatArray>, ys: List<FloatArray>, window: Int): Float {
        if (xs.size < window) return 0f
        val step = (window / 4).coerceAtLeast(1)
        var windows = 0
        var crossed = 0
        var start = 0
        while (start + window <= xs.size) {
            windows++
            val anchors = xs.subList(start, start + window).maxOf { it.size }
            var found = false
            for (anchor in 0 until anchors) {
                var lowX = 1f
                var highX = 0f
                var lowY = 1f
                var highY = 0f
                for (frame in start until start + window) {
                    val row = xs[frame]
                    if (anchor >= row.size) continue
                    val x = row[anchor]
                    val y = ys[frame][anchor]
                    if (x < lowX) lowX = x
                    if (x > highX) highX = x
                    if (y < lowY) lowY = y
                    if (y > highY) highY = y
                }
                if (highX - lowX >= 0.6f || highY - lowY >= 0.6f) {
                    found = true
                    break
                }
            }
            if (found) crossed++
            start += step
        }
        return if (windows == 0) 0f else crossed.toFloat() / windows
    }

    fun number(value: Float): String = if (value < 0f) "  -  " else ((value * 100).toInt() / 100.0).toString().padStart(5)
}
