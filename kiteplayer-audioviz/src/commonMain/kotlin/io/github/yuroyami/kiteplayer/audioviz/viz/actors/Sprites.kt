package io.github.yuroyami.kiteplayer.audioviz.viz.actors

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Particles
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glyph
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.ring
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.shard
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import kotlin.math.cos
import kotlin.math.sin

/** The kinds of small thing a sprite pool or a traveller can be. */
internal object Sprite {
    const val GLOW = 0
    const val SPARK = 1
    const val SHARD = 2
    const val RING = 3
    const val HEX = 4
    const val CROSS = 5
    const val DIAMOND = 6
    const val CHEVRON = 7
    const val DASH = 8
    const val STREAK = 9
}

/** Adds one sprite of [kind] at [x], [y] in pixels. A streak points back along [vx], [vy]. */
internal fun TriangleMesh.sprite(kind: Int, x: Float, y: Float, size: Float, angle: Float, argb: Int, vx: Float = 0f, vy: Float = 0f) {
    when (kind) {
        Sprite.GLOW -> glow(x, y, size, argb)
        Sprite.SPARK -> spark(x, y, size, (size * 0.16f).coerceAtLeast(0.7f), argb)
        Sprite.SHARD -> shard(x, y, size, angle, argb)
        Sprite.RING -> ring(x, y, size, (size * 0.2f).coerceAtLeast(0.8f), argb, 16)
        Sprite.HEX -> polygon(x, y, size, 6, angle, argb, argb and 0x00FFFFFF)
        Sprite.CROSS -> glyph(0, x, y, size, angle, argb)
        Sprite.DIAMOND -> glyph(1, x, y, size, angle, argb)
        Sprite.CHEVRON -> glyph(2, x, y, size, angle, argb)
        Sprite.DASH -> glyph(3, x, y, size, angle, argb)
        else -> streak(x - vx, y - vy, x, y, (size * 0.5f).coerceAtLeast(1f), argb)
    }
}

/**
 * Small things that fly and fade: sparks, shards, specks. Positions are shares of the screen, sizes
 * shares of its shorter side, and the whole pool is drawn in one call.
 */
internal class Sprites(val capacity: Int, seed: Long) {
    val pool = Particles(capacity)
    private val mesh = TriangleMesh(maxVertices = (capacity * 10).coerceIn(64, 32_000))
    private val random = Rng(seed)

    /** Throws [count] from [x], [y] at about [speed] screens a second, within [spread] of [direction]. */
    fun burst(
        x: Float,
        y: Float,
        count: Int,
        speed: Float,
        life: Float,
        size: Float,
        tint: Float,
        kind: Int,
        direction: Float = 0f,
        spread: Float = TAU,
    ) {
        repeat(count) {
            val angle = direction + (random.next() - 0.5f) * spread
            val pace = speed * (0.4f + 0.8f * random.next())
            pool.spawn(
                atX = x,
                atY = y,
                speedX = cos(angle) * pace,
                speedY = sin(angle) * pace,
                seconds = life * (0.6f + 0.8f * random.next()),
                tintPosition = tint + random.next() * 0.15f,
                radius = size * (0.6f + 0.8f * random.next()),
                spin = random.signed() * 6f,
                kind = kind,
                angle = random.next() * TAU,
            )
        }
    }

    /** Scatters [count] across the whole screen, drifting at up to [drift] screens a second. */
    fun sprinkle(count: Int, life: Float, size: Float, tint: Float, kind: Int, drift: Float = 0.03f) {
        repeat(count) {
            pool.spawn(
                atX = random.next(),
                atY = random.next(),
                speedX = random.signed() * drift,
                speedY = random.signed() * drift,
                seconds = life * (0.6f + 0.8f * random.next()),
                tintPosition = tint + random.next() * 0.3f,
                radius = size * (0.5f + random.next()),
                spin = random.signed() * 3f,
                kind = kind,
                angle = random.next() * TAU,
            )
        }
    }

    fun advance(deltaSeconds: Float, drag: Float = 0.8f, gravity: Float = 0f) {
        pool.advance(deltaSeconds, gravity, drag)
    }

    /** Every live sprite in one call, fading at the end of its life. */
    fun DrawScope.drawSprites(
        palette: VizPalette,
        walk: Float,
        alpha: Float = 1f,
        blendMode: BlendMode = BlendMode.Plus,
        saturation: Float = 0.8f,
    ) {
        mesh.clear()
        val width = size.width
        val height = size.height
        val unit = size.minDimension
        for (slot in 0 until pool.capacity) {
            val alive = pool.remaining(slot)
            if (alive <= 0f) continue
            val fade = ((alive * 3f).coerceAtMost(1f) * alpha).coerceIn(0f, 1f)
            val argb = palette.cycled(pool.tint[slot] + walk, saturation = saturation, value = 1f, alpha = fade).toArgb()
            mesh.sprite(
                pool.kind[slot],
                pool.x[slot] * width,
                pool.y[slot] * height,
                pool.size[slot] * unit * (0.5f + 0.5f * alive),
                pool.angle[slot],
                argb,
                pool.velocityX[slot] * width * 0.08f,
                pool.velocityY[slot] * height * 0.08f,
            )
        }
        drawMesh(mesh, blendMode)
    }

    val live: Int get() = (0 until capacity).count { pool.life[it] > 0f }

    fun clear() {
        pool.clear()
        random.reset()
    }
}

/** Every live traveller as a sprite of its kind, in one call. */
internal fun DrawScope.drawTravellers(
    travellers: Travellers,
    mesh: TriangleMesh,
    palette: VizPalette,
    walk: Float,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.Plus,
) {
    mesh.clear()
    val width = size.width
    val height = size.height
    val unit = size.minDimension
    for (slot in 0 until travellers.capacity) {
        if (!travellers.alive[slot]) continue
        val t = travellers.progress[slot]
        val fade = ((1f - t) * 6f).coerceIn(0f, 1f) * alpha
        val argb = palette.cycled(travellers.tint[slot] + walk, saturation = 0.75f, value = 1f, alpha = fade.coerceIn(0f, 1f)).toArgb()
        val seconds = travellers.seconds[slot]
        val vx = (travellers.toX[slot] - travellers.fromX[slot]) / seconds * width * 0.06f
        val vy = (travellers.toY[slot] - travellers.fromY[slot]) / seconds * height * 0.06f
        mesh.sprite(
            travellers.kind[slot],
            travellers.x[slot] * width,
            travellers.y[slot] * height,
            travellers.size[slot] * unit,
            travellers.angle[slot],
            argb,
            vx,
            vy,
        )
    }
    drawMesh(mesh, blendMode)
}
