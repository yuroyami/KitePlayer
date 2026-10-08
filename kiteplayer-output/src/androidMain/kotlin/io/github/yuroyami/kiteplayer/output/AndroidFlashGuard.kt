package io.github.yuroyami.kiteplayer.output

import android.opengl.GLES20
import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import java.nio.ByteBuffer

/**
 * The flash guard's reading of a picture on the GL tier (#500): a small framebuffer the blit draws
 * the picture into, unadjusted, and its read back as the detector's cells. The rule is in
 * `docs/video-flash-guard.md`.
 *
 * The framebuffer is [WIDTH] by [HEIGHT] texels, one for each point the detector samples. It is
 * read back at once, before the picture itself is drawn, so the picture that completes a flashing
 * run is already dimmed. One GL thread's, with its context current.
 */
@OptIn(KitePlayerLowLevelApi::class)
internal class GlFlashMeter : AutoCloseable {
    private var framebuffer = 0
    private var texture = 0
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
    private val bytes = ByteArray(WIDTH * HEIGHT * 4)
    private val cells = FloatArray(VideoFlashGuard.MEASURES)

    /**
     * Binds the meter's framebuffer, has [draw] draw the whole picture over it, and answers the
     * picture's cells. Leaves the default framebuffer bound; the caller sets its own viewport again.
     */
    fun measure(draw: () -> Unit): FloatArray {
        if (framebuffer == 0) create()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        try {
            GLES20.glViewport(0, 0, WIDTH, HEIGHT)
            draw()
            pixels.position(0)
            GLES20.glReadPixels(0, 0, WIDTH, HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        pixels.position(0)
        pixels.get(bytes)
        // Rows come back bottom first. The detector counts cells, so which way up they are is no matter.
        VideoFlashGuard.cellsFromRgba(bytes, WIDTH, HEIGHT, into = cells)
        return cells
    }

    private fun create() {
        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "the flash guard's framebuffer is incomplete: $status" }
    }

    /** Gives back the framebuffer and its texture. The context must still be current. */
    override fun close() {
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        framebuffer = 0
        texture = 0
    }

    companion object {
        /** Four points across and down in each of the detector's cells. */
        const val WIDTH: Int = VideoFlashGuard.COLUMNS * 4
        const val HEIGHT: Int = VideoFlashGuard.ROWS * 4
    }
}

/**
 * [adjust], as `GlState.packGlAdjust` packs it, with the flash guard's [factor] folded in (#500):
 * the colour matrix and its offsets times the factor, or the factor alone when the picture controls
 * are neutral. A factor of 1 gives [adjust] back, so a picture outside a flashing run is drawn
 * exactly as before.
 */
internal fun dimGlAdjust(adjust: FloatArray?, factor: Float): FloatArray? {
    if (factor >= 1f) return adjust
    if (adjust == null || adjust[12] == 0f) {
        val dimmed = adjust?.copyOf() ?: FloatArray(15).also { it[13] = 1f }
        for (i in 0 until 12) dimmed[i] = 0f
        dimmed[0] = factor
        dimmed[4] = factor
        dimmed[8] = factor
        dimmed[12] = 1f
        return dimmed
    }
    return adjust.copyOf().also { dimmed -> for (i in 0 until 12) dimmed[i] *= factor }
}

/**
 * [matrix], Android's 4 by 5 colour matrix with offsets in 0..255 or null for none, with the flash
 * guard's [factor] folded into its three colour rows. A factor of 1 gives [matrix] back.
 */
internal fun dimColorMatrix(matrix: FloatArray?, factor: Float): FloatArray? {
    if (factor >= 1f) return matrix
    val dimmed = matrix?.copyOf() ?: FloatArray(20).also {
        it[0] = 1f
        it[6] = 1f
        it[12] = 1f
        it[18] = 1f
    }
    for (i in 0 until 15) dimmed[i] *= factor
    return dimmed
}
