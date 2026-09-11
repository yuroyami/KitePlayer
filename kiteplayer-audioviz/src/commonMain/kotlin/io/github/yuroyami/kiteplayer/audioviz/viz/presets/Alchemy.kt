package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The classic plasma, set travelling: the field scrolls one screen every two bars, two layers of strips
 * cross it at other angles, and glowing blobs ride its brightest ridges. A kick bends the field and the
 * strips turn to a new angle every phrase.
 *
 * Drawn as one mesh of smoothly shaded cells, which the graphics card interpolates, so a full screen of
 * colour costs a single call.
 */
internal class Plasma : Layered(
    name = "Plasma",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 501L, detailKind = DetailKind.Hatch, camera = Camera2D(wander = 0.06f, seed = 501)),
) {
    override val paintsWholeScreen: Boolean get() = true

    private val strips = genes.choice("Strips", 3)
    private val backwards = genes.toggle("Scroll backwards", start = false)
    private val waves = genes.number("Waves", 0.7f, 1.6f, 1f)
    private val blobs = genes.choice("Ridge blobs", 3, start = 1)

    private var flow = 0f
    private var travel = 0f
    private val angle = Slew(maxPerSecond = 0.6f)
    private val bend = Spring(stiffness = 120f, damping = 0.5f)
    private val blobX = FloatArray(BLOBS) { 0.5f }
    private val blobY = FloatArray(BLOBS) { 0.5f }
    private val samples = FloatArray(GRID_X * GRID_Y)
    private val taken = BooleanArray(GRID_X * GRID_Y)
    private val blobMesh = TriangleMesh(maxVertices = BLOBS * 8 + 8)
    private val sheet = TriangleMesh(maxVertices = (COLUMNS + 1) * (ROWS + 1), maxIndices = COLUMNS * ROWS * 6)
    private val comets = Comets()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        flow += dt * 3.3f * state.tempo
        travel += dt / (gestures.barSeconds * 2f) * if (backwards.on) -1f else 1f
        bend.kick(gestures.kickHit * 5f)
        bend.advance(dt)
        if (gestures.phrase) strips.choose((strips.value + 1) % 3)
        angle.advance(strips.value * 0.785f, dt)
        // The blobs glide toward the brightest points of the field, a few cells apart.
        for (index in samples.indices) {
            samples[index] = field((index % GRID_X + 0.5f) / GRID_X, (index / GRID_X + 0.5f) / GRID_Y, state)
        }
        taken.fill(false)
        val ease = (dt * 2f).coerceAtMost(1f)
        for (blob in 0 until BLOBS) {
            var best = -1
            for (index in samples.indices) if (!taken[index] && (best < 0 || samples[index] > samples[best])) best = index
            if (best < 0) break
            val column = best % GRID_X
            val row = best / GRID_X
            for (dy in -1..1) for (dx in -1..1) {
                val x = column + dx
                val y = row + dy
                if (x in 0 until GRID_X && y in 0 until GRID_Y) taken[y * GRID_X + x] = true
            }
            blobX[blob] += ((column + 0.5f) / GRID_X - blobX[blob]) * ease
            blobY[blob] += ((row + 0.5f) / GRID_Y - blobY[blob]) * ease
            kit.place(blob, blobX[blob], blobY[blob])
        }
        comets.advance(state, gestures, random)
        kit.follow(BLOBS, comets.travellers)
    }

    private fun field(u: Float, v: Float, state: VizRenderState): Float {
        val a = angle.value
        val c = cos(a)
        val s = sin(a)
        val x = (u - 0.5f) * c - (v - 0.5f) * s + 0.5f + travel
        val y = (u - 0.5f) * s + (v - 0.5f) * c + 0.5f
        val distance = sqrt((u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f))
        val k = waves.value
        return sin(x * (7f + 4f * state.body) * k + flow) + sin(y * 5.5f * k - flow * 0.7f) +
            sin((x + y) * 4.5f * k + flow * 1.3f) + sin(distance * (14f + 7f * bend.value.coerceIn(-1f, 2f)) - flow * 2f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        val lift = state.lift
        // The field as one smoothly shaded mesh: one call, where a gradient per row would cost most of a frame.
        sheet.clear()
        for (row in 0..ROWS) {
            val v = row.toFloat() / ROWS
            for (column in 0..COLUMNS) {
                val u = column.toFloat() / COLUMNS
                val energy = if (bands.isEmpty()) 0f else bands.sampleAt(u)
                val colour = state.palette.cycled(
                    field(u, v, state) / 8f + 0.5f + energy * 0.3f + genes.walk,
                    value = (0.1f + 0.45f * energy + 0.45f * lift).coerceIn(0f, 1f),
                )
                sheet.vertex(u * size.width, v * size.height, colour.toArgb())
            }
        }
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                val corner = row * (COLUMNS + 1) + column
                sheet.quad(corner, corner + 1, corner + COLUMNS + 2, corner + COLUMNS + 1)
            }
        }
        drawMesh(sheet)
        // Two layers of translucent strips crossing the field at other angles, scrolling with it.
        val span = sceneRadius * 2f
        for (layer in 0 until 2) {
            rotate(60f + 60f * layer + angle.value * 57.29578f, center) {
                val step = span / 10f
                val shift = (travel * (layer + 1) * 2f).let { it - kotlin.math.floor(it) } * step
                for (strip in -1 until 11) {
                    val energy = if (bands.isEmpty()) 0f else bands.sampleAt(((strip + 10) % 10) / 10f)
                    drawRect(
                        state.palette.cycled(strip * 0.1f + layer * 0.5f + genes.walk, alpha = ((0.08f + 0.14f * energy) * lift).coerceIn(0f, 1f)),
                        topLeft = Offset(center.x - span, center.y - span / 2f + strip * step + shift),
                        size = Size(span * 2f, step * 0.45f),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        blobMesh.clear()
        for (blob in 0 until BLOBS) {
            val presence = blobs.presence(blob, 0, 3)
            if (presence <= 0.01f) continue
            blobMesh.glow(blobX[blob] * size.width, blobY[blob] * size.height, size.minDimension * 0.06f, state.palette.argb(blob * 0.15f + genes.walk, saturation = 0.4f, value = 1f, alpha = (0.35f + 0.5f * state.lift) * presence))
        }
        drawMesh(blobMesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    override fun onReset() {
        flow = 0f
        travel = 0f
        angle.reset()
        bend.reset()
        blobX.fill(0.5f)
        blobY.fill(0.5f)
        comets.clear()
    }

    private companion object {
        const val COLUMNS = 40
        const val ROWS = 24
        const val BLOBS = 6
        const val GRID_X = 12
        const val GRID_Y = 8
    }
}

/**
 * Soft clouds on up to three depths, on looping paths that span the screen, one lap every four bars,
 * passing in front of each other. Each cloud is lobed by its part of the spectrum, stars twinkle in
 * front, comets cross, and a drop parts the clouds to the edges and lets them drift back.
 */
internal class Nebula : Layered(
    name = "Nebula",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 502L, groundKind = GroundKind.Cloud, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.08f, seed = 502)),
) {
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.56f)

    private val clouds = genes.choice("Clouds", 3, start = 2)
    private val depths = genes.choice("Depths", 3, start = 2)
    private val paths = genes.choice("Paths", 3)
    private val lobes = genes.choice("Lobes", 3, start = 1)

    private val stage = Stage(reachX = 0.22f, reachY = 0.16f, start = 0.3f)
    private var phase = 0f
    private val bulge = FloatArray(MOST)
    private var parting = 0f
    private val cloudX = FloatArray(MOST) { 0.5f }
    private val cloudY = FloatArray(MOST) { 0.5f }
    private val comets = Comets(size = 0.022f)
    private val snareComets = Travellers(8)
    private val cometMesh = TriangleMesh(maxVertices = 8 * 12 + 8)
    private val stars = Sprites(160, 2_502L)
    private var starCredit = 0f
    private val mesh = TriangleMesh(maxVertices = MOST * (SEGMENTS + 2) + 16)
    private val coreMesh = TriangleMesh(maxVertices = MOST * 7 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        phase += dt * TAU / (gestures.barSeconds * 4f) * (0.7f + 0.6f * state.drive)
        if (gestures.drop) parting = 1f
        parting = (parting - dt / gestures.barSeconds).coerceAtLeast(0f)
        val push = 1f + 0.7f * sin(parting * PI.toFloat())
        for (index in 0 until MOST) {
            bulge[index] = (bulge[index] - dt * 2f).coerceAtLeast(0f)
            var x = 0f
            var y = 0f
            for (family in 0 until 3) {
                val share = paths.weight(family)
                if (share <= 0f) continue
                x += share * 0.42f * sin(FX[family] * phase + index * GOLDEN)
                y += share * 0.38f * sin(FY[family] * phase + index * GOLDEN * 1.7f + SHIFT[family])
            }
            cloudX[index] = stage.x + x * push
            cloudY[index] = stage.y + y * push
            if (index < 3) kit.place(index, cloudX[index], cloudY[index])
        }
        if (gestures.kickHit > 0f) {
            var nearest = 0
            var best = 9f
            for (index in 0 until clouds.count(12, 6)) {
                val dx = cloudX[index] - 0.5f
                val dy = cloudY[index] - 0.5f
                if (dx * dx + dy * dy < best) {
                    best = dx * dx + dy * dy
                    nearest = index
                }
            }
            bulge[nearest] = 1f
        }
        if (gestures.snareHit > 0f) snareComets.across(random, gestures.beatSeconds * 3f, PathShape.Arc, 0.15f, 0.022f, random.next(), 0f, Sprite.GLOW)
        snareComets.advance(dt)
        comets.advance(state, gestures, random)
        kit.follow(3, comets.travellers)
        starCredit += dt * (30f + 60f * state.air)
        while (starCredit >= 1f) {
            starCredit -= 1f
            stars.sprinkle(1, 1.2f, 0.016f, random.next(), Sprite.SPARK, drift = 0.02f)
        }
        stars.advance(dt, drag = 0.3f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val bands = state.frame.bandsRel
        val count = clouds.drawn(12, 6)
        val layers = depths.count(1)
        val lobeCount = 3 + 2 * lobes.value
        val lift = 0.3f + 0.7f * state.lift
        // Far clouds first, so the near ones pass in front.
        for (pass in 0 until layers) {
            for (index in pass until count step layers) {
                val presence = clouds.presence(index, 12, 6)
                if (presence <= 0.01f) continue
                val depth = (pass + 1f) / layers
                val energy = if (bands.isEmpty()) 0f else bands.sampleAt(index.toFloat() / count)
                val radius = sceneRadius * (0.1f + 0.14f * energy) * (0.6f + 0.6f * depth) * (1f + 0.4f * bulge[index])
                val colour = state.palette.argb(index.toFloat() / MOST + genes.walk, value = 0.8f, alpha = (0.1f + 0.14f * depth) * lift * presence)
                addCloud(cloudX[index] * size.width, cloudY[index] * size.height, radius, lobeCount, split.level(index % 3), index, colour)
            }
        }
        drawMesh(mesh, BlendMode.Plus)
        // A hard bright point at the heart of each cloud, so their paths read as motion.
        coreMesh.clear()
        for (index in 0 until count) {
            val presence = clouds.presence(index, 12, 6)
            if (presence <= 0.01f) continue
            coreMesh.glow(cloudX[index] * size.width, cloudY[index] * size.height, size.minDimension * 0.008f, state.palette.argb(index.toFloat() / MOST + genes.walk, saturation = 0.4f, value = 1f, alpha = (0.3f + 0.5f * state.lift) * presence))
        }
        drawMesh(coreMesh, BlendMode.Plus)
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        for (index in 0 until count) {
            val presence = clouds.presence(index, 12, 6)
            if (presence <= 0.01f) continue
            val rim = state.palette.cycled(index.toFloat() / MOST + genes.walk, value = 1f, alpha = ((0.06f + 0.22f * state.lift) * presence).coerceIn(0f, 1f))
            drawCircle(rim, sceneRadius * 0.12f * (1f + 0.4f * bulge[index]), Offset(cloudX[index] * size.width, cloudY[index] * size.height), style = Stroke(thin))
        }
        drawTravellers(snareComets, cometMesh, state.palette, genes.walk, alpha = lift)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    private fun addCloud(x: Float, y: Float, radius: Float, lobeCount: Int, level: Float, index: Int, colour: Int) {
        val clear = colour and 0x00FFFFFF
        val middle = mesh.vertex(x, y, colour)
        var previous = -1
        for (segment in 0..SEGMENTS) {
            val a = TAU * segment / SEGMENTS
            val reach = radius * (0.75f + 0.35f * level * sin(lobeCount * a + index + phase * 2f))
            val point = mesh.vertex(x + cos(a) * reach, y + sin(a) * reach, clear)
            if (previous >= 0) mesh.triangle(middle, previous, point)
            previous = point
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(stars) { drawSprites(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift, saturation = 0.3f) }
    }

    override fun onReset() {
        stage.reset()
        phase = 0f
        bulge.fill(0f)
        parting = 0f
        comets.clear()
        snareComets.clear()
        stars.clear()
        starCredit = 0f
    }

    private companion object {
        const val MOST = 24
        const val SEGMENTS = 24
        const val GOLDEN = 2.3999632f
        val FX = floatArrayOf(1f, 2f, 3f)
        val FY = floatArrayOf(2f, 3f, 2f)
        val SHIFT = floatArrayOf(0f, 0f, 1.5707964f)
    }
}

/**
 * Curtains of light on up to three depths, each sliding sideways at its own rate over a ridge of
 * mountains drawn from the spectrum, brightest round a spot that drifts across the sky. The lower ends reach down on loud bands, rays flicker through them
 * with the treble, a kick runs a wave along the front curtain, and a drop pulls every curtain to the floor.
 */
internal class Aurora : Layered(
    name = "Aurora",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 503L, groundKind = GroundKind.Fog, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.05f, seed = 503)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.5f, livelyTrail = 0.42f)

    private val depths = genes.choice("Depths", 3, start = 2)
    private val rates = genes.number("Scroll rates", 0.7f, 1.5f, 1f)
    private val ridgeHeight = genes.number("Ridge height", 0.08f, 0.22f, 0.14f)
    private val rayDensity = genes.number("Rays", 0.5f, 1.5f, 1f)

    private val stage = Stage(reachX = 0.3f, reachY = 0.3f, start = 0f)
    private val scroll = FloatArray(3)
    private var ridgeScroll = 0f
    private var wave = -1f
    private var dropHold = 0f
    private val floorPull = Envelope(attackPerSecond = 3f, releasePerSecond = 1f)
    private var time = 0f
    private val comets = Comets(size = 0.02f)
    private val curtainMesh = TriangleMesh(maxVertices = 3 * COLUMNS * 6 + 16)
    private val rayMesh = TriangleMesh(maxVertices = RAYS * 4 + 8)
    private val ridge = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        time += dt * state.tempo
        stage.advance(dt)
        for (layer in 0 until 3) scroll[layer] += dt / (gestures.barSeconds * BARS_PER_SCREEN[layer] / rates.value)
        ridgeScroll += dt / (gestures.barSeconds * 8f)
        if (gestures.kickHit > 0f) wave = 0f
        if (wave >= 0f) {
            wave += dt / (gestures.beatSeconds * 2f)
            if (wave > 1.2f) wave = -1f
        }
        if (gestures.drop) dropHold = gestures.barSeconds
        dropHold -= dt
        floorPull.advance(if (dropHold > 0f) 1f else 0f, dt)
        comets.advance(state, gestures, random)
        kit.place(0, wrap(scroll[0]), 0.35f)
        kit.follow(1, comets.travellers)
    }

    /** Where the lower edge of curtain [layer] hangs at [x], as a share of the height. */
    private fun bottomOf(layer: Int, x: Float, state: VizRenderState): Float {
        val bands = state.frame.bandsRel
        val energy = if (bands.isEmpty()) 0f else bands.foldedAt(wrap(x * (1f + 0.3f * layer) - scroll[layer]))
        var bottom = 0.25f + 0.3f * energy + 0.2f * state.lift - 0.06f * layer + stage.y - 0.5f
        if (layer == 0 && wave >= 0f) {
            val distance = (x - wave) / 0.08f
            bottom += 0.12f * kotlin.math.exp(-distance * distance)
        }
        val floor = 1f - ridgeHeight.value * 0.5f
        return bottom + (floor - bottom) * floorPull.value
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        curtainMesh.clear()
        val layers = depths.count(1)
        val lift = 0.3f + 0.7f * state.lift
        for (layer in layers - 1 downTo 0) {
            val far = layer.toFloat() / 3f
            val top = (0.02f + 0.06f * layer + stage.y - 0.5f).coerceAtLeast(0f) * size.height
            for (column in 0 until COLUMNS) {
                val x0 = column.toFloat() / COLUMNS
                val x1 = (column + 1f) / COLUMNS
                val bottom = bottomOf(layer, x0, state) * size.height
                // Brightest round a spot that drifts across the sky, so the curtains are never lit the same way twice.
                val spot = 0.15f + 0.85f * (0.5f + 0.5f * cos(TAU * (x0 - stage.x)))
                val bright = state.palette.argb(x0 * 0.6f + wrap(scroll[layer]) * 0.3f + layer * 0.15f + genes.walk, value = 0.8f, alpha = (0.35f + 0.4f * (1f - far)) * lift * spot)
                val clear = bright and 0x00FFFFFF
                val glowAt = top + (bottom - top) * 0.75f
                val left = x0 * size.width
                val right = x1 * size.width + 1f
                val a = curtainMesh.vertex(left, top, clear)
                val b = curtainMesh.vertex(right, top, clear)
                val c = curtainMesh.vertex(right, glowAt, bright)
                val d = curtainMesh.vertex(left, glowAt, bright)
                curtainMesh.quad(a, b, c, d)
                val e = curtainMesh.vertex(right, bottom, clear)
                val f = curtainMesh.vertex(left, bottom, clear)
                curtainMesh.quad(d, c, e, f)
            }
        }
        drawMesh(curtainMesh, BlendMode.Plus)
        // Vertical rays flickering with the treble.
        rayMesh.clear()
        val count = (RAYS * rayDensity.value / 1.5f).toInt().coerceIn(1, RAYS)
        for (ray in 0 until count) {
            val x = wrap(ray * 0.618f + scroll[0] * 0.5f)
            val flicker = 0.5f + 0.5f * sin(time * (3f + ray % 5) + ray)
            val alpha = (0.05f + 0.3f * state.air) * flicker * lift
            if (alpha <= 0.01f) continue
            val colour = state.palette.argb(0.55f + genes.walk, saturation = 0.4f, value = 1f, alpha = alpha)
            val width = size.width * 0.003f
            val bottom = bottomOf(0, x, state) * size.height
            val left = x * size.width
            val a = rayMesh.vertex(left - width, 0f, colour and 0x00FFFFFF)
            val b = rayMesh.vertex(left + width, 0f, colour and 0x00FFFFFF)
            val c = rayMesh.vertex(left + width, bottom, colour)
            val d = rayMesh.vertex(left - width, bottom, colour)
            rayMesh.quad(a, b, c, d)
        }
        drawMesh(rayMesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // The ridge of mountains along the bottom, its shape the spectrum, sliding slowly.
        val bands = state.frame.bandsRel
        ridge.reset()
        ridge.moveTo(0f, size.height)
        for (step in 0..RIDGE) {
            val x = step.toFloat() / RIDGE
            val energy = if (bands.isEmpty()) 0f else bands.foldedAt(wrap(x + ridgeScroll))
            ridge.lineTo(x * size.width, size.height * (1f - ridgeHeight.value * (0.4f + 0.6f * energy)))
        }
        ridge.lineTo(size.width, size.height)
        ridge.close()
        drawPath(ridge, state.palette.low.copy(alpha = 0.9f))
        drawPath(ridge, state.palette.mid.copy(alpha = 0.35f), style = Stroke((size.minDimension * 0.003f).coerceAtLeast(1f)))
    }

    override fun onReset() {
        stage.reset()
        scroll.fill(0f)
        ridgeScroll = 0f
        wave = -1f
        dropHold = 0f
        floorPull.reset()
        time = 0f
        comets.clear()
    }

    private companion object {
        const val COLUMNS = 60
        const val RAYS = 36
        const val RIDGE = 64
        val BARS_PER_SCREEN = floatArrayOf(2f, 4f, 8f)
    }
}

/**
 * Wedges of spectrum folded round a centre that wanders off the middle, so the echo's fold mirrors it
 * into new arrangements. Shards thrown in on the drums are folded too, a sweep goes round once a bar,
 * and a drop takes the fold to its most mirrors and spins it a full turn in a bar.
 */
internal class Kaleidoscope : Layered(
    name = "Kaleidoscope",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 504L, groundKind = GroundKind.Plasma, groundDim = 0.8f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.05f, seed = 504)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.58f, calmSpin = 0.1f, livelySpin = 0.35f)
    override val warp: WarpSpec = WarpSpec(WarpFields.FOLD, calmAmount = 0.03f, livelyAmount = 0.08f)

    private val mirrors = genes.choice("Mirrors", 3, start = 1)
    private val offset = genes.number("Figure offset", 0f, 0.22f, 0.1f)
    private val spriteKind = genes.choice("Sprites", 3)
    private val groundFold = genes.toggle("Cells", start = false)

    private val turn = MusicClock(beatsPerCycle = 8f)
    private var spin = 0f
    private var extra = 0f
    private var fastLeft = 0f
    private val snap = Spring(stiffness = 180f, damping = 0.45f)
    private val wander = Orbiter(radiusX = 1f, radiusY = 1f, lapsPerBar = 0.35f)
    private var centreX = 0.5f
    private var centreY = 0.5f
    private var sweepAngle = 0f
    private val shards = Sprites(300, 1_504L)
    private val comets = Comets()
    private val wedge = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        ground?.kind = if (groundFold.on) GroundKind.Voronoi else GroundKind.Plasma
        spin = turn.advance(dt, state.frame.bpm, state.frame.beatConfidence, state.frame.phrasePhase, state.paced(0.2f)) * TAU
        if (gestures.drop) {
            mirrors.choose(2)
            fastLeft = gestures.barSeconds
        }
        if (fastLeft > 0f) {
            fastLeft -= dt
            extra += dt * TAU / gestures.barSeconds
        }
        snap.kick(gestures.kickHit * 6f)
        snap.advance(dt)
        wander.advance(state, gestures)
        centreX = 0.5f + offset.value * cos(wander.angle) / kit.aspect * 1.6f
        centreY = 0.5f + offset.value * sin(wander.angle)
        sweepAngle = gestures.barPhase * TAU
        kit.place(0, centreX + sin(sweepAngle) * 0.4f / kit.aspect, centreY - cos(sweepAngle) * 0.4f)
        kit.place(1, centreX, centreY)
        if (gestures.kickHit > 0f || gestures.snareHit > 0f) {
            val kind = when (spriteKind.value) {
                0 -> Sprite.SHARD
                1 -> Sprite.DIAMOND
                else -> Sprite.CROSS
            }
            shards.burst(centreX, centreY, 28, 0.5f, 0.8f, 0.02f, random.next(), kind)
        }
        shards.advance(dt, drag = 0.8f)
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        val at = Offset(centreX * size.width, centreY * size.height)
        val reach = sceneRadius * (0.95f + 0.3f * snap.value.coerceIn(0f, 1.2f))
        val lift = 0.35f + 0.65f * state.lift
        for (option in 0 until 3) {
            val share = mirrors.weight(option)
            if (share > 0.01f) drawWedges(state, at, reach, MIRRORS[option], share * lift, spin + extra)
        }
        drawWedges(state, at, reach * 0.5f, MIRRORS[mirrors.value], 0.6f * lift, -(spin + extra) * 1.5f)
        with(shards) { drawSprites(state.palette, genes.walk) }
        drawLine(state.palette.cap.copy(alpha = 0.5f * lift), at, polar(at, sweepAngle + spin + extra, reach), (size.minDimension * 0.006f).coerceAtLeast(1.5f), blendMode = BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    // Every other copy is flipped, which is what makes the seams line up.
    private fun DrawScope.drawWedges(state: VizRenderState, at: Offset, reach: Float, count: Int, alpha: Float, turn: Float) {
        val bands = state.frame.bandsRel
        val step = TAU / count
        for (mirror in 0 until count) {
            val flip = if (mirror % 2 == 0) 1f else -1f
            val base = step * mirror + turn
            wedge.reset()
            wedge.moveTo(at.x, at.y)
            for (point in 0..POINTS) {
                val along = point.toFloat() / POINTS
                val p = polar(at, base + flip * step * along, reach * (0.12f + 0.88f * bands.sampleAt(along)))
                wedge.lineTo(p.x, p.y)
            }
            wedge.close()
            drawPath(wedge, state.palette.cycled(mirror.toFloat() / count + genes.walk, alpha = (0.55f * alpha).coerceIn(0f, 1f)))
            drawPath(wedge, state.palette.cap.copy(alpha = (0.45f * alpha).coerceIn(0f, 1f)), style = Stroke(2f))
        }
    }

    override fun onReset() {
        turn.reset()
        spin = 0f
        extra = 0f
        fastLeft = 0f
        snap.reset()
        wander.reset()
        shards.clear()
        comets.clear()
    }

    private companion object {
        const val POINTS = 18
        val MIRRORS = intArrayOf(6, 8, 12)
    }
}

/**
 * Rings of spectrum rushing out of a vanishing point that orbits the screen, with a second tunnel
 * behind turning the other way. Debris flies out to the corners, a light sweeps down the tunnel on
 * every kick, a snare nudges the vanishing point, and a drop doubles the rush for a bar.
 */
internal class Tunnel : Layered(
    name = "Tunnel",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 505L, groundKind = GroundKind.Stars, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.05f, seed = 505)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.6f, calmZoom = 1.004f, livelyZoom = 1.015f)
    override val warp: WarpSpec = WarpSpec(WarpFields.TWIST, calmAmount = 0.01f, livelyAmount = 0.03f)

    private val shape = genes.choice("Ring shape", 3, start = 1)
    private val second = genes.toggle("Second tunnel", start = true)
    private val debrisKind = genes.choice("Debris", 3)
    private val twist = genes.number("Twist", 0f, 1.2f, 0.4f)

    private val stage = Stage(reachX = 0.2f, reachY = 0.15f, start = 1.2f)
    private val point = Orbiter(radiusX = 0.27f, radiusY = 0.22f, lapsPerBar = 0.3f)
    private var pointX = 0.5f
    private var pointY = 0.5f
    private var travelled = 0f
    private var rushHold = 0f
    private val surge = Spring(stiffness = 90f, damping = 0.6f)
    private val nudge = Spring(stiffness = 60f, damping = 0.6f)
    private var sweepDepth = -1f
    private var lastBeat = -1
    private val debris = Travellers(24)
    private val debrisMesh = TriangleMesh(maxVertices = 24 * 12 + 8)
    private val comets = Comets()
    private val ring = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        point.centreX = stage.x
        point.centreY = stage.y
        point.advance(state, gestures)
        nudge.kick(gestures.snareHit * 3f * random.signed())
        nudge.advance(dt)
        pointX = point.x + 0.05f * nudge.value
        pointY = point.y
        kit.place(1, pointX, pointY)
        surge.kick(gestures.kickHit * 4f)
        surge.advance(dt)
        if (gestures.drop) rushHold = gestures.barSeconds
        rushHold -= dt
        travelled += dt * (0.2f + 3.6f * state.drive + surge.value.coerceIn(0f, 3f)) * if (rushHold > 0f) 2f else 1f
        if (gestures.kickHit > 0f) sweepDepth = 0f
        if (sweepDepth >= 0f) {
            sweepDepth += dt / gestures.beatSeconds
            if (sweepDepth > 1f) sweepDepth = -1f
        }
        // Debris on the drums, and one piece a beat in any case.
        val beat = (gestures.barPhase * 4f).toInt()
        val newBeat = beat != lastBeat
        lastBeat = beat
        if (gestures.kickHit > 0f || gestures.snareHit > 0f || newBeat) {
            val kind = when (debrisKind.value) {
                0 -> Sprite.SHARD
                1 -> Sprite.HEX
                else -> Sprite.STREAK
            }
            val toX = if (random.next() < 0.5f) -0.1f else 1.1f
            val toY = if (random.next() < 0.5f) -0.1f else 1.1f
            debris.spawn(pointX, pointY, toX, toY, gestures.beatSeconds * 2f, PathShape.Line, 0f, 0.02f + 0.02f * state.lift, random.next(), random.signed() * 6f, kind)
        }
        debris.advance(dt)
        kit.follow(0, debris)
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val lift = 0.35f + 0.65f * state.lift
        val reach = sceneRadius * 1.1f
        drawTunnel(state, pointX, pointY, reach, 1f, lift)
        val behind = second.weight(1)
        if (behind > 0.01f) drawTunnel(state, 1f - pointX, 1f - pointY, reach * 0.7f, -1f, 0.4f * behind * lift)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    private fun DrawScope.drawTunnel(state: VizRenderState, x: Float, y: Float, reach: Float, way: Float, alpha: Float) {
        val bands = state.frame.bandsRel
        val vanishing = Offset(x * size.width, y * size.height)
        for (index in 0 until RINGS) {
            // Spacing grows towards the viewer, which is what gives the sense of depth.
            val depth = ((index + travelled) % RINGS) / RINGS
            val radius = reach * depth * depth
            if (radius < 1f) continue
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(1f - depth)
            // Near rings slide toward the middle of the screen, far ones stay at the vanishing point.
            val at = Offset(vanishing.x + (center.x - vanishing.x) * depth * 0.6f, vanishing.y + (center.y - vanishing.y) * depth * 0.6f)
            val lit = if (sweepDepth >= 0f && abs(depth - sweepDepth) < 0.08f) 1f else 0f
            val colour = state.palette.cycled(depth + genes.walk, value = (0.2f + 0.8f * energy + 0.3f * lit).coerceIn(0f, 1f), alpha = (depth * alpha + 0.4f * lit).coerceIn(0f, 1f))
            val stroke = Stroke((size.minDimension * 0.02f * depth * (1f + 2f * lit)).coerceAtLeast(1f))
            val turn = (twist.value * depth * TAU + travelled * 0.2f) * way
            val round = shape.weight(0)
            if (round > 0.01f) drawCircle(colour.copy(alpha = colour.alpha * round), radius, at, style = stroke)
            val figure = shape.weight(1)
            val hexagon = shape.weight(2)
            if (figure > 0.01f || hexagon > 0.01f) {
                val sides = if (figure >= hexagon) 40 else 6
                ring.reset()
                for (step in 0..sides) {
                    val along = step.toFloat() / sides
                    val swell = if (sides == 40 && bands.isNotEmpty()) 0.8f + 0.4f * bands.foldedAt(along) else 1f
                    val p = polar(at, TAU * along + turn, radius * swell)
                    if (step == 0) ring.moveTo(p.x, p.y) else ring.lineTo(p.x, p.y)
                }
                drawPath(ring, colour.copy(alpha = colour.alpha * maxOf(figure, hexagon)), style = stroke)
            }
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        drawTravellers(debris, debrisMesh, state.palette, genes.walk)
    }

    override fun onReset() {
        stage.reset()
        point.reset()
        travelled = 0f
        rushHold = 0f
        surge.reset()
        nudge.reset()
        sweepDepth = -1f
        lastBeat = -1
        debris.clear()
        comets.clear()
    }

    private fun abs(value: Float): Float = if (value < 0f) -value else value

    private companion object {
        const val RINGS = 22
    }
}

/**
 * Smoke rising through light: hundreds of motes in one or two columns climbing the full height on a
 * curl that the echo's flow warp stretches into streaks. A kick lets out a puff and embers, the wind
 * changes side every phrase, and a drop opens both columns full.
 */
internal class Smoke : Layered(
    name = "Smoke",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 506L, groundKind = GroundKind.Rays, groundDim = 0.7f, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.05f, seed = 506)),
) {
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.8f, calmZoom = 1.002f, livelyZoom = 1.01f)
    override val warp: WarpSpec = WarpSpec(WarpFields.FLOW, calmAmount = 0.008f, livelyAmount = 0.02f)

    private val columns = genes.toggle("Second column", start = true)
    private val windGene = genes.number("Wind", -1f, 1f, 0.2f)
    private val curl = genes.number("Curl", 0.6f, 1.6f, 1f)
    private val emberRate = genes.choice("Embers", 3, start = 1)

    private val motes = Sprites(900, 1_506L)
    private val embers = Sprites(200, 2_506L)
    private val wind = Slew(maxPerSecond = 0.5f)
    private var credit = 0f
    private var swirl = 0f
    private var fullHold = 0f
    private val wisps = Travellers(3)
    private val wispMesh = TriangleMesh(maxVertices = 3 * 12 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        swirl += dt * 0.6f * state.tempo
        wind.advance(windGene.value * if (gestures.phrases % 2 == 0) 1f else -1f, dt)
        if (gestures.drop) fullHold = gestures.barSeconds
        fullHold -= dt
        val twin = columns.weight(1)
        credit = (credit + dt * (40f + 380f * state.drive) * if (fullHold > 0f) 3f else 1f).coerceAtMost(60f)
        while (credit >= 1f) {
            credit -= 1f
            val right = twin > 0.01f && random.next() < 0.5f * twin
            val base = if (right) 0.68f else if (twin > 0.01f) 0.32f else 0.5f
            motes.burst(base + random.signed() * 0.08f, 1.02f, 1, 0.3f, 3.5f, 0.02f, random.next(), Sprite.GLOW, UP, 0.5f)
        }
        if (gestures.kickHit > 0f) {
            motes.burst(0.5f, 1f, 20, 0.5f, 3f, 0.024f, random.next(), Sprite.GLOW, UP, 1f)
            if (emberRate.value > 0) embers.burst(0.5f + random.signed() * 0.2f, 1f, 4 * emberRate.value, 0.9f, 1.5f, 0.01f, 0.05f, Sprite.SPARK, UP, 0.4f)
        }
        // A curl of two crossed sines carries each mote sideways as it rises.
        val pool = motes.pool
        for (slot in 0 until pool.capacity) {
            if (pool.life[slot] <= 0f) continue
            val turn = sin(pool.x[slot] * 5f * curl.value + swirl) * cos(pool.y[slot] * 4f * curl.value - swirl * 0.8f)
            pool.velocityX[slot] += (turn * 0.35f + wind.value * 0.08f) * dt
        }
        motes.advance(dt, drag = 0.2f, gravity = -0.15f)
        embers.advance(dt, drag = 0.1f, gravity = -0.5f)
        if (gestures.bar || !wisps.anyNewest) {
            wisps.spawn(0.3f + 0.4f * random.next(), 1.05f, 0.2f + 0.6f * random.next(), -0.05f, gestures.barSeconds * 0.9f, PathShape.Wave, 0.06f, 0.03f, random.next(), 0f, Sprite.GLOW)
        }
        wisps.advance(dt)
        kit.follow(0, wisps)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val lift = 0.3f + 0.7f * state.lift
        with(motes) { drawSprites(state.palette, genes.walk, alpha = 0.55f * lift, saturation = 0.5f) }
        with(embers) { drawSprites(state.palette, genes.walk) }
        drawTravellers(wisps, wispMesh, state.palette, genes.walk, alpha = lift)
    }

    override fun onReset() {
        motes.clear()
        embers.clear()
        wind.reset()
        credit = 0f
        swirl = 0f
        fullHold = 0f
        wisps.clear()
    }
}
