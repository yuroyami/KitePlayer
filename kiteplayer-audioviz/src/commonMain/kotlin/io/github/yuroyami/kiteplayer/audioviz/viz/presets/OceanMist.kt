package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.AudioEvent
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.Particles
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.inGamut
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.retentionOf
import io.github.yuroyami.kiteplayer.audioviz.viz.toOklab
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A night sea seen from a low cliff, under a low white moon. The sea is eighty rows of the waveform
 * laid out in depth: the front row is the sound now, and each older row lies farther out, smaller and
 * nearer the horizon, hiding the rows behind it. Glitter runs down the sea from the moon, and mist lies
 * on the horizon. The bass raises the swell, the level sets the light and how fast the rows roll out,
 * a kick lifts the front rows, a snare throws spray, and the hats thicken the glitter. On a drop a wave
 * three times the normal swell rolls in over a bar and breaks across the whole width.
 */
internal class OceanMist : Layered(
    name = "Ocean Mist",
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 203L,
        // A fixed viewpoint: the shared camera's snare nudge and drop whip never reach this picture.
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 203),
    ),
) {
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(KICK_SECONDS)),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Bass, VizProperty.Size, response = VizResponse.envelope(BASS_FALL)),
        VizDrive(VizDriver.Bass, VizProperty.Brightness, response = VizResponse.envelope(GLOW_FALL)),
        VizDrive(VizDriver.Level, VizProperty.Brightness, response = VizResponse.envelope(LIGHT_RISE)),
        VizDrive(VizDriver.Level, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(SPRAY_LIFE)),
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.envelope(HAT_SECONDS)),
        VizDrive(VizDriver.Width, VizProperty.Size, response = VizResponse.envelope(WIDTH_SECONDS)),
        VizDrive(VizDriver.Mood, VizProperty.Shape, response = VizResponse.envelope(MOOD_SECONDS)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(BREAKDOWN_SECONDS)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(GLIDE_SECONDS)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(WAVE_SECONDS)),
        VizDrive(VizDriver.Key, VizProperty.Colour, response = VizResponse.envelope(KEY_SECONDS)),
        echoes = true,
    )

    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = FINISH

    /** The background trail: how long the moon's glow lingers. The sea itself never smears. */
    override val trail: Float get() = trails.value

    private val waveHeight = VizParam("Wave height", 0.3f, 1.8f, 1f)
    private val mistAmount = VizParam("Mist", 0f, 2f, 1f)
    private val trails = VizParam("Trails", 0f, 0.85f, 0.6f)
    private val stereo = VizParam("Stereo traces", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    override val params: List<VizParam> = listOf(waveHeight, mistAmount, trails, stereo)

    // The rows of the sea, newest last in a ring. Each is POINTS heights in swell units, both ends at zero.
    private val store = FloatArray(STORE * POINTS)
    private val identity = IntArray(STORE)
    private var newest = -1
    private var stored = 0
    private var captures = 0
    private var roll = 0f

    // The live front row, what it was a frame ago, this frame's reading, and the two stereo traces.
    private val live = FloatArray(POINTS)
    private val before = FloatArray(POINTS)
    private val reading = FloatArray(POINTS)
    private val leftRow = FloatArray(POINTS)
    private val rightRow = FloatArray(POINTS)
    private val readingLeft = FloatArray(POINTS)
    private val readingRight = FloatArray(POINTS)
    private val middle = FloatArray(SCOPE_USE)

    // Smoothed readings of the music.
    private var mood = 0.5f
    private var bass = 0f
    private var glow = 0f
    private var energy = 0f
    private var width = 0f
    private var awake = 0f
    private var hats = 0f
    private var lean = 0f

    // Gestures.
    private var kickLift = 0f
    private val runnerX = FloatArray(RUNNERS)
    private val runnerAge = FloatArray(RUNNERS) { RUNNER_LIFE }
    private val runnerStrength = FloatArray(RUNNERS)
    private var nextRunner = 0
    private var crestX = 0.5f
    private var crestHeight = 0f
    private var breakdown = 0f
    private var breakdownHeld = false
    private var breakdownCycles = 0
    private var cyclesSeen = 0

    // The wave: its progress over one bar, how long that bar is, the break it ends in, and the mist it blows off.
    private var waveOn = false
    private var waveAt = 0f
    private var waveBar = 2f
    private var waveFall = 0f
    private var breakLight = 0f
    private var blow = 0f
    private var foam = 0f
    private var anticipated: AudioEvent? = null

    // The moon, in shares of the screen, gliding to a new place at a section.
    private var moonX = MOON_X
    private var moonRise = MOON_RISE
    private var moonToX = MOON_X
    private var moonToRise = MOON_RISE

    // Slow clocks: the glitter shimmer, which keeps a slow pace in silence, and the drift of the mist.
    private var shimmer = 0f
    private var drift = 0f

    private val spray = Particles(SPRAY_CAPACITY)

    // Colours, worked out once a frame.
    private var cachedPalette: VizPalette? = null
    private var lowHue = OWN_HORIZON_HUE
    private var midHue = OWN_MIST_HUE
    private var highHue = OWN_FRONT_HUE
    private var capHue = OWN_GLITTER_HUE
    private var horizonHue = OWN_HORIZON_HUE
    private var frontHue = OWN_FRONT_HUE
    private var glitterArgb = 0
    private var mistArgb = 0
    private var haloArgb = 0
    private var rightArgb = 0
    private var groundArgb = 0
    private var light = 0.35f
    private var lit = 0f
    private var motion = 1f

    // Drawing scratch, sized once.
    private val sea = TriangleMesh(maxVertices = MESH_VERTICES)
    private val mist = TriangleMesh(maxVertices = (MIST_COLUMNS + 1) * 8 + 8)
    private val moonFace = TriangleMesh(maxVertices = 49 + 4 * 25 + 8)
    private val halo = TriangleMesh(maxVertices = 80)
    private val drops = TriangleMesh(maxVertices = SPRAY_CAPACITY * (DROP_SIDES * 2 + 1) + 8)
    private val xs = FloatArray(MAX_POINTS)
    private var ysFar = FloatArray(MAX_POINTS)
    private var ysNear = FloatArray(MAX_POINTS)
    private var whiteFar = FloatArray(MAX_POINTS)
    private var whiteNear = FloatArray(MAX_POINTS)
    private val ysLeft = FloatArray(MAX_POINTS)
    private val ysRight = FloatArray(MAX_POINTS)
    private val whiteLeft = FloatArray(MAX_POINTS)
    private val whiteRight = FloatArray(MAX_POINTS)
    private val orderRing = IntArray(STORE + 1)
    private val orderSlot = FloatArray(STORE + 1)

    /** The front row as it is drawn now, in swell units, with both ends at the level. */
    internal val frontRow: FloatArray get() = live

    init {
        calmSea()
    }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        val dt = state.deltaSeconds.coerceIn(0f, 0.1f)
        // Anything that moves on its own stops while the player is paused; the music clock stops in silence too.
        val own = if (frame.held) 0f else dt
        val step = state.stepSeconds.coerceIn(0f, 0.1f)
        val audible = frame.audible
        motion = state.motionScale.coerceIn(0f, 1f)

        mood += (frame.mood - mood) * settle(own, MOOD_SECONDS)
        bass += (frame.bassRel - bass) * settle(own, if (frame.bassRel > bass) BASS_RISE else BASS_FALL)
        glow += (frame.bassRel - glow) * settle(own, if (frame.bassRel > glow) GLOW_RISE else GLOW_FALL)
        energy += (frame.energy - energy) * settle(own, if (frame.energy > energy) LIGHT_RISE else LIGHT_FALL)
        width += (frame.width - width) * settle(own, WIDTH_SECONDS)
        awake += (audible - awake) * settle(own, AWAKE_SECONDS)
        hats *= exp(-own / HAT_SECONDS)
        if (gestures.hat > 0f) hats = min(HAT_MOST, hats + HAT_JUMP * gestures.hatAccent)
        leanTowardsKey(frame.keyHue, frame.keyConfidence, own)

        readRows(state, own)
        rollRows(step)
        findCrest()

        kickLift *= exp(-own / KICK_SECONDS)
        if (gestures.kick > 0f) {
            kickLift = max(kickLift, KICK_JUMP * gestures.kickAccent)
            runnerX[nextRunner] = crestX
            runnerAge[nextRunner] = 0f
            runnerStrength[nextRunner] = (0.45f + 0.35f * gestures.kickAccent).coerceAtMost(1f)
            nextRunner = (nextRunner + 1) % RUNNERS
        }
        for (index in 0 until RUNNERS) runnerAge[index] = min(RUNNER_LIFE, runnerAge[index] + own)

        val count = gestures.snareSpawn(SPRAY_SNARE)
        if (count > 0) throwSpray(count, state.motionScale)
        spray.advance(own, SPRAY_GRAVITY, SPRAY_DRAG)

        followStructure(state, step, own)
        paint(state)
        shimmer += own * (SHIMMER_IDLE + SHIMMER_PLAYING * awake + SHIMMER_HATS * min(1f, hats))
        drift += step * MIST_DRIFT
    }

    /** Reads this frame's waveform into the live front row, and the two channels for the stereo traces. */
    private fun readRows(state: VizRenderState, own: Float) {
        val frame = state.frame
        val swell = SWELL_FLOOR + SWELL_BASS * bass
        val gain = frame.waveformGain * COMPRESS
        val follow = settle(own, LIVE_SECONDS) * frame.audible
        if (stereo.value >= 0.5f) {
            // The mix is taken from the same two traces, so it always lies between them.
            val left = frame.scopeLeft
            val right = frame.scopeRight
            val count = min(SCOPE_USE, min(left.size, right.size))
            for (index in 0 until count) middle[index] = 0.5f * (left[index] + right[index])
            if (count >= MIN_SCOPE) {
                extract(middle, count, gain, swell, reading)
                extract(left, count, gain, swell, readingLeft)
                extract(right, count, gain, swell, readingRight)
                blendInto(leftRow, readingLeft, follow)
                blendInto(rightRow, readingRight, follow)
                blendInto(live, reading, follow)
            }
        } else if (frame.scope.size >= MIN_SCOPE) {
            extract(frame.scope, min(SCOPE_USE, frame.scope.size), gain, swell, reading)
            blendInto(live, reading, follow)
            live.copyInto(leftRow)
            live.copyInto(rightRow)
        }
    }

    /**
     * One row from the newest samples. It starts at a rising zero crossing, as an oscilloscope does, so
     * a held note draws the same swell every frame. Calm music reads fewer samples and smooths them
     * more, which draws a long swell; lively music reads more and keeps its detail, which draws chop.
     */
    private fun extract(source: FloatArray, available: Int, gain: Float, swell: Float, out: FloatArray) {
        var start = 0
        var previous = smoothed(source, available, 0)
        for (index in 1 until TRIGGER_SEARCH) {
            val current = smoothed(source, available, index)
            if (previous <= 0f && current > 0f) {
                start = index
                break
            }
            previous = current
        }
        val length = (LONG_SWELL + (SHORT_CHOP - LONG_SWELL) * mood).coerceAtMost((available - start).toFloat())
        val sigma = length / (POINTS - 1) * (SMOOTH_CALM + (SMOOTH_LIVELY - SMOOTH_CALM) * mood)
        val reach = (GAUSS_REACH * sigma).toInt() + 1
        for (point in 0 until POINTS) {
            val centre = start + point * (length - 1f) / (POINTS - 1)
            val from = max(0, centre.toInt() - reach)
            val to = min(available - 1, centre.toInt() + reach + 1)
            var sum = 0f
            var weight = 0f
            for (index in from..to) {
                val w = gaussian((index - centre) / sigma)
                sum += source[index] * w
                weight += w
            }
            out[point] = if (weight > 0f) sum / weight else 0f
        }
        // Both ends pinned to the level, so a row that repeats across the width meets itself, and a
        // slice of a slow bass cycle reads as a swell rather than as a slope.
        val first = out[0]
        val last = out[POINTS - 1]
        for (point in 0 until POINTS) {
            val chord = first + (last - first) * point / (POINTS - 1)
            out[point] = tanh((out[point] - chord) * gain) * swell
        }
    }

    private fun smoothed(source: FloatArray, available: Int, at: Int): Float {
        var sum = 0f
        for (offset in -2..2) sum += source[(at + offset).coerceIn(0, available - 1)]
        return sum
    }

    /** Moves the rows out by the music clock, storing the front row each time a whole row has rolled. */
    private fun rollRows(step: Float) {
        roll += step * (ROWS_CALM + ROWS_BUSY * energy)
        val whole = roll.toInt()
        if (whole > 0) {
            roll -= whole
            val count = min(whole, STORE)
            for (index in count downTo 1) {
                // Rows stored between two frames lie between the front row then and now.
                val share = 1f - (index - 1).toFloat() / count
                newest = (newest + 1) % STORE
                val at = newest * POINTS
                for (point in 0 until POINTS) store[at + point] = before[point] + (live[point] - before[point]) * share
                identity[newest] = captures++
                if (stored < STORE) stored++
            }
        }
        live.copyInto(before)
    }

    /** Where the front row stands highest, which is where spray leaves and a kick's white crest starts. */
    private fun findCrest() {
        var best = POINTS / 10
        for (point in POINTS / 10 until POINTS - POINTS / 10) {
            if (max(leftRow[point], rightRow[point]) > max(leftRow[best], rightRow[best])) best = point
        }
        crestX = best.toFloat() / (POINTS - 1)
        crestHeight = max(leftRow[best], rightRow[best])
    }

    private fun throwSpray(count: Int, motion: Float) {
        val aspect = kit.aspect
        val unit = min(aspect, 1f)
        val top = FRONT - unit * SWELL * waveHeight.value * crestHeight
        repeat(min(count, SPRAY_BURST_MOST)) {
            val angle = -1.5707964f + (random.next() - 0.5f) * 1.8f
            val speed = unit * (0.35f + 0.55f * random.next()) * (0.4f + 0.6f * motion)
            spray.spawn(
                atX = crestX + (random.next() - 0.5f) * SPRAY_SPREAD,
                atY = top - unit * 0.015f * random.next(),
                speedX = cos(angle) * speed / aspect,
                speedY = sin(angle) * speed,
                seconds = SPRAY_LIFE * (0.6f + 0.8f * random.next()),
                tintPosition = 0f,
                radius = 0.6f + 0.8f * random.next(),
            )
        }
    }

    /** The section, the breakdown and the drop: the moon, the mist and the wave. */
    private fun followStructure(state: VizRenderState, step: Float, own: Float) {
        val bar = gestures.cycleSeconds.coerceIn(WAVE_BAR_SHORTEST, WAVE_BAR_LONGEST)
        val motion = state.motionScale.coerceIn(0f, 1f)

        if (gestures.turn) {
            var target = 0.2f + 0.6f * random.next()
            if (abs(target - moonX) < 0.18f) target = if (moonX > 0.5f) target - 0.3f else target + 0.3f
            target = target.coerceIn(MOON_LEFT, MOON_RIGHT)
            moonToX = moonX + (target - moonX) * motion
            moonToRise = MOON_RISE_LOW + (MOON_RISE_HIGH - MOON_RISE_LOW) * random.next()
        }
        // An easing that starts at once and settles within the first bar.
        val glide = settle(step, bar / 4f)
        moonX += (moonToX - moonX) * glide
        moonRise += (moonToRise - moonRise) * glide

        if (gestures.cycles != cyclesSeen) {
            cyclesSeen = gestures.cycles
            if (breakdownHeld && ++breakdownCycles >= BREAKDOWN_CYCLES) breakdownHeld = false
        }
        if (gestures.breakdown) {
            breakdownHeld = true
            breakdownCycles = 0
        } else if (gestures.section || gestures.surge) {
            breakdownHeld = false
        }
        breakdown += ((if (breakdownHeld) 1f else 0f) - breakdown) * settle(own, BREAKDOWN_SECONDS)

        // The wave starts on the drop, or earlier when the queued audio already shows the drop coming.
        val upcoming = state.future?.nextEvent(AudioEventKind.Drop)
        if (upcoming != null && !waveOn) {
            val event = upcoming.event
            if (upcoming.secondsUntil in 0f..bar && event.detection.confidence >= FORESIGHT_CONFIDENCE &&
                !event.sameIdentity(anticipated) &&
                event.generation == state.frame.generation && event.analysisRevision == state.frame.analysisRevision) {
                anticipated = event
                startWave(bar)
            }
        }
        if (gestures.surge && !waveOn) startWave(bar)
        if (waveOn) {
            waveAt += step / waveBar
            foamCrest(own, motion)
            if (waveAt >= 1f) breakWave(motion)
        }
        waveFall *= exp(-own / WAVE_FALL_SECONDS)
        breakLight *= exp(-own / BREAK_SECONDS)
        blow += ((if (waveOn) 1f else 0f) - blow) * settle(own, if (waveOn) waveBar * 0.3f else BLOW_RETURN)
    }

    private fun startWave(bar: Float) {
        waveOn = true
        waveAt = 0f
        waveBar = bar
        foam = 0f
    }

    /** Foam blown off the wave's crest as it rolls in, thicker as it comes nearer. */
    private fun foamCrest(own: Float, motion: Float) {
        val slot = WAVE_START * (1f - waveAt)
        val near = nearness(slot)
        foam += own * FOAM_RATE * smoothstep(0f, WAVE_RISE, waveAt) * (0.3f + 0.7f * near) * (0.3f + 0.7f * motion)
        val aspect = kit.aspect
        val unit = min(aspect, 1f)
        val amplitude = unit * SWELL * waveHeight.value * RATIO.pow(slot * AMP_POWER)
        val top = HORIZON + (FRONT - HORIZON) * near - amplitude * WAVE_HEIGHT
        while (foam >= 1f) {
            foam -= 1f
            val angle = -1.5707964f + (random.next() - 0.5f) * 1.4f
            val speed = unit * (0.08f + 0.25f * random.next()) * (0.3f + 0.7f * near) * (0.4f + 0.6f * motion)
            spray.spawn(
                atX = random.next(),
                atY = top,
                speedX = cos(angle) * speed / aspect,
                speedY = sin(angle) * speed,
                seconds = SPRAY_LIFE * (0.4f + 0.5f * random.next()),
                tintPosition = 0f,
                radius = (0.35f + 0.9f * near) * (0.6f + 0.6f * random.next()),
            )
        }
    }

    /** The wave reaches the front row and breaks across the whole width in white spray. */
    private fun breakWave(motion: Float) {
        waveOn = false
        waveAt = 0f
        waveFall = 1f
        breakLight = 1f
        val aspect = kit.aspect
        val unit = min(aspect, 1f)
        val count = (SPRAY_BREAK * (0.3f + 0.7f * motion)).toInt()
        repeat(count) {
            val x = random.next()
            val point = (x * (POINTS - 1)).toInt()
            val top = FRONT - unit * SWELL * waveHeight.value * (WAVE_HEIGHT + live[point])
            val angle = -1.5707964f + (random.next() - 0.5f) * 2.2f
            val speed = unit * (0.3f + 0.8f * random.next()) * (0.4f + 0.6f * motion)
            spray.spawn(
                atX = x,
                atY = top + unit * 0.02f * random.next(),
                speedX = cos(angle) * speed / aspect,
                speedY = sin(angle) * speed,
                seconds = SPRAY_LIFE * (0.8f + 1.0f * random.next()),
                tintPosition = 0f,
                radius = 0.7f + 1.1f * random.next(),
            )
        }
    }

    /** Turns the row colours up to [LEAN_MOST] degrees towards the key, as sure as the key is. */
    private fun leanTowardsKey(keyHue: Float, confidence: Float, own: Float) {
        val sure = ((confidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = keyHue * 360f - (OWN_HORIZON_HUE + OWN_FRONT_HUE) * 0.5f
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        val target = towards.coerceIn(-LEAN_MOST, LEAN_MOST) * sure
        lean += (target - lean) * settle(own, KEY_SECONDS)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        // The moon's glow, the one soft light in the sky. A longer trail keeps it longer, at the same brightness.
        val keep = 1f - retentionOf(trail, state.deltaSeconds)
        val unit = size.minDimension
        val radius = unit * MOON_RADIUS
        val x = moonX * size.width
        val y = HORIZON * size.height - moonRise * unit
        val alpha = (HALO_FLOOR + HALO_BASS * glow) * state.lightScale * keep
        halo.clear()
        halo.glow(x, y, radius * 2.6f, withAlpha(haloArgb, alpha), sides = 32)
        halo.glow(x, y, radius * 7f, withAlpha(haloArgb, alpha * 0.5f), sides = 32)
        drawMesh(halo, BlendMode.Plus)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (size.width < 2f || size.height < 2f) return
        drawMoon(state)
        drawSea(state)
        drawMist(state)
        drawSpray(state)
    }

    /** This frame's colours: the drawing's own, or the palette's when one was chosen, read at full strength. */
    private fun paint(state: VizRenderState) {
        val palette = state.palette
        if (palette !== cachedPalette) {
            cachedPalette = palette
            lowHue = palette.low.toOklab().hue
            midHue = palette.mid.toOklab().hue
            highHue = palette.high.toOklab().hue
            capHue = palette.cap.toOklab().hue
        }
        // A palette that walks the whole circle names no colour of its own, so the sea keeps its swatches.
        val own = ((palette.hueSpan - 300f) / 60f).coerceIn(0f, 1f)
        horizonHue = mixHue(lowHue, OWN_HORIZON_HUE + lean, own)
        frontHue = mixHue(highHue, OWN_FRONT_HUE + lean, own)
        val glitterHue = mixHue(capHue, OWN_GLITTER_HUE, own)
        val mistHue = mixHue(midHue, OWN_MIST_HUE, own)
        // Squared, so a quieter passage reads clearly darker while a silence keeps the idle light.
        val level = ((lightFor(energy) - LIGHT_FLOOR) / (1f - LIGHT_FLOOR)).coerceIn(0f, 1f)
        lit = level * level
        light = (IDLE_LIGHT + (1f - IDLE_LIGHT) * lit) * state.lightScale
        glitterArgb = colourOf(GLITTER_L, min(GLITTER_C, limitOf(GLITTER_L, glitterHue)), glitterHue).toArgb()
        mistArgb = colourOf(MIST_L, min(MIST_C, limitOf(MIST_L, mistHue)), mistHue).toArgb()
        haloArgb = colourOf(HALO_L, min(HALO_C, limitOf(HALO_L, mistHue)), mistHue).toArgb()
        val rightHue = horizonHue
        val rightL = cuspOf(rightHue) + 0.03f
        rightArgb = scaled(colourOf(rightL, max(0f, limitOf(rightL, rightHue) - HEADROOM), rightHue).toArgb(), light)
        groundArgb = palette.background.toArgb()
    }

    /** The row colour [along] the way from the horizon (0) to the front (1), at the screen's strongest chroma. */
    private fun rowArgb(along: Float): Int {
        var turn = frontHue - horizonHue
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        val hue = horizonHue + turn * along
        val lightness = min(cuspOf(hue) - HORIZON_DEPTH * (1f - along), 0.5f + 0.5f * along)
        return colourOf(lightness, max(0f, limitOf(lightness, hue) - HEADROOM), hue).toArgb()
    }

    private fun DrawScope.drawMoon(state: VizRenderState) {
        val unit = size.minDimension
        val radius = unit * MOON_RADIUS
        val x = moonX * size.width
        val y = HORIZON * size.height - moonRise * unit
        val white = (MOON_LIGHT + (1f - MOON_LIGHT) * lit) * state.lightScale
        // The rim is a little darker than the middle, as on the real moon, so the disc reads as a
        // sphere rather than as a lamp; the soft grey seas give it the moon's face.
        drawCircle(Color(white * LIMB, white * LIMB, white * (LIMB + 0.03f)), radius, Offset(x, y))
        moonFace.clear()
        moonFace.glow(x, y, radius, grey(white, white, white, 1f), sides = 48)
        for (sea in MARIA_X.indices) {
            moonFace.glow(
                x + MARIA_X[sea] * radius, y + MARIA_Y[sea] * radius, MARIA_R[sea] * radius,
                grey(white * 0.7f, white * 0.72f, white * 0.78f, MARIA_ALPHA[sea]), sides = 24,
            )
        }
        drawMesh(moonFace)
    }

    /**
     * The rows, farthest first, each a sharp line over a band of the ground colour that reaches down to
     * the row in front, so every row hides what lies behind it and the sea reads as one surface.
     */
    private fun DrawScope.drawSea(state: VizRenderState) {
        val w = size.width
        val h = size.height
        val unit = size.minDimension
        val points = pointsFor(w)
        for (index in 0 until points) xs[index] = w * index / (points - 1)
        val count = orderRows(h)
        val stereoOn = stereo.value >= 0.5f
        sea.clear()
        rowY(orderRing[0], orderSlot[0], points, ysFar, whiteFar, stereoOn, w, h, unit)
        for (entry in 0 until count) {
            val slot = orderSlot[entry]
            val nearest = entry == count - 1
            if (nearest) {
                ysNear.fill(h + 2f, 0, points)
            } else {
                rowY(orderRing[entry + 1], orderSlot[entry + 1], points, ysNear, whiteNear, stereoOn, w, h, unit)
            }
            if (sea.vertexCount + points * 10 + GLITTER_VERTICES > sea.maxVertices) {
                drawMesh(sea)
                sea.clear()
            }
            addBand(ysFar, ysNear, points)
            val depth = (1f - slot / (ROWS - 1)).coerceIn(0f, 1f)
            val fade = (ROWS - slot).coerceIn(0f, 1f)
            val colour = scaled(rowArgb(depth.pow(COLOUR_CURVE)), light * fade)
            val white = scaled(WHITE, light * fade)
            val half = lineHalf(slot, unit)
            if (orderRing[entry] == LIVE && stereoOn) {
                addLine(ysRight, whiteRight, points, rightArgb, white, half)
                addLine(ysLeft, whiteLeft, points, colour, white, half)
            } else {
                addLine(ysFar, whiteFar, points, colour, white, half)
            }
            addGlitter(slot, orderRing[entry], points, fade, w, unit)
            val swapY = ysFar
            ysFar = ysNear
            ysNear = swapY
            val swapWhite = whiteFar
            whiteFar = whiteNear
            whiteNear = swapWhite
        }
        drawMesh(sea)
    }

    private fun pointsFor(width: Float): Int = (width / PIXELS_PER_POINT).toInt().coerceIn(MIN_POINTS, MAX_POINTS)

    /** The rows to draw into [orderRing] and [orderSlot], farthest first and the live row last, and how many. */
    private fun orderRows(height: Float): Int {
        val span = (FRONT - HORIZON) * height
        // Rows closer together than this merge into a smear, so a small picture keeps every second or third one.
        val farGap = span * (1f - FAR) * (1f - RATIO) / (1f - RATIO_LAST) * RATIO_LAST
        val stride = when {
            farGap >= MIN_GAP -> 1
            farGap * 2f >= MIN_GAP -> 2
            farGap * 3f >= MIN_GAP -> 3
            else -> 4
        }
        var count = 0
        for (k in stored downTo 1) {
            val ring = (newest - (k - 1) + STORE) % STORE
            if (identity[ring] % stride != 0) continue
            val slot = k - 1 + roll
            if (slot > ROWS) continue
            orderRing[count] = ring
            orderSlot[count] = slot
            count++
        }
        orderRing[count] = LIVE
        orderSlot[count] = 0f
        return count + 1
    }

    /**
     * How many rows show somewhere on a [width] by [height] picture of the sea as it stands: a row
     * shows where its line rises above every row in front of it.
     */
    internal fun visibleRows(width: Float, height: Float): Int {
        val points = pointsFor(width)
        val unit = min(width, height)
        val count = orderRows(height)
        val envelope = FloatArray(points) { Float.MAX_VALUE }
        val ys = FloatArray(MAX_POINTS)
        val white = FloatArray(MAX_POINTS)
        var shown = 0
        for (entry in count - 1 downTo 0) {
            rowY(orderRing[entry], orderSlot[entry], points, ys, white, stereo.value >= 0.5f, width, height, unit)
            var visible = false
            for (index in 0 until points) {
                if (ys[index] < envelope[index] - 1f) visible = true
                envelope[index] = min(envelope[index], ys[index])
            }
            if (visible) shown++
        }
        return shown
    }

    /**
     * The screen heights of one row into [ys], and how white each point of its line is into [white].
     * The live row with the stereo traces on answers the higher of its two traces, which its band hides under.
     */
    private fun rowY(
        ring: Int, slot: Float, points: Int, ys: FloatArray, white: FloatArray, stereoOn: Boolean,
        w: Float, h: Float, unit: Float,
    ) {
        if (ring == LIVE && stereoOn) {
            traceY(leftRow, 0, slot, points, ysLeft, whiteLeft, true, 0, w, h, unit)
            traceY(rightRow, 0, slot, points, ysRight, whiteRight, true, 0, w, h, unit)
            for (index in 0 until points) {
                ys[index] = min(ysLeft[index], ysRight[index])
                white[index] = max(whiteLeft[index], whiteRight[index])
            }
        } else if (ring == LIVE) {
            traceY(live, 0, slot, points, ys, white, true, 0, w, h, unit)
        } else {
            traceY(store, ring * POINTS, slot, points, ys, white, false, identity[ring], w, h, unit)
        }
    }

    private fun traceY(
        data: FloatArray,
        offset: Int,
        slot: Float,
        points: Int,
        ys: FloatArray,
        white: FloatArray,
        front: Boolean,
        seed: Int,
        w: Float,
        h: Float,
        unit: Float,
    ) {
        val near = nearness(slot)
        val amplitude = unit * SWELL * waveHeight.value * RATIO.pow(slot * AMP_POWER)
        val base = HORIZON * h + (FRONT - HORIZON) * h * near
        val repeats = 1f + (REPEAT_FAR - 1f) * (1f - near)
        val phase = if (front) 0f else hash01(seed) * (repeats - 1f) / (REPEAT_FAR - 1f)
        val flatten = 1f - FLATTEN * breakdown
        // Under reduced motion the wave is a sweep of white light rather than a wall.
        val wave = waveLift(slot) * motion
        val lift = kickLift * KICK_LIFT * unit * exp(-slot / KICK_DEPTH)
        val waveWhite = waveWhite(slot)
        val breakWhite = breakLight * (1f - slot / BREAK_ROWS).coerceIn(0f, 1f)
        for (index in 0 until points) {
            val across = index.toFloat() / (points - 1)
            var at = repeats * (across - 0.5f) + 0.5f + phase
            at -= kotlin.math.floor(at)
            val value = periodic(data, offset, at) * flatten
            val ridge = 1f + 0.12f * sin(TAU * (1.3f * across + 0.21f))
            ys[index] = base - amplitude * (value + wave * ridge) - lift
            var whiteness = crest(value)
            if (front) whiteness = max(whiteness, runnerWhite(across * w, w))
            white[index] = max(max(whiteness, waveWhite), breakWhite).coerceAtMost(1f)
        }
    }

    /** The wave's lift at [slot], in swell units: a wall at the wave's row, steep in front, gentle behind. */
    private fun waveLift(slot: Float): Float {
        val height = if (waveOn) WAVE_HEIGHT * smoothstep(0f, WAVE_RISE, waveAt) else 0f
        val fallen = WAVE_HEIGHT * waveFall
        var lift = 0f
        if (height > 0f) {
            val crest = WAVE_START * (1f - waveAt)
            val distance = slot - crest
            val spread = if (distance < 0f) WAVE_FRONT else WAVE_BACK
            lift = height * exp(-(distance / spread) * (distance / spread))
        }
        if (fallen > 0f) lift += fallen * exp(-slot / 2f)
        return lift
    }

    /** How white the wave makes the line at [slot]: its crest is white from the moment it appears far out. */
    private fun waveWhite(slot: Float): Float {
        if (!waveOn) return 0f
        val crest = WAVE_START * (1f - waveAt)
        val distance = (slot - crest) / WAVE_WHITE_ROWS
        return exp(-distance * distance)
    }

    private fun runnerWhite(x: Float, w: Float): Float {
        var most = 0f
        for (index in 0 until RUNNERS) {
            val age = runnerAge[index]
            if (age >= RUNNER_LIFE) continue
            val travelled = age * RUNNER_SPEED * w
            val origin = runnerX[index] * w
            val reach = RUNNER_HALF * w
            val nearest = min(abs(x - (origin + travelled)), abs(x - (origin - travelled)))
            val window = (1f - nearest / reach).coerceIn(0f, 1f)
            val fade = 1f - age / RUNNER_LIFE
            most = max(most, runnerStrength[index] * fade * window * window)
        }
        return most
    }

    private fun crest(value: Float): Float = smoothstep(CREST, CREST + CREST_SOFT, value)

    /** Share of the way from the horizon (0) to the front row (1) at which row [slot] lies. */
    private fun nearness(slot: Float): Float = FAR + (1f - FAR) * (RATIO.pow(slot) - RATIO_LAST) / (1f - RATIO_LAST)

    private fun lineHalf(slot: Float, unit: Float): Float {
        val scale = sqrt(unit.coerceAtLeast(1f) / REFERENCE_HEIGHT).coerceIn(0.6f, 1.6f)
        return LINE_PX * scale * (0.75f + 0.25f * RATIO.pow(slot * AMP_POWER)) * 0.5f
    }

    /** A band of the ground colour from one row's line down past the row in front of it. */
    private fun addBand(top: FloatArray, bottom: FloatArray, points: Int) {
        val first = sea.vertexCount
        for (index in 0 until points) {
            sea.vertex(xs[index], top[index], groundArgb)
            sea.vertex(xs[index], max(top[index], bottom[index]) + BAND_OVERLAP, groundArgb)
        }
        for (index in 0 until points - 1) {
            val a = first + index * 2
            sea.quad(a, a + 2, a + 3, a + 1)
        }
    }

    /** A sharp line through [ys], with a one pixel feather on each side so the edge is smooth without blur. */
    private fun addLine(ys: FloatArray, white: FloatArray, points: Int, colour: Int, bright: Int, half: Float) {
        val first = sea.vertexCount
        for (index in 0 until points) {
            val before = max(index - 1, 0)
            val after = min(index + 1, points - 1)
            val dx = xs[after] - xs[before]
            val dy = ys[after] - ys[before]
            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
            val nx = -dy / length
            val ny = dx / length
            val whiteness = white[index]
            val argb = blend(colour, bright, whiteness)
            val clear = argb and 0x00FFFFFF
            val thick = half * (1f + 0.6f * whiteness)
            val core = (thick - 0.5f).coerceAtLeast(0f)
            val edge = thick + 0.5f
            val x = xs[index]
            val y = ys[index]
            sea.vertex(x + nx * edge, y + ny * edge, clear)
            sea.vertex(x + nx * core, y + ny * core, argb)
            sea.vertex(x - nx * core, y - ny * core, argb)
            sea.vertex(x - nx * edge, y - ny * edge, clear)
        }
        for (index in 0 until points - 1) {
            val a = first + index * 4
            val b = a + 4
            sea.quad(a, b, b + 1, a + 1)
            sea.quad(a + 1, b + 1, b + 2, a + 2)
            sea.quad(a + 2, b + 2, b + 3, a + 3)
        }
    }

    /**
     * The moon's path on the water: glints on this row inside a column under the moon, narrow at the
     * horizon and wider in front, wider still for a wide stereo mix. The hats light more of them.
     */
    private fun addGlitter(slot: Float, ring: Int, points: Int, fade: Float, w: Float, unit: Float) {
        val near = nearness(slot)
        val spread = sqrt(width.coerceIn(0f, 1f))
        val column = unit * MOON_RADIUS * (0.8f + 1.2f * spread) +
            (w * (COLUMN_MONO + COLUMN_WIDE * spread) - unit * MOON_RADIUS * (0.8f + 1.2f * spread)) * near
        val centre = moonX * w
        val density = GLITTER_FLOOR + (1f - GLITTER_FLOOR) * min(1f, hats)
        val seed = if (ring == LIVE) -7 else identity[ring]
        val candidates = ((GLITTER_BASE + GLITTER_WIDE * (column / (w * (COLUMN_MONO + COLUMN_WIDE)))) *
            (0.4f + 0.8f * density)).toInt()
        val ys = ysFar
        for (index in 0 until candidates) {
            val key = seed * 31 + index * 7919
            val side = hash01(key) * 2f - 1f
            val offset = if (side < 0f) -(-side).pow(1.4f) else side.pow(1.4f)
            val x = centre + offset * column
            if (x < 1f || x > w - 1f) continue
            val rate = 0.6f + 1.1f * hash01(key + 1)
            val phase = hash01(key + 2)
            val wave = 0.5f + 0.5f * sin(TAU * (shimmer * rate + phase))
            val twinkle = wave * wave
            val strength = twinkle * density * (1f - 0.55f * abs(offset)) * fade
            if (strength < GLITTER_CUT) continue
            val at = x / w * (points - 1)
            val low = at.toInt().coerceIn(0, points - 2)
            val y = ys[low] + (ys[low + 1] - ys[low]) * (at - low) - 0.5f
            val length = (2f + 7f * near) * (unit / REFERENCE_HEIGHT).coerceIn(0.6f, 2f)
            // A glint is either there or not; the twinkle mostly decides whether, and a little how bright.
            val argb = scaled(glitterArgb, max(light, GLITTER_LIGHT) * (0.55f + 0.45f * min(1f, strength * 2f)))
            addGlint(x, y, length, argb)
            if (strength > GLINT_GLOW) sea.glow(x, y, length * 1.1f, withAlpha(argb, 0.45f * strength), sides = 6)
        }
    }

    private fun addGlint(x: Float, y: Float, length: Float, argb: Int) {
        if (sea.vertexCount + 5 > sea.maxVertices) return
        val clear = argb and 0x00FFFFFF
        val middle = sea.vertex(x, y, argb)
        val left = sea.vertex(x - length, y, clear)
        val above = sea.vertex(x, y - 1.3f, clear)
        val right = sea.vertex(x + length, y, clear)
        val below = sea.vertex(x, y + 1.3f, clear)
        sea.triangle(middle, left, above)
        sea.triangle(middle, above, right)
        sea.triangle(middle, right, below)
        sea.triangle(middle, below, left)
    }

    /**
     * The mist: two banks of pale light. The far one lies on the horizon with a ragged top where the
     * fog piles up; a thinner one breaks into wisps lower on the sea and drifts the other way. Both
     * glow brighter under the moon. The far bank rolls over the far half of the sea in a breakdown,
     * both blow off on the drop, and they thin in a silence.
     */
    private fun DrawScope.drawMist(state: VizRenderState) {
        val w = size.width
        val h = size.height
        val horizon = HORIZON * h
        val lifted = blow * MIST_BLOW * h
        val farHalf = HORIZON * h + (FRONT - HORIZON) * h * nearness(ROWS * 0.5f)
        val strength = ((MIST_IDLE + (MIST_PLAYING - MIST_IDLE) * awake * (0.5f + 0.5f * lit) + MIST_BREAKDOWN * breakdown) *
            mistAmount.value * (1f - 0.85f * blow)).coerceIn(0f, MIST_MOST) * state.lightScale
        if (strength <= 0.002f) return
        val moon = moonX * w
        mist.clear()
        addBank(
            w, horizon - lifted, h * MIST_UP * (0.7f + 0.3f * awake),
            h * MIST_DOWN + breakdown * (farHalf - horizon - h * MIST_DOWN), h * MIST_TAIL,
            strength, drift, broken = false, moon,
        )
        val nearY = horizon + (FRONT - HORIZON) * h * NEAR_BANK - lifted * 0.5f
        addBank(w, nearY, h * NEAR_UP, h * NEAR_DOWN, h * NEAR_TAIL, strength * NEAR_SHARE, -0.7f * drift + 0.37f, broken = true, moon)
        drawMesh(mist, BlendMode.Plus)
    }

    /** One bank of mist across the width: its top is ragged and its light is patchy, and brighter under the moon. */
    private fun addBank(
        w: Float, base: Float, up: Float, down: Float, tail: Float,
        strength: Float, phase: Float, broken: Boolean, moon: Float,
    ) {
        val start = mist.vertexCount
        for (column in 0..MIST_COLUMNS) {
            val across = column.toFloat() / MIST_COLUMNS
            val x = across * w
            val body = 0.5f + 0.5f * (0.5f * sin(TAU * (2.3f * across + phase)) +
                0.3f * sin(TAU * (5.1f * across - 1.3f * phase) + 1.7f) +
                0.2f * sin(TAU * (9.7f * across + 0.6f * phase) + 4.1f))
            // A broken bank thins to nothing between its patches, so it reads as wisps.
            val patch = if (broken) smoothstep(0.35f, 0.75f, body) else 0.3f + 0.7f * body
            val off = (x - moon) / (MOON_SPREAD * w)
            val moonlit = 1f + MOONLIT * exp(-off * off)
            val alpha = (strength * patch * moonlit).coerceAtMost(MIST_MOST)
            mist.vertex(x, base - up * (0.45f + 0.55f * body), withAlpha(mistArgb, 0f))
            mist.vertex(x, base, withAlpha(mistArgb, alpha))
            mist.vertex(x, base + down, withAlpha(mistArgb, alpha * MIST_LOW))
            mist.vertex(x, base + down + tail, withAlpha(mistArgb, 0f))
        }
        for (column in 0 until MIST_COLUMNS) {
            val a = start + column * 4
            val b = a + 4
            mist.quad(a, b, b + 1, a + 1)
            mist.quad(a + 1, b + 1, b + 2, a + 2)
            mist.quad(a + 2, b + 2, b + 3, a + 3)
        }
    }

    /** Spray off the crests: small white drops that rise, fall and fade. */
    private fun DrawScope.drawSpray(state: VizRenderState) {
        drops.clear()
        val w = size.width
        val h = size.height
        val unit = size.minDimension
        val bright = (SPRAY_LIGHT + (1f - SPRAY_LIGHT) * lit) * state.lightScale
        val scale = (unit / REFERENCE_HEIGHT).coerceIn(0.6f, 2f)
        for (slot in 0 until spray.capacity) {
            val left = spray.remaining(slot)
            if (left <= 0f) continue
            val alpha = (left * 2.5f).coerceAtMost(1f)
            addDrop(spray.x[slot] * w, spray.y[slot] * h, 1.6f * scale * spray.size[slot] + 0.7f, scaled(withAlpha(WHITE, alpha), bright))
        }
        drawMesh(drops)
    }

    /** One drop of spray: a solid round core with a thin soft edge. */
    private fun addDrop(x: Float, y: Float, radius: Float, argb: Int) {
        if (drops.vertexCount + DROP_SIDES * 2 + 1 > drops.maxVertices) return
        val clear = argb and 0x00FFFFFF
        val middle = drops.vertex(x, y, argb)
        val first = drops.vertexCount
        for (side in 0 until DROP_SIDES) {
            val angle = TAU * side / DROP_SIDES
            val c = cos(angle)
            val s = sin(angle)
            drops.vertex(x + c * radius, y + s * radius, argb)
            drops.vertex(x + c * (radius + 0.8f), y + s * (radius + 0.8f), clear)
        }
        for (side in 0 until DROP_SIDES) {
            val a = first + side * 2
            val b = first + ((side + 1) % DROP_SIDES) * 2
            drops.triangle(middle, a, b)
            drops.quad(a, b, b + 1, a + 1)
        }
    }

    override fun onReset() {
        calmSea()
        mood = 0.5f
        bass = 0f
        glow = 0f
        energy = 0f
        width = 0f
        awake = 0f
        hats = 0f
        lean = 0f
        kickLift = 0f
        runnerAge.fill(RUNNER_LIFE)
        nextRunner = 0
        crestX = 0.5f
        crestHeight = 0f
        breakdown = 0f
        breakdownHeld = false
        breakdownCycles = 0
        cyclesSeen = 0
        waveOn = false
        waveAt = 0f
        waveBar = 2f
        waveFall = 0f
        breakLight = 0f
        blow = 0f
        foam = 0f
        anticipated = null
        moonX = MOON_X
        moonRise = MOON_RISE
        moonToX = MOON_X
        moonToRise = MOON_RISE
        shimmer = 0f
        drift = 0f
        spray.clear()
        cachedPalette = null
    }

    /** The sea before any music: a calm, low swell in every row, so the picture is whole from its first frame. */
    private fun calmSea() {
        for (row in 0 until STORE) {
            val first = hash01(row * 13 + 5) * TAU
            val second = hash01(row * 17 + 3) * TAU
            val at = row * POINTS
            for (point in 0 until POINTS) {
                val along = point.toFloat() / (POINTS - 1)
                store[at + point] = CALM_SWELL * (sin(TAU * 2f * along + first) - sin(first)) +
                    CALM_SWELL * 0.35f * (sin(TAU * 5f * along + second) - sin(second))
            }
            identity[row] = row - STORE
        }
        newest = STORE - 1
        stored = STORE
        captures = 0
        roll = 0f
        for (point in 0 until POINTS) {
            val along = point.toFloat() / (POINTS - 1)
            live[point] = CALM_SWELL * sin(TAU * 2f * along)
        }
        live.copyInto(before)
        live.copyInto(leftRow)
        live.copyInto(rightRow)
        // The first stored rows start as the front row does, so none of them jumps at the first capture.
        for (row in 0 until 2) live.copyInto(store, ((newest - row + STORE) % STORE) * POINTS)
    }

    private companion object {
        const val LIVE = -1

        // The frame. Heights are shares of the picture's height.
        const val HORIZON = 0.38f
        const val FRONT = 0.9f
        const val ROWS = 80
        const val STORE = 96
        const val POINTS = 48
        /** Each row is this much closer to the one behind it than to the one in front. */
        const val RATIO = 0.988f
        val RATIO_LAST: Float = RATIO.pow(ROWS - 1)
        /** How far above the horizon the last row lies, as a share of the way to the front row. */
        const val FAR = 0.012f
        /** Swell in front, as a share of the picture's shorter side. */
        const val SWELL = 0.13f
        /** How fast the swell shrinks with distance, against how fast the rows close up. */
        const val AMP_POWER = 1.35f
        /** How many times the farthest row repeats its waveform across the width. */
        const val REPEAT_FAR = 3.2f
        const val LINE_PX = 1.5f
        const val REFERENCE_HEIGHT = 540f
        const val MIN_GAP = 1.5f
        const val PIXELS_PER_POINT = 8f
        const val MIN_POINTS = 48
        const val MAX_POINTS = 128
        const val BAND_OVERLAP = 1.2f
        const val MESH_VERTICES = 32_000
        const val GLITTER_VERTICES = 600

        // Reading the waveform.
        const val SCOPE_USE = 256
        const val MIN_SCOPE = 64
        const val TRIGGER_SEARCH = 32
        const val LONG_SWELL = 96f
        const val SHORT_CHOP = 224f
        const val SMOOTH_CALM = 2.6f
        const val SMOOTH_LIVELY = 0.85f
        const val GAUSS_REACH = 3f
        /** The waveform under the shared trace gain, through a soft limit: its median lands near half a swell. */
        const val COMPRESS = 2.2f
        const val SWELL_FLOOR = 0.55f
        const val SWELL_BASS = 0.9f
        const val LIVE_SECONDS = 0.07f
        const val CALM_SWELL = 0.22f
        /** Rows stored a second, from a quiet passage to a loud one. */
        const val ROWS_CALM = 7f
        const val ROWS_BUSY = 23f

        // The light.
        const val LIGHT_FLOOR = 0.06f
        /** How much light the sea keeps in a silence and on the first frame. */
        const val IDLE_LIGHT = 0.35f
        const val MOON_LIGHT = 0.85f
        const val SPRAY_LIGHT = 0.55f
        const val LIGHT_RISE = 0.35f
        const val LIGHT_FALL = 1f
        const val BASS_RISE = 0.04f
        const val BASS_FALL = 0.2f
        const val GLOW_RISE = 0.12f
        const val GLOW_FALL = 0.5f
        const val HALO_FLOOR = 0.08f
        const val HALO_BASS = 0.5f
        const val AWAKE_SECONDS = 0.8f
        const val MOOD_SECONDS = 1.5f
        const val WIDTH_SECONDS = 0.6f

        // Colours: the drawing's own swatches, and the rules for reading any hue at full strength.
        const val OWN_HORIZON_HUE = 262f
        const val OWN_FRONT_HUE = 205f
        const val OWN_GLITTER_HUE = 88f
        const val OWN_MIST_HUE = 230f
        const val HORIZON_DEPTH = 0.095f
        const val HEADROOM = 0.012f
        const val COLOUR_CURVE = 1.2f
        const val GLITTER_L = 0.93f
        const val GLITTER_C = 0.08f
        const val MIST_L = 0.8f
        const val MIST_C = 0.06f
        const val HALO_L = 0.92f
        const val HALO_C = 0.05f
        const val LEAN_MOST = 25f
        const val KEY_SECONDS = 2f
        const val WHITE = -1
        const val CREST = 0.85f
        const val CREST_SOFT = 0.25f

        // The moon, in shares of the shorter side above the horizon, and of the width across.
        const val MOON_RADIUS = 0.048f
        /** How light the moon's rim is against its middle. */
        const val LIMB = 0.8f
        // The moon's seas: centres and radii in moon radii, and how dark each one is.
        val MARIA_X = floatArrayOf(-0.3f, 0.32f, -0.05f, 0.18f)
        val MARIA_Y = floatArrayOf(-0.25f, 0.05f, 0.42f, -0.42f)
        val MARIA_R = floatArrayOf(0.4f, 0.3f, 0.24f, 0.16f)
        val MARIA_ALPHA = floatArrayOf(0.55f, 0.5f, 0.45f, 0.35f)
        const val MOON_X = 0.64f
        const val MOON_RISE = 0.11f
        const val MOON_RISE_LOW = 0.085f
        const val MOON_RISE_HIGH = 0.15f
        const val MOON_LEFT = 0.2f
        const val MOON_RIGHT = 0.8f
        const val GLIDE_SECONDS = 0.6f

        // The glitter.
        const val COLUMN_MONO = 0.05f
        const val COLUMN_WIDE = 0.3f
        /** Glints a row can hold: a few in any column, and more the wider the column, so a wide one is as dense. */
        const val GLITTER_BASE = 3f
        const val GLITTER_WIDE = 30f
        const val GLITTER_FLOOR = 0.35f
        const val GLITTER_CUT = 0.06f
        const val GLINT_GLOW = 0.4f
        /** The glitter is moonlight, so it keeps most of its light when the music is quiet. */
        const val GLITTER_LIGHT = 0.7f
        const val HAT_SECONDS = 0.45f
        const val HAT_JUMP = 0.8f
        const val HAT_MOST = 1.4f
        const val SHIMMER_IDLE = 0.25f
        const val SHIMMER_PLAYING = 1f
        const val SHIMMER_HATS = 1.2f

        // The kick: the front rows lift, and a white crest runs out both ways along the front row.
        const val KICK_SECONDS = 0.2f
        const val KICK_JUMP = 0.6f
        const val KICK_LIFT = 0.045f
        const val KICK_DEPTH = 2.5f
        const val RUNNERS = 4
        const val RUNNER_LIFE = 0.45f
        const val RUNNER_SPEED = 1.1f
        const val RUNNER_HALF = 0.05f

        // Spray, in shares of the height a second.
        const val SPRAY_CAPACITY = 220
        const val DROP_SIDES = 6
        const val SPRAY_SNARE = 20
        /** How wide a stretch of the crest a snare's spray leaves from, as a share of the width. */
        const val SPRAY_SPREAD = 0.09f
        const val SPRAY_BURST_MOST = 28
        const val SPRAY_BREAK = 140
        const val SPRAY_LIFE = 0.9f
        const val SPRAY_GRAVITY = 1.6f
        const val SPRAY_DRAG = 0.6f

        // The mist.
        const val MIST_COLUMNS = 64
        /** The near bank of wisps: where it lies down the sea, its height, and its share of the far bank's light. */
        const val NEAR_BANK = 0.3f
        const val NEAR_UP = 0.035f
        const val NEAR_DOWN = 0.02f
        const val NEAR_TAIL = 0.03f
        const val NEAR_SHARE = 0.75f
        /** How much brighter the mist is under the moon, and how wide that brighter stretch is. */
        const val MOONLIT = 1.3f
        const val MOON_SPREAD = 0.16f
        const val MIST_UP = 0.09f
        const val MIST_DOWN = 0.05f
        const val MIST_TAIL = 0.05f
        const val MIST_LOW = 0.45f
        const val MIST_IDLE = 0.12f
        const val MIST_PLAYING = 0.22f
        const val MIST_BREAKDOWN = 0.06f
        /** Additive mist is never more than a quarter of its colour. */
        const val MIST_MOST = 0.25f
        const val MIST_BLOW = 0.12f
        const val MIST_DRIFT = 0.05f
        const val BLOW_RETURN = 3f

        // The breakdown.
        const val FLATTEN = 0.55f
        const val BREAKDOWN_SECONDS = 0.2f
        const val BREAKDOWN_CYCLES = 16

        // The wave.
        const val WAVE_SECONDS = 2f
        /** The support a queued drop needs before the wave starts early for it. */
        const val FORESIGHT_CONFIDENCE = 0.6f
        const val WAVE_BAR_SHORTEST = 1.2f
        const val WAVE_BAR_LONGEST = 4.5f
        const val WAVE_START = 58f
        const val WAVE_HEIGHT = 2.4f
        const val WAVE_RISE = 0.2f
        const val WAVE_FRONT = 2.6f
        const val WAVE_BACK = 3.2f
        const val WAVE_WHITE_ROWS = 1.3f
        const val WAVE_FALL_SECONDS = 0.18f
        const val BREAK_SECONDS = 0.35f
        const val BREAK_ROWS = 4f
        /** Foam drops a second off the rolling wave's crest, when it is near. */
        const val FOAM_RATE = 90f

        /** The finishing pass: only the hottest parts, the moon, the white crests and the spray, glow. */
        val FINISH = PostSpec(bloom = 0.45f, bloomRadius = 0.025f, threshold = 0.8f, vignette = 0.2f, grain = 0f, glitch = false, aberration = 0f)

        private val GAUSS = FloatArray(GAUSS_STEPS + 1) { step ->
            val d = step.toFloat() / GAUSS_STEPS * GAUSS_REACH
            exp(-0.5f * d * d)
        }

        fun gaussian(d: Float): Float {
            val at = abs(d) / GAUSS_REACH * GAUSS_STEPS
            return if (at >= GAUSS_STEPS) 0f else GAUSS[at.toInt()]
        }

        const val GAUSS_STEPS = 256

        /**
         * The lightness at which [hue] is most colourful.
         *
         * The shared cusp table also accepts a large chroma right next to black, where its gamut
         * tolerance is bigger than the colour itself, and so puts the cusp of cyan and teal at black.
         * This one searches only from [CUSP_FLOOR] up, where that tolerance does not matter.
         */
        fun cuspOf(hue: Float): Float {
            var h = hue % 360f
            if (h < 0f) h += 360f
            val low = h.toInt().coerceIn(0, 359)
            val share = h - low
            return CUSPS[low] * (1f - share) + CUSPS[(low + 1) % 360] * share
        }

        private val CUSPS: FloatArray by lazy { FloatArray(360) { searchCusp(it.toFloat()) } }

        /** The chroma limit rises to one peak and falls again with lightness, so a golden-section search finds it. */
        private fun searchCusp(hue: Float): Float {
            var a = CUSP_FLOOR
            var b = 0.995f
            var c = b - GOLDEN * (b - a)
            var d = a + GOLDEN * (b - a)
            var atC = limitOf(c, hue)
            var atD = limitOf(d, hue)
            repeat(CUSP_STEPS) {
                if (atC > atD) {
                    b = d; d = c; atD = atC
                    c = b - GOLDEN * (b - a); atC = limitOf(c, hue)
                } else {
                    a = c; c = d; atC = atD
                    d = a + GOLDEN * (b - a); atD = limitOf(d, hue)
                }
            }
            return (a + b) * 0.5f
        }

        /** The most chroma a screen shows at [lightness] and [hue], found by halving. */
        fun limitOf(lightness: Float, hue: Float): Float {
            var lower = 0f
            var upper = 0.4f
            repeat(16) {
                val middle = (lower + upper) * 0.5f
                if (inGamut(lightness, middle, hue)) lower = middle else upper = middle
            }
            return lower
        }

        const val CUSP_FLOOR = 0.3f
        const val CUSP_STEPS = 24
        const val GOLDEN = 0.618034f

        fun settle(dt: Float, seconds: Float): Float = if (dt <= 0f) 0f else 1f - exp(-dt / seconds.coerceAtLeast(1e-3f))

        fun smoothstep(from: Float, to: Float, value: Float): Float {
            val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** A value between 0 and 1 that stays the same for the same [value]. */
        fun hash01(value: Int): Float {
            var x = value * -0x61c88647
            x = x xor (x ushr 15)
            x *= 0x2c1b3c6d
            x = x xor (x ushr 12)
            x *= 0x297a2d39
            x = x xor (x ushr 15)
            return (x ushr 8) / 16_777_216f
        }

        /** The row at [at], 0 to 1, read smoothly between its points and wrapping round, since both ends are zero. */
        fun periodic(data: FloatArray, offset: Int, at: Float): Float {
            val position = at * (POINTS - 1)
            val index = position.toInt().coerceIn(0, POINTS - 2)
            val t = position - index
            val p0 = data[offset + wrapIndex(index - 1)]
            val p1 = data[offset + index]
            val p2 = data[offset + wrapIndex(index + 1)]
            val p3 = data[offset + wrapIndex(index + 2)]
            // Catmull-Rom through the four neighbours.
            return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
        }

        fun wrapIndex(index: Int): Int {
            val period = POINTS - 1
            return ((index % period) + period) % period
        }

        fun blendInto(target: FloatArray, source: FloatArray, share: Float) {
            if (share <= 0f) return
            for (index in target.indices) target[index] += (source[index] - target[index]) * share
        }

        /** The hue [share] of the way from [from] to [to], the short way round. */
        fun mixHue(from: Float, to: Float, share: Float): Float {
            var turn = to - from
            while (turn > 180f) turn -= 360f
            while (turn < -180f) turn += 360f
            return from + turn * share
        }

        /** An opaque-channel colour from three shares of white and an alpha. */
        fun grey(red: Float, green: Float, blue: Float, alpha: Float): Int =
            ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or
                ((red.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
                ((green.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or
                (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        fun withAlpha(argb: Int, alpha: Float): Int =
            ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (argb and 0x00FFFFFF)

        /** [argb] with its colour scaled towards black by [factor], keeping its alpha. */
        fun scaled(argb: Int, factor: Float): Int {
            val f = factor.coerceIn(0f, 1f)
            val r = ((argb shr 16 and 0xFF) * f).toInt()
            val g = ((argb shr 8 and 0xFF) * f).toInt()
            val b = ((argb and 0xFF) * f).toInt()
            return (argb and -0x1000000) or (r shl 16) or (g shl 8) or b
        }

        /** The colour [share] of the way from [from] to [to], channel by channel. */
        fun blend(from: Int, to: Int, share: Float): Int {
            if (share <= 0f) return from
            if (share >= 1f) return to
            val a = mixChannel(from ushr 24, to ushr 24, share)
            val r = mixChannel(from shr 16 and 0xFF, to shr 16 and 0xFF, share)
            val g = mixChannel(from shr 8 and 0xFF, to shr 8 and 0xFF, share)
            val b = mixChannel(from and 0xFF, to and 0xFF, share)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun mixChannel(from: Int, to: Int, share: Float): Int = (from + (to - from) * share + 0.5f).toInt()
    }
}
