package io.github.yuroyami.kiteplayer.audioviz.viz.fluid

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A small grid of moving liquid, simulated on the processor.
 *
 * This is Jos Stam's stable fluids method, which most real time smoke and ink is built on. Each step
 * carries the velocity and the dye along the velocity, then takes out any part of the flow that would
 * squeeze the liquid together or pull it apart. That second part is what makes it swirl like a liquid
 * instead of sliding like sand. Some of the spin the method loses is put back each step, or small
 * eddies would die out.
 *
 * Positions are in cells and velocities in cells per second, with y running down. A border one cell
 * wide walls the liquid in.
 */
internal class FluidGrid(val width: Int, val height: Int, dyeChannels: Int = 3) {
    private val stride = width + 2
    private val cells = stride * (height + 2)

    val velocityX = FloatArray(cells)
    val velocityY = FloatArray(cells)

    /** Dye, one array per colour channel. */
    val dye: Array<FloatArray> = Array(dyeChannels) { FloatArray(cells) }

    private val carriedX = FloatArray(cells)
    private val carriedY = FloatArray(cells)
    private val carriedDye = FloatArray(cells)
    private val pressure = FloatArray(cells)
    private val divergence = FloatArray(cells)
    private val spin = FloatArray(cells)

    /** Where cell [x], [y] of the inside sits in the arrays. Both count from 0. */
    fun index(x: Int, y: Int): Int = (x + 1) + (y + 1) * stride

    fun clear() {
        velocityX.fill(0f)
        velocityY.fill(0f)
        pressure.fill(0f)
        for (channel in dye) channel.fill(0f)
    }

    /**
     * Pushes the liquid and drops dye into it, in a soft round patch.
     *
     * [x] and [y] run 0 to 1 across the grid, [radius] is a share of its height, the push is in cells
     * per second, and the colour is how much dye goes into each channel at the middle of the patch.
     */
    fun splat(
        x: Float,
        y: Float,
        radius: Float,
        pushX: Float,
        pushY: Float,
        red: Float,
        green: Float = 0f,
        blue: Float = 0f,
    ) {
        val middleX = x * width
        val middleY = y * height
        val spread = (radius * height).coerceAtLeast(0.8f)
        val reach = (spread * 2.5f).toInt() + 1
        val fromX = (middleX.toInt() - reach).coerceAtLeast(0)
        val toX = (middleX.toInt() + reach).coerceAtMost(width - 1)
        val fromY = (middleY.toInt() - reach).coerceAtLeast(0)
        val toY = (middleY.toInt() + reach).coerceAtMost(height - 1)
        for (cellY in fromY..toY) {
            for (cellX in fromX..toX) {
                val dx = cellX + 0.5f - middleX
                val dy = cellY + 0.5f - middleY
                val weight = exp(-(dx * dx + dy * dy) / (spread * spread))
                if (weight < 0.01f) continue
                val at = index(cellX, cellY)
                velocityX[at] += pushX * weight
                velocityY[at] += pushY * weight
                dye[0][at] += red * weight
                if (dye.size > 2) {
                    dye[1][at] += green * weight
                    dye[2][at] += blue * weight
                }
            }
        }
    }

    /** Lifts every cell by the dye in its first channel, which is how warm smoke rises. */
    fun rise(strength: Float, deltaSeconds: Float) {
        val warmth = dye[0]
        val lift = strength * deltaSeconds
        for (at in 0 until cells) velocityY[at] -= warmth[at] * lift
    }

    /**
     * Moves everything on by [deltaSeconds].
     *
     * [curl] is how much of the lost spin to put back. [dyeKept] and [speedKept] are the shares of dye
     * and of speed that survive one second, both under one.
     */
    fun step(deltaSeconds: Float, curl: Float, dyeKept: Float, speedKept: Float) {
        val dt = deltaSeconds.coerceIn(0f, MAX_STEP)
        if (dt <= 0f) return
        if (curl > 0f) confine(curl, dt)
        walls()
        project()
        carry(carriedX, velocityX, dt)
        carry(carriedY, velocityY, dt)
        carriedX.copyInto(velocityX)
        carriedY.copyInto(velocityY)
        walls()
        project()

        val dyeScale = dyeKept.coerceIn(0f, 1f).pow(dt)
        for (channel in dye) {
            carry(carriedDye, channel, dt)
            for (at in 0 until cells) channel[at] = carriedDye[at] * dyeScale
        }
        val speedScale = speedKept.coerceIn(0f, 1f).pow(dt)
        for (at in 0 until cells) {
            velocityX[at] *= speedScale
            velocityY[at] *= speedScale
        }
    }

    /** Each cell takes the value from where its liquid was a moment ago, blended between cells. */
    private fun carry(into: FloatArray, from: FloatArray, dt: Float) {
        val mostX = width + 0.5f
        val mostY = height + 0.5f
        for (y in 1..height) {
            var at = 1 + y * stride
            for (x in 1..width) {
                val backX = (x - dt * velocityX[at]).coerceIn(0.5f, mostX)
                val backY = (y - dt * velocityY[at]).coerceIn(0.5f, mostY)
                val left = backX.toInt()
                val top = backY.toInt()
                val right = backX - left
                val down = backY - top
                val corner = left + top * stride
                val upper = from[corner] + (from[corner + 1] - from[corner]) * right
                val lower = from[corner + stride] + (from[corner + stride + 1] - from[corner + stride]) * right
                into[at] = upper + (lower - upper) * down
                at++
            }
        }
        edges(into)
    }

    /** Takes out the part of the flow that would squeeze the liquid, which leaves the swirl. */
    private fun project() {
        for (y in 1..height) {
            var at = 1 + y * stride
            for (x in 1..width) {
                divergence[at] = -0.5f * (velocityX[at + 1] - velocityX[at - 1] + velocityY[at + stride] - velocityY[at - stride])
                at++
            }
        }
        edges(divergence)
        // Last step's pressure is a good first guess, so a few passes are enough.
        repeat(PRESSURE_PASSES) {
            for (y in 1..height) {
                var at = 1 + y * stride
                for (x in 1..width) {
                    pressure[at] = (divergence[at] + pressure[at - 1] + pressure[at + 1] + pressure[at - stride] + pressure[at + stride]) * 0.25f
                    at++
                }
            }
            edges(pressure)
        }
        for (y in 1..height) {
            var at = 1 + y * stride
            for (x in 1..width) {
                velocityX[at] -= 0.5f * (pressure[at + 1] - pressure[at - 1])
                velocityY[at] -= 0.5f * (pressure[at + stride] - pressure[at - stride])
                at++
            }
        }
        walls()
    }

    /** Puts back some of the spin the method loses, which keeps small eddies alive. */
    private fun confine(strength: Float, dt: Float) {
        for (y in 1..height) {
            var at = 1 + y * stride
            for (x in 1..width) {
                spin[at] = 0.5f * (velocityY[at + 1] - velocityY[at - 1] - velocityX[at + stride] + velocityX[at - stride])
                at++
            }
        }
        for (y in 2 until height) {
            var at = 2 + y * stride
            for (x in 2 until width) {
                val towardX = 0.5f * (abs(spin[at + 1]) - abs(spin[at - 1]))
                val towardY = 0.5f * (abs(spin[at + stride]) - abs(spin[at - stride]))
                val length = sqrt(towardX * towardX + towardY * towardY) + 1e-5f
                val here = spin[at] * strength * dt / length
                velocityX[at] += towardY * here
                velocityY[at] -= towardX * here
                at++
            }
        }
    }

    /** Liquid slides along the walls but cannot pass through them. */
    private fun walls() {
        for (y in 1..height) {
            val left = y * stride
            val right = width + 1 + y * stride
            velocityX[left] = -velocityX[left + 1]
            velocityX[right] = -velocityX[right - 1]
            velocityY[left] = velocityY[left + 1]
            velocityY[right] = velocityY[right - 1]
        }
        for (x in 1..width) {
            val bottom = x + (height + 1) * stride
            velocityY[x] = -velocityY[x + stride]
            velocityY[bottom] = -velocityY[bottom - stride]
            velocityX[x] = velocityX[x + stride]
            velocityX[bottom] = velocityX[bottom - stride]
        }
    }

    /** The border of a plain quantity copies the cell just inside it. */
    private fun edges(field: FloatArray) {
        for (y in 1..height) {
            field[y * stride] = field[1 + y * stride]
            field[width + 1 + y * stride] = field[width + y * stride]
        }
        for (x in 0..width + 1) {
            field[x] = field[x + stride]
            field[x + (height + 1) * stride] = field[x + height * stride]
        }
    }

    private companion object {
        /** The longest step taken at once. A stalled frame is not allowed to throw the liquid about. */
        const val MAX_STEP = 1f / 20f

        const val PRESSURE_PASSES = 16
    }
}
