package io.github.yuroyami.kiteplayer.audioviz.viz

/**
 * A fixed pool of particles, reused forever.
 *
 * Drawings that spray dots run at sixty frames a second, so allocating a list of objects per frame
 * would hand the garbage collector a steady job for no reason. Everything here lives in flat arrays
 * that are written in place, and a dead particle is one whose [life] has run out.
 */
internal class Particles(val capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val velocityX = FloatArray(capacity)
    val velocityY = FloatArray(capacity)
    /** Counts down to zero. Zero means the slot is free. */
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    /** Where in the palette's cycle this one sits. */
    val tint = FloatArray(capacity)
    val size = FloatArray(capacity)
    /** Turn in radians, and how fast it turns, for sprites that are not round. */
    val angle = FloatArray(capacity)
    val spin = FloatArray(capacity)
    /** Which sprite to draw, for a pool that mixes them. */
    val kind = IntArray(capacity)

    private var nextSlot = 0

    /** Takes the next free slot, or the oldest one when every slot is busy. */
    fun spawn(
        atX: Float,
        atY: Float,
        speedX: Float,
        speedY: Float,
        seconds: Float,
        tintPosition: Float,
        radius: Float,
        spin: Float = 0f,
        kind: Int = 0,
        angle: Float = 0f,
    ) {
        var slot = -1
        for (offset in 0 until capacity) {
            val candidate = (nextSlot + offset) % capacity
            if (life[candidate] <= 0f) {
                slot = candidate
                break
            }
        }
        if (slot < 0) slot = nextSlot
        nextSlot = (slot + 1) % capacity

        x[slot] = atX
        y[slot] = atY
        velocityX[slot] = speedX
        velocityY[slot] = speedY
        life[slot] = seconds
        maxLife[slot] = seconds
        tint[slot] = tintPosition
        size[slot] = radius
        this.spin[slot] = spin
        this.kind[slot] = kind
        this.angle[slot] = angle
    }

    /** Moves every live particle and ages it. [gravity] is in units per second per second. */
    fun advance(deltaSeconds: Float, gravity: Float = 0f, drag: Float = 0f) {
        val keep = if (drag <= 0f) 1f else (1f - drag * deltaSeconds).coerceIn(0f, 1f)
        for (slot in 0 until capacity) {
            if (life[slot] <= 0f) continue
            velocityY[slot] += gravity * deltaSeconds
            velocityX[slot] *= keep
            velocityY[slot] *= keep
            x[slot] += velocityX[slot] * deltaSeconds
            y[slot] += velocityY[slot] * deltaSeconds
            angle[slot] += spin[slot] * deltaSeconds
            life[slot] -= deltaSeconds
        }
    }

    /** How much of this particle's life is left, 1 when fresh and 0 when gone. */
    fun remaining(slot: Int): Float =
        if (maxLife[slot] <= 0f) 0f else (life[slot] / maxLife[slot]).coerceIn(0f, 1f)

    fun clear() {
        life.fill(0f)
        nextSlot = 0
    }
}
