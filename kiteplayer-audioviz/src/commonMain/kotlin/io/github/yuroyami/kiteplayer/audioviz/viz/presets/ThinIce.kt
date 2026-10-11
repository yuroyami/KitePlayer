package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.field.FieldShader
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.LaceReaction
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.canDrawRuntimeShaders
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Impulses
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A black frozen lake seen from above, with two wells under the ice that send out rings.
 *
 * The lake is a map of the mix. Across the screen is the stereo balance, and the long side runs from
 * bass to treble, from the left of a landscape screen and from the bottom of a portrait one. A kick
 * rings at the bass end, a snare in the middle and a hat at the treble end, and the two wells slew
 * to the two loudest quarters of the bands. A kick sends a cyan ring and a snare an orchid ring.
 * Each ring is the waveform at the moment it was born, bent into a circle and lit round its circle
 * by the spectrum, and it cools to deep blue or deep violet as it spreads. Neighbouring rings sit one
 * beat apart, so a steady kick draws even rings. Where a cyan ring crosses an orchid ring, the
 * crossing points flare white, and a hat makes them glint. The ice shows only as white hairline
 * cracks: an accented kick adds one, cracks creep out of the wells in the last beat before a drop,
 * and a breakdown heals them. On the
 * drop the ice breaks along the rings and along straight lines out of the wells, and the pieces carry
 * the picture away, shrinking into the water over one bar, while new rings start on fresh water.
 *
 * Hats lay snow into a drift, a memory field that fades over eight heard seconds, and the snow that
 * falls on a young ring front settles along it. The forms are Lake, Frost (a breakdown: lace grows inside the rings),
 * Shatter (a drop: the break) and Underwater (the rings as caustic light and bubbles from hats, until
 * the next section or eight cycles). A morph turns the glide and swaps the wells. The camera glides
 * over the lake on the slow cycle and dives over a cycle after a drop; it never cuts.
 */
internal class ThinIce : Layered(
    name = "Thin Ice",
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 170L,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 170),
    ),
) {

    // The kit's camera holds still; the drawing glides and dives its own view, and never on a hit.
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

    // No general glow: the only glow is drawn here, on the crossing points and the wells.
    override val post: PostSpec get() = PostSpec.Off

    override val mapping: VizMapping by mappingOf(
        // The waveform is the fine bend of each ring, and of the small ring each well trembles with.
        // The spectrum lights every ring round its circle.
        VizDrive(VizDriver.Waveform, VizProperty.Texture),
        VizDrive(VizDriver.Bands, VizProperty.Brightness),
        // The lake is a map of the mix: the stereo balance moves the wells and the hits across, and
        // the wells sit on the two loudest quarters of the bands.
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        // A kick rings low on the long side, a snare in the middle and a hat high, as thick as the hit is hard.
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(8f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(8f)),
        // A hat glints on the crossings, and rings well B in a song that has no snare.
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.3f)),
        // In a song with no kick, onsets ring well A.
        VizDrive(VizDriver.Onset, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(8f)),
        // Rings spread one ring spacing a beat.
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        // A section rings both wells once.
        VizDrive(VizDriver.Section, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(8f)),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.Breakdown, VizProperty.Brightness, VizCurve.Discrete, VizResponse.envelope(2f)),
        // A breakdown is Frost, a drop breaks the ice and goes under it, and a section freezes a new lake.
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.lifetime(2f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
        silence = VizSilence.Still,
    )

    // The wells, along the screen's long side from its bass end and across it, in shares of the
    // screen. Each one slews toward the place of a loud band group and the stereo balance.
    private val wellAlong = floatArrayOf(START_ALONG, 1f - START_ALONG)
    private val wellAcross = floatArrayOf(START_ACROSS, 1f - START_ACROSS)
    private val alongSlew = Array(WELLS) { Slew(WELL_RATE, wellAlong[it]) }
    private val acrossSlew = Array(WELLS) { Slew(WELL_RATE, wellAcross[it]) }
    private val wellFlash = FloatArray(WELLS)
    /** The well that sits on the loudest band group; the other sits on the second loudest. */
    private var leader = 0
    private var moves = 0

    // The mix: the stereo balance, -1 all left to 1 all right, and the level of each quarter of the bands.
    private val balanceSlew = Slew(BALANCE_RATE)
    private val groupLevel = FloatArray(GROUPS)
    private var lastSpawned = -1

    // Rings: well A's slots first, then well B's. Centres are shares of the screen, sizes are in
    // shorter sides of the screen.
    private val alive = BooleanArray(SLOTS)
    private val centreX = FloatArray(SLOTS)
    private val centreY = FloatArray(SLOTS)
    private val radius = FloatArray(SLOTS)
    private val thickness = FloatArray(SLOTS)
    private val swing = FloatArray(SLOTS)
    private val strength = FloatArray(SLOTS)
    private val profile = FloatArray(SLOTS * PROFILE)
    private val shade = FloatArray(SLOTS * SHADES)
    // Music seconds since each ring was born, and how far it has spread since its first splash.
    private val age = FloatArray(SLOTS)
    private val travelled = FloatArray(SLOTS)

    // The sound as it is now: the waveform the wells tremble with, and the spectrum's light, 0 to 1.
    private val liveProfile = FloatArray(PROFILE)
    private val liveLevel = FloatArray(SHADES)
    private val liveShade = FloatArray(SHADES)

    // Hairline cracks, in shorter sides from the top left, fixed in the ice.
    private val crackX = FloatArray(CRACKS * CRACK_POINTS)
    private val crackY = FloatArray(CRACKS * CRACK_POINTS)
    private val crackLength = IntArray(CRACKS)
    private val crackGrow = FloatArray(CRACKS)
    private val crackAlpha = FloatArray(CRACKS)
    private val crackCreeping = BooleanArray(CRACKS)
    private var kickCracks = 0
    private var creepArmed = false

    // The music, as this drawing reads it.
    private var sinceKick = LONG_AGO
    private var sinceSnare = LONG_AGO
    private var sinceFallbackA = LONG_AGO
    private var sinceFallbackB = LONG_AGO
    private var kickAverage = 0.6f
    private var breakdown = false
    private var calm = 0f
    private var glint = 0f
    private var hats = 0
    private var light = IDLE_LIGHT

    /** The share of light the flash guard allows. The floors below sit under it, so they follow it too. */
    private var allowed = 1f
    private val keyTurn = Slew(maxPerSecond = 20f)

    // The break: a copy of the picture, the pieces cut from it, and how long ago it broke.
    private var snapshot: ImageBitmap? = null
    private val snapshotScope = CanvasDrawScope()
    private var breakPending = false
    private var breakAge = -1f
    private var breakBar = 2f
    private var pieceCount = 0
    private val pieceInner = FloatArray(PIECES)
    private val pieceOuter = FloatArray(PIECES)
    private val pieceFrom = FloatArray(PIECES)
    private val pieceTo = FloatArray(PIECES)
    private val pieceDelay = FloatArray(PIECES)
    private var pieceWellX = 0f
    private var pieceWellY = 0f

    // Colours: each well's ring from where it is born to where it has spread, as red, green and blue.
    private val hot = Array(WELLS) { FloatArray(3) }
    private val cool = Array(WELLS) { FloatArray(3) }
    private var colourFor: VizPalette? = null
    private var colourTurn = Float.NaN

    private val cosTable = FloatArray(POINTS) { cos(TAU * it / POINTS) }
    private val sinTable = FloatArray(POINTS) { sin(TAU * it / POINTS) }
    private val rings = TriangleMesh(maxVertices = (SLOTS + WELLS) * POINTS * 2 + 64)
    private val marks = TriangleMesh(maxVertices = 24_000)
    private val glows = TriangleMesh(maxVertices = 12_000)
    private val piece = Path()

    // The screen in shorter sides, as last drawn.
    private var screenW = 16f / 9f
    private var screenH = 1f

    init {
        seedRings()
    }

    /** The radii of well [well]'s living rings, smallest first, in shorter sides. For tests. */
    internal fun radiiOf(well: Int): List<Float> =
        (well * PER_WELL until (well + 1) * PER_WELL).filter { alive[it] }.map { radius[it] }.sorted()

    /** Hairline cracks in the ice now. For tests. */
    internal val cracks: Int get() = (0 until CRACKS).count { crackAlpha[it] > 0f }

    /** Where the first point of each live crack is, in shorter sides from the top left, for tests. */
    internal fun crackStarts(): List<Pair<Float, Float>> =
        (0 until CRACKS).filter { crackAlpha[it] > 0f }.map { crackX[it * CRACK_POINTS] to crackY[it * CRACK_POINTS] }

    /** Where well [well] is, in shares of the screen, for tests. */
    internal fun wellAt(well: Int): Pair<Float, Float> = wellX(well) to wellY(well)

    /** The centres of the live rings of [well], in shares of the screen, for tests. */
    internal fun ringCentresOf(well: Int): List<Pair<Float, Float>> =
        (0 until PER_WELL).map { well * PER_WELL + it }.filter { alive[it] }.map { centreX[it] to centreY[it] }

    /** The centre of ring [slot], in shares of the screen's width and height, for tests. */
    internal fun ringCentreX(slot: Int): Float = centreX[slot]

    internal fun ringCentreY(slot: Int): Float = centreY[slot]

    /** The radius of ring [slot], in shorter sides, for tests. */
    internal fun radiusOf(slot: Int): Float = radius[slot]

    /** The slot of the newest ring, -1 before the first. For tests. */
    internal val newestRing: Int get() = lastSpawned

    /** How far ring [slot] bends with its waveform now, in shorter sides. For tests. */
    internal fun amplitudeOf(slot: Int): Float = amplitude(slot)

    /** Times a birth has moved a well to the loudest band group. For tests. */
    internal val wellMoves: Int get() = moves

    /** Hits in the shared impulse list now. For tests. */
    internal val impulseCount: Int get() = kit.impulses.count

    /** A birth: the well off the loudest band group takes it over and rings once. */
    internal fun birth() {
        leader = 1 - leader
        spawn(leader, 0.8f, lastFrame ?: return)
        moves++
    }

    private var lastFrame: SpectrumFrame? = null

    // The snow: hats settle into a drift that fades over eight heard seconds, and the ring fronts
    // gather it. In Frost, lace grows inside the rings in the drift's extra channel.
    private val drift = MemoryField(rows = DRIFT_ROWS, withExtra = true).also { it.halfLife = DRIFT_HALF_LIFE }
    private val driftFlow = Flows.Drift(DRIFT_X, DRIFT_Y)
    private val driftProgram: ShaderProgram by lazy { ShaderProgram(DRIFT) }
    private val lace = LaceReaction()
    private var laceMask = FloatArray(0)
    private var laceOwed = 0f

    // The form, how long the lake has been under water, and the bubbles hats send up there.
    private var form = LAKE
    private var underwaterAge = 0f
    private val bubbles = Sprites(120, 1_213L)

    // The camera: a glide over the lake on the slow cycle, and a dive under it after a drop.
    private val presence = Envelope(2f, 0.5f)
    private val glideXSlew = Slew(GLIDE_RATE)
    private val glideYSlew = Slew(GLIDE_RATE)
    private val diveSlew = Slew(DIVE_RATE, 1f)
    private var glideSign = 1f

    override val forms: FormReadout get() = FormReadout(FORMS[form], evolution.morphs, evolution.births)

    /** Sets the form at once, for tests. */
    internal fun jumpTo(form: Int) {
        enter(form)
    }

    /** The camera's glide, in shares of the width and the height, and its dive, for tests. */
    internal val glideX: Float get() = glideXSlew.value
    internal val glideY: Float get() = glideYSlew.value
    internal val diveValue: Float get() = diveSlew.value

    /** The share of the drift's cells that hold snow, for tests. */
    internal fun driftInkShare(): Float = shareOf { column, row -> drift.inkAt(drift.xOf(column), drift.yOf(row)) > 0.05f }

    /** The drift's snow at [x], [y] in shares of the screen, for tests. */
    internal fun driftInkAt(x: Float, y: Float): Float = drift.inkAt((x - 0.5f) * 2f * kit.aspect, (y - 0.5f) * 2f)

    /** The share of the drift's cells that hold lace, for tests. */
    internal fun laceShare(): Float = shareOf { column, row -> drift.extraAt(drift.xOf(column), drift.yOf(row)) > 0.5f }

    /** Bubbles rising now, for tests. */
    internal fun bubbleCount(): Int = (0 until bubbles.capacity).count { bubbles.pool.remaining(it) > 0f }

    private inline fun shareOf(test: (Int, Int) -> Boolean): Float {
        var count = 0
        for (row in 0 until drift.rows) for (column in 0 until drift.columns) if (test(column, row)) count++
        return count / (drift.rows * drift.columns).toFloat()
    }

    /** Pieces of broken ice still sinking. For tests. */
    internal val sinking: Int get() = if (breakAge < 0f) 0 else pieceCount

    /** Hairline cracks accented kicks have added since the last section. For tests. */
    internal val kickCracksThisSection: Int get() = kickCracks

    /** Every crossing point of a ring of well A with a ring of well B, as x and y pairs in shorter sides. For tests. */
    internal fun crossingPoints(): List<Pair<Float, Float>> {
        val found = ArrayList<Pair<Float, Float>>()
        forEachCrossing { _, _, x, y -> found += x to y }
        return found
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val step = state.stepSeconds
        val frame = state.frame
        updateScreen(kit.aspect)

        if (gestures.breakdown) breakdown = true else if (gestures.turn || gestures.surge) breakdown = false
        calm += ((if (breakdown) 1f else 0f) - calm) * (1f - exp(-step / 0.5f))

        allowed = state.lightScale.coerceIn(0f, 1f)
        light = allowed * max(IDLE_LIGHT * (1f - frame.audible), lightFor(state.energy)) * (1f - 0.4f * calm)
        updateKey(state, step)
        lastFrame = frame
        followTheMix(frame, step)

        // A section rings both wells once where they stand.
        if (gestures.turn) {
            kickCracks = 0
            spawn(0, 0.6f, frame)
            spawn(1, 0.6f, frame)
        }
        if (evolution.birth) birth()

        // Rings spread one spacing a beat, slower in a breakdown, and not at all in a silence.
        val speed = SPACING / gestures.beatSeconds.coerceAtLeast(0.15f) * (1f - 0.55f * calm)
        for (slot in 0 until SLOTS) {
            if (!alive[slot]) continue
            // Every ring splashes out quickly when it is born and then spreads at the beat's pace.
            // The splash is the same for every ring, so rings born a beat apart stay evenly spaced.
            age[slot] += step
            travelled[slot] += speed * step
            radius[slot] = START_RADIUS + SPLASH * (1f - exp(-age[slot] / SPLASH_SECONDS)) + travelled[slot]
            if (radius[slot] > reachFrom(centreX[slot], centreY[slot])) alive[slot] = false
        }
        fillProfile(frame, liveProfile, 0)
        fillLevel(frame, liveLevel, 0)
        for (point in 0 until SHADES) liveShade[point] = 0.45f + 0.55f * liveLevel[point]

        // Kicks ring well A and snares well B. A song with no kick rings A on its onsets, and one
        // with no snare rings B on its hats, at most once a beat each.
        sinceKick += step
        sinceSnare += step
        sinceFallbackA += step
        sinceFallbackB += step
        val beat = gestures.beatSeconds
        val kickAcross = 0.5f + BALANCE_REACH * balanceSlew.value
        if (gestures.kick > 0f) {
            val accented = gestures.kick >= max(0.5f, kickAverage * 1.15f)
            kickAverage += (gestures.kick - kickAverage) * 0.2f
            spawnAt(0, kickAcross, groupAlong(0), gestures.kick, frame)
            impulse(Impulses.LOW, gestures.kick)
            sinceKick = 0f
            if (accented && !breakdown && kickCracks < MOST_KICK_CRACKS) {
                kickCrack()
                kickCracks++
            }
        } else if (sinceKick > gestures.cycleSeconds && sinceFallbackA > beat * 0.9f) {
            val onset = onsetIn(frame)
            if (onset > 0f) {
                spawn(0, onset * 0.8f, frame)
                sinceFallbackA = 0f
            }
        }
        if (gestures.snare > 0f) {
            spawnAt(1, kickAcross, 0.5f, gestures.snare, frame)
            impulse(Impulses.BODY, gestures.snare)
            sinceSnare = 0f
        } else if (gestures.hat > 0f && sinceSnare > gestures.cycleSeconds && sinceFallbackB > beat * 0.9f) {
            spawnAt(1, kickAcross, groupAlong(GROUPS - 1), gestures.hat * 0.8f, frame)
            sinceFallbackB = 0f
        }
        if (gestures.hat > 0f) {
            // A hat that rang no ring still lands in the impulse list, at the hats' place.
            placeAt(kickAcross, groupAlong(GROUPS - 1))
            impulse(Impulses.HIGH, gestures.hat)
            glint = max(glint, gestures.hat)
            hats++
        }
        // Everything below ages on the music's clock, so a pause holds the picture as it is.
        glint *= exp(-step / 0.2f)
        for (well in 0 until WELLS) wellFlash[well] *= exp(-step / 0.25f)

        // Cracks creep in, a breakdown heals them, and in the last beat before a drop cracks creep
        // out of the wells.
        for (crack in 0 until CRACKS) {
            if (crackAlpha[crack] <= 0f) continue
            if (!crackCreeping[crack]) crackGrow[crack] = (crackGrow[crack] + step / 0.25f).coerceAtMost(1f)
            if (breakdown) crackAlpha[crack] -= step / gestures.cycleSeconds.coerceAtLeast(0.5f)
        }
        creep(state)

        if (gestures.surge) breakPending = true
        if (breakAge >= 0f) {
            breakAge += step
            if (breakAge > breakBar * 1.1f) breakAge = -1f
        }
        chooseForm(frame)
        settleSnow(step, state.energy)
        moveCamera(state)
    }

    /**
     * One chain a frame: a drop shatters the ice, a breakdown brings Frost, a turn or a surge ends
     * Frost, a section or eight cycles freeze a new lake over the water, and a morph turns the glide
     * and swaps the wells. The ice goes under water once the last piece has sunk.
     */
    private fun chooseForm(frame: SpectrumFrame) {
        if (evolution.bloom || gestures.surge) {
            enter(SHATTER)
        } else if (evolution.collapse) {
            enter(FROST)
        } else if (form == FROST && (gestures.turn || gestures.surge)) {
            enter(LAKE)
        } else if (form == UNDERWATER && (gestures.section || underwaterAge > UNDERWATER_CYCLES * gestures.cycleSeconds)) {
            freezeNewLake(frame)
        } else if (evolution.morph) {
            glideSign = -glideSign
            leader = 1 - leader
        }
        if (form == SHATTER && (breakAge < 0f || breakAge > breakBar) && !breakPending) enter(UNDERWATER)
    }

    private fun enter(next: Int) {
        if (next == FROST && form != FROST) sproutLace = true
        if (next == UNDERWATER && form != UNDERWATER) underwaterAge = 0f
        form = next
    }

    private var sproutLace = false

    /** The rings clear, two rings start from the wells, and the lake is ice again. */
    private fun freezeNewLake(frame: SpectrumFrame) {
        alive.fill(false)
        spawn(0, 0.6f, frame)
        spawn(1, 0.6f, frame)
        enter(LAKE)
    }

    /**
     * Hats lay snow where they land and along every young ring front, and the drift creeps on heard time.
     * In Frost, lace grows inside the rings.
     */
    private fun settleSnow(step: Float, energy: Float) {
        drift.size(kit.aspect)
        if (form == UNDERWATER) underwaterAge += step
        val hat = gestures.hat
        if (hat > 0f) {
            drift.disc((hitX - 0.5f) * 2f * kit.aspect, (hitY - 0.5f) * 2f, SNOW_RADIUS, SNOW_INK * hat)
            // The snow that falls on a young ring front settles along it, so each hat leaves the fronts as they stood.
            val toField = 2f * min(screenW, screenH) / screenH
            for (slot in 0 until SLOTS) {
                if (!alive[slot] || age[slot] > GATHER_SECONDS) continue
                drift.ring((centreX[slot] - 0.5f) * 2f * kit.aspect, (centreY[slot] - 0.5f) * 2f,
                    radius[slot] * toField, GATHER_WIDTH, GATHER_INK * strength[slot] * hat)
            }
            if (form == UNDERWATER) {
                bubbles.burst(hitX, hitY, BUBBLES, 0.3f, 2f, 0.01f, 0.5f, Sprite.RING, UP, 0.3f)
            }
        }
        drift.advance(driftFlow, step)
        bubbles.advance(step, drag = 0.1f, gravity = -0.2f)
        if (form == FROST && step > 0f) {
            fillLaceMask()
            if (sproutLace) {
                lace.sprout(drift, laceMask, LACE_SEEDS, 1.6f, random)
                sproutLace = false
            }
            // Contour's pace: about one reaction step a heard frame, up to three when the music is loud.
            laceOwed += step * (LACE_STEPS + LACE_LOUD_STEPS * energy)
            var steps = 0
            while (laceOwed >= 1f && steps < MOST_LACE_STEPS) {
                laceOwed -= 1f
                lace.step(drift, laceMask)
                steps++
            }
            laceOwed = laceOwed.coerceAtMost(1f)
        }
    }

    /** Marks the drift's cells that lie inside a live ring. */
    private fun fillLaceMask() {
        val cells = drift.columns * drift.rows
        if (laceMask.size != cells) laceMask = FloatArray(cells)
        for (row in 0 until drift.rows) {
            val y = (drift.yOf(row) * 0.5f + 0.5f) * screenH
            for (column in 0 until drift.columns) {
                val x = (drift.xOf(column) / kit.aspect * 0.5f + 0.5f) * screenW
                var inside = 0f
                for (slot in 0 until SLOTS) {
                    if (!alive[slot]) continue
                    val dx = x - centreX[slot] * screenW
                    val dy = y - centreY[slot] * screenH
                    if (dx * dx + dy * dy < radius[slot] * radius[slot]) {
                        inside = 1f
                        break
                    }
                }
                laceMask[row * drift.columns + column] = inside
            }
        }
    }

    /** The glide follows the slow cycle while music plays; the dive goes under after a drop. A pause holds both. */
    private fun moveCamera(state: VizRenderState) {
        val live = if (state.frame.held) 0f else state.deltaSeconds
        presence.advance(state.frame.audible, live)
        val reach = presence.value * state.motionScale.coerceIn(0f, 1f) * glideSign
        val phase = gestures.slowCyclePhase * TAU
        glideXSlew.advance(GLIDE_REACH * sin(phase) * reach, live)
        glideYSlew.advance(GLIDE_REACH_Y * cos(phase) * reach, live)
        diveSlew.advance(if (form == SHATTER || form == UNDERWATER) DIVE else 1f, live)
    }

    /**
     * Keeps the screen's size in shorter sides. A turn between landscape and portrait swaps the
     * roles of the two sides of the wells, so the rings already born swap their centres to stay on
     * their wells. The first draw on a phone in portrait is such a turn, because a drawing starts
     * out with the landscape shape.
     */
    private fun updateScreen(aspect: Float) {
        val landscape = aspect >= 1f
        if (landscape) {
            screenW = aspect
            screenH = 1f
        } else {
            screenW = 1f
            screenH = 1f / aspect.coerceAtLeast(0.1f)
        }
        if (landscape != wasLandscape) {
            wasLandscape = landscape
            // The picture turns a quarter, so the bass end of the long side goes from the left to the bottom.
            for (slot in 0 until SLOTS) {
                val x = centreX[slot]
                val y = centreY[slot]
                centreX[slot] = if (landscape) 1f - y else y
                centreY[slot] = if (landscape) x else 1f - x
            }
            // The cracks and the well of the sinking pieces are in shorter sides from the top left.
            val long = max(screenW, screenH)
            for (point in crackX.indices) {
                val x = crackX[point]
                val y = crackY[point]
                crackX[point] = if (landscape) long - y else y
                crackY[point] = if (landscape) x else long - x
            }
            val x = pieceWellX
            val y = pieceWellY
            pieceWellX = if (landscape) long - y else y
            pieceWellY = if (landscape) x else long - x
        }
    }

    /** Whether the screen was landscape the last time its size was read. */
    private var wasLandscape = true

    /**
     * Where well [well] is, in shares of the screen. The bass end of the long side is the left of a
     * landscape screen and the bottom of a portrait one.
     */
    private fun wellX(well: Int): Float = xOf(wellAcross[well], wellAlong[well])

    private fun wellY(well: Int): Float = yOf(wellAcross[well], wellAlong[well])

    private fun xOf(across: Float, along: Float): Float = if (screenW >= screenH) along else across

    private fun yOf(across: Float, along: Float): Float = if (screenW >= screenH) across else 1f - along

    /** The place of band group [group] along the long side, from its bass end. */
    private fun groupAlong(group: Int): Float = (group + 0.5f) / GROUPS

    /**
     * Reads the mix once a frame: the stereo balance, and the level of each quarter of the bands.
     * The leader well slews to the loudest quarter and the other to the second loudest, both across
     * to where the balance leans.
     */
    private fun followTheMix(frame: SpectrumFrame, step: Float) {
        val left = energyOf(frame.scopeLeft)
        val right = energyOf(frame.scopeRight)
        val leaning = if (left + right > 1e-9f) (right - left) / (right + left) else 0f
        balanceSlew.advance(leaning, step)
        val bands = frame.bandsRel
        val ease = 1f - exp(-step / GROUP_SECONDS)
        for (group in 0 until GROUPS) {
            val from = group * bands.size / GROUPS
            val to = (group + 1) * bands.size / GROUPS
            var sum = 0f
            for (band in from until to) sum += bands[band]
            val level = if (to > from) sum / (to - from) else 0f
            groupLevel[group] += (level - groupLevel[group]) * ease
        }
        var loudest = 0
        for (group in 1 until GROUPS) if (groupLevel[group] > groupLevel[loudest]) loudest = group
        var second = if (loudest == 0) 1 else 0
        for (group in 0 until GROUPS) if (group != loudest && groupLevel[group] > groupLevel[second]) second = group
        val across = 0.5f + BALANCE_REACH * balanceSlew.value
        for (well in 0 until WELLS) {
            wellAlong[well] = alongSlew[well].advance(groupAlong(if (well == leader) loudest else second), step)
            wellAcross[well] = acrossSlew[well].advance(across, step)
        }
    }

    private fun energyOf(wave: FloatArray): Float {
        var sum = 0f
        for (sample in wave) sum += sample * sample
        return if (wave.isEmpty()) 0f else sum / wave.size
    }

    // The screen place of the last hit, in shares of the width and the height.
    private var hitX = 0.5f
    private var hitY = 0.5f

    private fun placeAt(across: Float, along: Float) {
        hitX = xOf(across, along)
        hitY = yOf(across, along)
    }

    /** Adds a hit at the last hit place to the shared impulse list, in centred units with y down. */
    private fun impulse(kind: Int, strength: Float) {
        kit.impulses.add(kind, strength, (hitX - 0.5f) * 2f * kit.aspect, (hitY - 0.5f) * 2f, random.next())
    }

    /** A new ring of [pool]'s at a place of the mix, [across] and [along] in shares of the screen. */
    private fun spawnAt(pool: Int, across: Float, along: Float, hit: Float, frame: SpectrumFrame) {
        placeAt(across, along)
        ring(pool, hitX, hitY, hit, frame)
    }

    /** How far a ring born at this centre spreads before the farthest corner of the screen is inside it. */
    private fun reachFrom(x: Float, y: Float): Float {
        val dx = max(x, 1f - x) * screenW
        val dy = max(y, 1f - y) * screenH
        return hypot(dx, dy) + 0.05f
    }

    /** A new ring from [well]: the waveform and the spectrum as they are now, as thick as [hit] is hard. */
    private fun spawn(well: Int, hit: Float, frame: SpectrumFrame) {
        ring(well, wellX(well), wellY(well), hit, frame)
    }

    /** A new ring of [pool]'s centred at [x], [y] in shares of the screen. */
    private fun ring(pool: Int, x: Float, y: Float, hit: Float, frame: SpectrumFrame) {
        val well = pool
        val slot = slotFor(well)
        lastSpawned = slot
        val force = hit.coerceIn(0f, 1f)
        alive[slot] = true
        centreX[slot] = x
        centreY[slot] = y
        radius[slot] = START_RADIUS
        age[slot] = 0f
        travelled[slot] = 0f
        thickness[slot] = THIN + (THICK - THIN) * force
        strength[slot] = 0.6f + 0.4f * force
        swing[slot] = SWING
        fillProfile(frame, profile, slot * PROFILE)
        fillLevel(frame, shade, slot * SHADES)
        for (point in 0 until SHADES) shade[slot * SHADES + point] = 0.45f + 0.55f * shade[slot * SHADES + point]
        wellFlash[well] = 1f
    }

    /** The waveform now, down one side of a ring: [PROFILE] samples from [from]. */
    private fun fillProfile(frame: SpectrumFrame, into: FloatArray, from: Int) {
        val scope = frame.scope
        val gain = frame.waveformGain
        for (point in 0 until PROFILE) {
            into[from + point] = if (scope.size < 2) 0f
            else (scope[point * (scope.size - 1) / (PROFILE - 1)] * gain).coerceIn(-1f, 1f)
        }
    }

    /** The spectrum now, down one side of a ring, bass at the top: [SHADES] levels, 0 to 1, from [from]. */
    private fun fillLevel(frame: SpectrumFrame, into: FloatArray, from: Int) {
        val bands = frame.bands
        for (point in 0 until SHADES) {
            val level = if (bands.isEmpty()) 0f else bands[(point * bands.size / SHADES).coerceAtMost(bands.size - 1)]
            val t = ((level - 0.04f) / 0.4f).coerceIn(0f, 1f)
            into[from + point] = t * t * (3f - 2f * t)
        }
    }

    /** A free slot of [well]'s, or the one whose ring has spread furthest. */
    private fun slotFor(well: Int): Int {
        var widest = well * PER_WELL
        for (slot in well * PER_WELL until (well + 1) * PER_WELL) {
            if (!alive[slot]) return slot
            if (radius[slot] > radius[widest]) widest = slot
        }
        return widest
    }

    /** The rings the lake starts with, flat and evenly spaced, so the first frame already shows both wells. */
    private fun seedRings() {
        alive.fill(false)
        for (well in 0 until WELLS) {
            for (ring in 0 until SEED_RINGS) {
                val slot = well * PER_WELL + ring
                alive[slot] = true
                centreX[slot] = wellX(well)
                centreY[slot] = wellY(well)
                radius[slot] = START_RADIUS + SPACING * (ring + 1 + 0.5f * well)
                age[slot] = LONG_AGO
                travelled[slot] = radius[slot] - START_RADIUS - SPLASH
                thickness[slot] = THIN + (THICK - THIN) * 0.4f
                strength[slot] = 0.7f
                swing[slot] = 0f
                for (point in 0 until PROFILE) profile[slot * PROFILE + point] = 0f
                for (point in 0 until SHADES) shade[slot * SHADES + point] = 0.75f
            }
        }
    }

    /** One hairline crack from an accented kick, along a ring of well A or out of the well. */
    private fun kickCrack() {
        var ring = -1
        for (slot in 0 until PER_WELL) {
            if (alive[slot] && radius[slot] > 0.12f && (ring < 0 || random.next() < 0.4f)) ring = slot
        }
        val startX: Float
        val startY: Float
        val heading: Float
        if (ring >= 0) {
            val angle = random.next() * TAU
            startX = centreX[ring] * screenW + cos(angle) * radius[ring]
            startY = centreY[ring] * screenH + sin(angle) * radius[ring]
            heading = angle + TAU / 4f * (if (random.next() < 0.5f) 1f else -1f) + random.signed() * 0.5f
        } else {
            val angle = random.next() * TAU
            startX = wellX(0) * screenW
            startY = wellY(0) * screenH
            heading = angle
        }
        addCrack(startX, startY, heading, 0.14f + 0.16f * random.next(), creeping = false)
    }

    /** In the last beat before a drop the analysed audio ahead already holds, cracks creep out of the wells. */
    private fun creep(state: VizRenderState) {
        val next = state.future?.nextEvent(AudioEventKind.Drop)
        val beat = gestures.beatSeconds.coerceAtLeast(0.15f)
        if (next == null || next.secondsUntil > beat) {
            if (creepArmed) {
                // The drop did not come: the creeping cracks finish growing and stay as hairlines.
                for (crack in 0 until CRACKS) crackCreeping[crack] = false
                creepArmed = false
            }
            return
        }
        if (!creepArmed) {
            creepArmed = true
            for (well in 0 until WELLS) {
                val turn = random.next() * TAU
                for (arm in 0 until CREEP_ARMS) {
                    val heading = turn + TAU * arm / CREEP_ARMS + random.signed() * 0.3f
                    addCrack(wellX(well) * screenW, wellY(well) * screenH, heading, 0.3f + 0.2f * random.next(), creeping = true)
                }
            }
        }
        val reached = (1f - next.secondsUntil / beat).coerceIn(0f, 1f)
        for (crack in 0 until CRACKS) if (crackCreeping[crack]) crackGrow[crack] = max(crackGrow[crack], reached)
    }

    /** A jagged hairline from a point, heading one way with small turns at every joint. */
    private fun addCrack(x: Float, y: Float, heading: Float, length: Float, creeping: Boolean) {
        var slot = 0
        for (crack in 0 until CRACKS) {
            if (crackAlpha[crack] <= 0f) {
                slot = crack
                break
            }
            if (crackAlpha[crack] < crackAlpha[slot]) slot = crack
        }
        val points = CRACK_POINTS - (random.next() * 3f).toInt()
        var px = x
        var py = y
        var toward = heading
        for (point in 0 until points) {
            crackX[slot * CRACK_POINTS + point] = px
            crackY[slot * CRACK_POINTS + point] = py
            toward += random.signed() * 0.5f
            val stride = length / (points - 1) * (0.7f + 0.6f * random.next())
            px += cos(toward) * stride
            py += sin(toward) * stride
        }
        crackLength[slot] = points
        crackGrow[slot] = 0f
        crackAlpha[slot] = 1f
        crackCreeping[slot] = creeping
    }

    /** The key turns both wells' colours by up to thirty degrees. */
    private fun updateKey(state: VizRenderState, step: Float) {
        val frame = state.frame
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - CYAN_HUE
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyTurn.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, step)
        val degrees = kotlin.math.round(keyTurn.value)
        val palette = state.palette
        if (palette === colourFor && degrees == colourTurn) return
        colourFor = palette
        colourTurn = degrees
        if (palette.name == VizPalette.Prism.name) {
            swatch(CYAN_L, CYAN_C, CYAN_HUE + degrees, hot[0])
            swatch(DEEP_BLUE_L, DEEP_BLUE_C, DEEP_BLUE_HUE + degrees, cool[0])
            swatch(ORCHID_L, ORCHID_C, ORCHID_HUE + degrees, hot[1])
            swatch(DEEP_VIOLET_L, DEEP_VIOLET_C, DEEP_VIOLET_HUE + degrees, cool[1])
        } else {
            palette.vivid(0.2f).into(hot[0])
            palette.vividAt(0.2f, DEEP_BLUE_L).into(cool[0])
            palette.vivid(0.75f).into(hot[1])
            palette.vividAt(0.75f, DEEP_VIOLET_L).into(cool[1])
        }
    }

    /** A swatch at its own lightness, at the most chroma the screen shows there, never above [chroma]. */
    private fun swatch(lightness: Float, chroma: Float, hue: Float, into: FloatArray) {
        colourOf(lightness, min(chroma, mostChroma(lightness, hue) - 0.005f).coerceAtLeast(0f), hue).into(into)
    }

    private fun Color.into(target: FloatArray) {
        target[0] = red
        target[1] = green
        target[2] = blue
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {}

    override fun DrawScope.drawTop(state: VizRenderState) {
        updateScreen(size.width / size.height.coerceAtLeast(1f))
        if (breakPending) {
            breakPending = false
            breakIce(state)
        }
        val dive = diveSlew.value
        withTransform({
            translate(glideXSlew.value * size.width, glideYSlew.value * size.height)
            scale(dive, dive, Offset(size.width * 0.5f, size.height * 0.5f))
        }) {
            if (form == UNDERWATER) {
                drawRect(Color(cool[0][0] * UNDER_LIGHT * light, cool[0][1] * UNDER_LIGHT * light, cool[0][2] * UNDER_LIGHT * light))
            }
            drawDrift()
            drawIce(state, broken = false)
            if (breakAge >= 0f) drawPieces(state)
            if (form == UNDERWATER) with(bubbles) { drawSprites(state.palette, genes.walk, alpha = light) }
        }
    }

    /** The snow as soft white and the lace as a thin white lattice, both under the allowed light. */
    private fun DrawScope.drawDrift() {
        if (!driftProgram.available || !canDrawRuntimeShaders()) return
        drift.bindTo(driftProgram)
        driftProgram.uniform("uResolution", size.width, size.height)
        driftProgram.uniform("uLight", light)
        val brush = driftProgram.brush() ?: return
        drawRect(brush)
    }

    /** The rings, the crossings, the wells and the cracks, as they stand. */
    private fun DrawScope.drawIce(state: VizRenderState, broken: Boolean) {
        val unit = min(size.width, size.height)
        rings.clear()
        marks.clear()
        glows.clear()
        val under = form == UNDERWATER && !broken
        for (slot in 0 until SLOTS) if (alive[slot]) ringInto(slot, unit, under)
        // Under water the rings are caustic light: no white crossings, no cracks.
        if (!under) {
            crossings(unit)
            wells(unit)
            crackLines(unit)
        }
        if (broken) breakLines(unit)
        drawMesh(rings, if (under) BlendMode.Plus else BlendMode.SrcOver)
        drawMesh(glows, BlendMode.Plus)
        drawMesh(marks)
    }

    /** One ring: a band round its well, bent by its waveform and lit by its spectrum. */
    private fun ringInto(slot: Int, unit: Float, under: Boolean) {
        val well = slot / PER_WELL
        val r = radius[slot]
        val spread = (r / reachFrom(centreX[slot], centreY[slot])).coerceIn(0f, 1f)
        // A ring is thicker and brighter for a moment as it is born.
        val fresh = exp(-age[slot] / 0.12f)
        val wide = if (under) CAUSTIC_WIDTH else 1f
        val half = max(0.6f, thickness[slot] * (1f + 0.6f * calm + 1.5f * fresh) * unit * 0.5f * wide)
        val fadeOut = ((1f - spread) / 0.15f).coerceIn(0f, 1f)
        val level = strength[slot] * light * fadeOut * (1f + 0.8f * fresh) * (if (under) CAUSTIC_LIGHT else 1f)
        val warm = if (under) hot[0] else hot[well]
        val cold = if (under) hot[0] else cool[well]
        bandInto(
            centreX[slot] * screenW * unit, centreY[slot] * screenH * unit, r * unit, amplitude(slot) * unit, half,
            warm[0] + (cold[0] - warm[0]) * spread, warm[1] + (cold[1] - warm[1]) * spread, warm[2] + (cold[2] - warm[2]) * spread,
            level, profile, slot * PROFILE, shade, slot * SHADES,
        )
    }

    /**
     * A band of [half] pixels either side of a circle of [radius] pixels, bent by [amp] pixels of a
     * waveform and lit round its circle by a spectrum. The sound now lights every band a little
     * more or less, as it plays, on top of the light it was born with.
     */
    private fun bandInto(
        cx: Float, cy: Float, radius: Float, amp: Float, half: Float,
        red: Float, green: Float, blue: Float, level: Float,
        bends: FloatArray, bendFrom: Int, lights: FloatArray, lightFrom: Int,
    ) {
        val first = rings.vertexCount
        for (point in 0 until POINTS) {
            // Mirrored about the vertical, so the ring closes on itself: the waveform runs down both sides.
            val along = (if (point <= POINTS / 2) point else POINTS - point).toFloat() / (POINTS / 2)
            // A newborn ring bends further than its own radius, so the bend never folds it through its centre.
            val bend = max(sample(bends, bendFrom, PROFILE, along) * amp, -0.8f * radius)
            val now = 0.85f + 0.3f * sample(liveLevel, 0, SHADES, along)
            val lit = (sample(lights, lightFrom, SHADES, along) * level * now).coerceIn(0f, 1f)
            val sx = sinTable[point]
            val sy = -cosTable[point]
            val colour = argb(red * lit, green * lit, blue * lit)
            val inner = radius + bend - half
            val outer = radius + bend + half
            rings.vertex(cx + sx * inner, cy + sy * inner, colour)
            rings.vertex(cx + sx * outer, cy + sy * outer, colour)
        }
        for (point in 0 until POINTS) {
            val a = first + point * 2
            val b = first + ((point + 1) % POINTS) * 2
            rings.quad(a, a + 1, b + 1, b)
        }
    }

    /** Every point where a ring of well A crosses a ring of well B flares white; hats make some glint. */
    private fun crossings(unit: Float) {
        forEachCrossing { a, b, x, y ->
            val fadeA = ((1f - radius[a] / reachFrom(centreX[a], centreY[a])) / 0.15f).coerceIn(0f, 1f)
            val fadeB = ((1f - radius[b] / reachFrom(centreX[b], centreY[b])) / 0.15f).coerceIn(0f, 1f)
            val heat = (min(strength[a] * fadeA, strength[b] * fadeB) * (0.5f + 0.7f * light) * allowed).coerceIn(0f, 1f)
            if (heat < 0.02f) return@forEachCrossing
            val size = max(thickness[a], thickness[b]) * unit
            val px = x * unit
            val py = y * unit
            marks.polygon(px, py, max(1f, size * 0.9f), 8, 0f, argb(heat, heat, heat))
            glows.glow(px, py, size * 4f + unit * 0.006f, argb(0.55f * heat, 0.55f * heat, 0.55f * heat), 8)
            if (glint > 0.05f && ((a * 31 + b * 17 + (px + py).toInt() + hats * 13) and 0x7FFFFFFF) % 3 == 0) {
                val star = glint * heat
                marks.spark(px, py, size * 3f + unit * 0.012f * star, max(0.8f, size * 0.35f), argb(star, star, star))
            }
        }
    }

    /**
     * Calls [each] with the two rings and the point, in shorter sides, of every crossing of a ring of
     * well A with a ring of well B. The point is worked out on the plain circles and then moved onto
     * the rings as their waveforms bend them, so the white point sits on both drawn lines.
     */
    private inline fun forEachCrossing(each: (Int, Int, Float, Float) -> Unit) {
        for (a in 0 until PER_WELL) {
            if (!alive[a]) continue
            val ax = centreX[a] * screenW
            val ay = centreY[a] * screenH
            val ra = radius[a]
            for (b in PER_WELL until SLOTS) {
                if (!alive[b]) continue
                val bx = centreX[b] * screenW
                val by = centreY[b] * screenH
                val dx = bx - ax
                val dy = by - ay
                val d = hypot(dx, dy)
                val rb = radius[b]
                if (d < 1e-4f || d > ra + rb || d < abs(ra - rb)) continue
                val along = (ra * ra - rb * rb + d * d) / (2f * d)
                val h = sqrt((ra * ra - along * along).coerceAtLeast(0f))
                val mx = ax + along * dx / d
                val my = ay + along * dy / d
                for (side in 0 until 2) {
                    val sign = if (side == 0) 1f else -1f
                    val x = mx - sign * h * dy / d
                    val y = my + sign * h * dx / d
                    // Each ring's own bend at this point, along its own radius; the point takes the middle.
                    val bendA = bendAt(a, x - ax, y - ay)
                    val bendB = bendAt(b, x - bx, y - by)
                    val onAx = x + (x - ax) / ra * bendA
                    val onAy = y + (y - ay) / ra * bendA
                    val onBx = x + (x - bx) / rb * bendB
                    val onBy = y + (y - by) / rb * bendB
                    each(a, b, (onAx + onBx) * 0.5f, (onAy + onBy) * 0.5f)
                }
            }
        }
    }

    /** How far ring [slot]'s waveform bends it, in shorter sides, in the direction [dx], [dy] from its centre. */
    private fun bendAt(slot: Int, dx: Float, dy: Float): Float {
        // The ring is drawn from the top, clockwise, and mirrored: the same share of a half turn either side.
        val along = abs(kotlin.math.atan2(dx, -dy)) / kotlin.math.PI.toFloat()
        return sample(profile, slot * PROFILE, PROFILE, along) * amplitude(slot)
    }

    /** How far ring [slot]'s waveform may bend it, in shorter sides: its detail smooths out as it spreads, as a ripple's does. */
    private fun amplitude(slot: Int): Float = swing[slot] * exp(-(radius[slot] - START_RADIUS) / 0.35f)

    /**
     * Each well: a white point in a small ring that trembles with the waveform as it plays, with its
     * colour round it, flaring for a moment when it sends a ring out.
     */
    private fun wells(unit: Float) {
        for (well in 0 until WELLS) {
            val x = wellX(well) * screenW * unit
            val y = wellY(well) * screenH * unit
            val flash = wellFlash[well]
            val colour = hot[well]
            bandInto(x, y, WELL_RING * unit, WELL_SWING * unit, max(0.6f, WELL_WIDTH * unit * 0.5f),
                colour[0], colour[1], colour[2], (0.45f + 0.55f * light) * allowed, liveProfile, 0, liveShade, 0)
            val glowLight = (0.35f + 0.65f * light) * allowed * (0.5f + 0.8f * flash)
            glows.glow(x, y, unit * (0.05f + 0.07f * flash), argb(colour[0] * glowLight, colour[1] * glowLight, colour[2] * glowLight), 16)
            val core = ((0.5f + 0.5f * light) * allowed).coerceIn(0f, 1f)
            marks.polygon(x, y, max(1.5f, unit * 0.006f), 10, 0f, argb(core, core, core))
        }
    }

    /** The hairline cracks, each drawn as far as it has crept. */
    private fun crackLines(unit: Float) {
        val width = max(1f, unit / 900f)
        val brightness = ((0.55f + 0.45f * light) * allowed).coerceIn(0f, 1f)
        for (crack in 0 until CRACKS) {
            val alpha = crackAlpha[crack]
            if (alpha <= 0f || crackGrow[crack] <= 0f) continue
            val points = crackLength[crack]
            val shown = crackGrow[crack] * (points - 1)
            val white = argb(brightness * alpha, brightness * alpha, brightness * alpha)
            for (joint in 0 until points - 1) {
                if (joint >= shown) break
                val base = crack * CRACK_POINTS + joint
                val part = (shown - joint).coerceAtMost(1f)
                val x0 = crackX[base]
                val y0 = crackY[base]
                val x1 = x0 + (crackX[base + 1] - x0) * part
                val y1 = y0 + (crackY[base + 1] - y0) * part
                line(marks, x0 * unit, y0 * unit, x1 * unit, y1 * unit, width, white)
            }
        }
    }

    /** The break itself: every ring and straight lines out of both wells, drawn into the copy it leaves. */
    private fun breakLines(unit: Float) {
        val width = max(1.5f, unit / 500f)
        val white = argb(allowed, allowed, allowed)
        for (slot in 0 until SLOTS) {
            if (!alive[slot]) continue
            val cx = centreX[slot] * screenW
            val cy = centreY[slot] * screenH
            var lastX = 0f
            var lastY = 0f
            for (point in 0..ARC_POINTS) {
                val angle = TAU * point / ARC_POINTS
                val r = radius[slot] * (1f + 0.01f * random.signed())
                val x = (cx + cos(angle) * r) * unit
                val y = (cy + sin(angle) * r) * unit
                if (point > 0) line(marks, lastX, lastY, x, y, width, white)
                lastX = x
                lastY = y
            }
        }
        // Well A's straight lines are the edges of the pieces, drawn with them; well B's are drawn here.
        for (well in 0 until WELLS) {
            val cx = wellX(well) * screenW
            val cy = wellY(well) * screenH
            val arms = if (well == 0) 0 else 7
            for (arm in 0 until arms) {
                val angle = TAU * arm / arms + random.signed() * 0.3f
                val r = reachFrom(wellX(well), wellY(well))
                line(marks, cx * unit, cy * unit, (cx + cos(angle) * r) * unit, (cy + sin(angle) * r) * unit, width, white)
            }
        }
    }

    /**
     * The drop: copies the picture with its break drawn in, cuts it into pieces round well A along
     * its rings and along straight lines out of it, and starts fresh rings on the water under them.
     */
    private fun DrawScope.breakIce(state: VizRenderState) {
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        val picture = snapshot?.takeIf { it.width == width && it.height == height }
            ?: ImageBitmap(width, height).also { snapshot = it }
        cutPieces()
        snapshotScope.draw(this, layoutDirection, Canvas(picture), Size(width.toFloat(), height.toFloat())) {
            drawRect(state.palette.background, blendMode = BlendMode.Src)
            drawIce(state, broken = true)
            // The lines the pieces part along, so every piece carries its own edge.
            val unit = min(this.size.width, this.size.height)
            for (index in 0 until pieceCount) {
                outline(index, unit)
                drawPath(piece, Color(allowed, allowed, allowed), style = Stroke(max(1.5f, unit / 500f)))
            }
        }
        alive.fill(false)
        crackAlpha.fill(0f)
        creepArmed = false
        kickCracks = 0
        breakAge = 0f
        breakBar = gestures.cycleSeconds.coerceIn(0.8f, 4f)
        spawn(0, 0.8f, state.frame)
        spawn(1, 0.8f, state.frame)
    }

    /** Pieces round well A: between its rings, and between straight lines out of it. */
    private fun cutPieces() {
        val cx = wellX(0) * screenW
        val cy = wellY(0) * screenH
        pieceWellX = cx
        pieceWellY = cy
        val reach = reachFrom(wellX(0), wellY(0))
        val bounds = FloatArray(MOST_BANDS + 1)
        var count = 1
        bounds[0] = 0f
        val radii = radiiOf(0)
        for (r in radii) {
            if (count >= MOST_BANDS) break
            if (r - bounds[count - 1] >= 0.07f && r < reach - 0.07f) bounds[count++] = r
        }
        while (count < MOST_BANDS && bounds[count - 1] + FILL_GAP < reach) {
            bounds[count] = bounds[count - 1] + FILL_GAP * (0.85f + 0.3f * random.next())
            count++
        }
        bounds[count++] = reach + 0.1f
        val turn = random.next() * TAU
        pieceCount = 0
        for (band in 0 until count - 1) {
            for (wedge in 0 until WEDGES) {
                if (pieceCount >= PIECES) break
                val width = TAU / WEDGES
                val jitterFrom = if (wedge == 0) 0f else wedgeJitter(band, wedge)
                val jitterTo = if (wedge == WEDGES - 1) 0f else wedgeJitter(band, wedge + 1)
                pieceInner[pieceCount] = bounds[band]
                pieceOuter[pieceCount] = bounds[band + 1]
                pieceFrom[pieceCount] = turn + width * (wedge + jitterFrom)
                pieceTo[pieceCount] = turn + width * (wedge + 1 + jitterTo)
                pieceDelay[pieceCount] = 0.2f * random.next()
                pieceCount++
            }
        }
    }

    // The straight lines out of the well kink a little at every ring, as they do in broken glass.
    private fun wedgeJitter(band: Int, wedge: Int): Float = 0.25f * sin(band * 12.9898f + wedge * 78.233f)

    /** Sets [piece] to the outline of piece [index] in pixels, in the place it was cut from. */
    private fun outline(index: Int, unit: Float) {
        piece.reset()
        val cx = pieceWellX * unit
        val cy = pieceWellY * unit
        val inner = pieceInner[index] * unit
        val outer = pieceOuter[index] * unit
        val from = pieceFrom[index]
        val to = pieceTo[index]
        for (step in 0..ARC_STEPS) {
            val angle = from + (to - from) * step / ARC_STEPS
            val x = cx + cos(angle) * outer
            val y = cy + sin(angle) * outer
            if (step == 0) piece.moveTo(x, y) else piece.lineTo(x, y)
        }
        if (inner <= 0f) {
            piece.lineTo(cx, cy)
        } else {
            for (step in ARC_STEPS downTo 0) {
                val angle = from + (to - from) * step / ARC_STEPS
                piece.lineTo(cx + cos(angle) * inner, cy + sin(angle) * inner)
            }
        }
        piece.close()
    }

    /** The pieces shrink into the water over one bar, each carrying its part of the copied picture. */
    private fun DrawScope.drawPieces(state: VizRenderState) {
        val picture = snapshot ?: return
        val unit = min(size.width, size.height)
        val motion = state.motionScale.coerceIn(0f, 1f)
        for (index in 0 until pieceCount) {
            val t = ((breakAge - pieceDelay[index] * breakBar) / (breakBar * 0.8f)).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            val fade = (1f - t) * (1f - t)
            if (fade <= 0.01f) continue
            // Under reduced motion the pieces fade where they lie instead of shrinking.
            val scale = 1f - eased * motion * 0.95f
            val middle = (pieceInner[index] + pieceOuter[index]) * 0.5f * unit
            val angle = (pieceFrom[index] + pieceTo[index]) * 0.5f
            val mx = pieceWellX * unit + cos(angle) * middle
            val my = pieceWellY * unit + sin(angle) * middle
            outline(index, unit)
            withTransform({ scale(scale, scale, Offset(mx, my)) }) {
                clipPath(piece) { drawImage(picture, alpha = fade) }
                // The edge is white hot as the ice breaks and cools as the piece sinks.
                val edge = (1f - 2.5f * t).coerceIn(0f, 1f)
                if (edge > 0f) drawPath(piece, Color(allowed, allowed, allowed, edge), style = Stroke(max(1.5f, unit / 450f)))
            }
        }
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

    override fun onReset() {
        wellAlong[0] = START_ALONG
        wellAlong[1] = 1f - START_ALONG
        wellAcross[0] = START_ACROSS
        wellAcross[1] = 1f - START_ACROSS
        for (well in 0 until WELLS) {
            alongSlew[well].reset(wellAlong[well])
            acrossSlew[well].reset(wellAcross[well])
        }
        leader = 0
        moves = 0
        balanceSlew.reset()
        groupLevel.fill(0f)
        lastSpawned = -1
        lastFrame = null
        hitX = 0.5f
        hitY = 0.5f
        drift.clear()
        lace.reset()
        laceMask.fill(0f)
        laceOwed = 0f
        sproutLace = false
        form = LAKE
        underwaterAge = 0f
        bubbles.pool.clear()
        presence.reset()
        glideXSlew.reset()
        glideYSlew.reset()
        diveSlew.reset(1f)
        glideSign = 1f
        wellFlash.fill(0f)
        liveProfile.fill(0f)
        liveLevel.fill(0f)
        liveShade.fill(0.45f)
        seedRings()
        crackAlpha.fill(0f)
        crackCreeping.fill(false)
        kickCracks = 0
        creepArmed = false
        sinceKick = LONG_AGO
        sinceSnare = LONG_AGO
        sinceFallbackA = LONG_AGO
        sinceFallbackB = LONG_AGO
        kickAverage = 0.6f
        breakdown = false
        calm = 0f
        glint = 0f
        hats = 0
        light = IDLE_LIGHT
        keyTurn.reset()
        colourFor = null
        breakPending = false
        breakAge = -1f
        pieceCount = 0
    }

    internal companion object {
        const val WELLS = 2
        const val PER_WELL = 16
        const val SLOTS = WELLS * PER_WELL
        const val SEED_RINGS = 5
        /** Points round one ring, and samples of its waveform and its spectrum down one side. */
        const val POINTS = 180
        const val PROFILE = 64
        const val SHADES = 24

        /** Shorter sides of the screen: a new ring's radius, the distance a ring spreads in a beat, and ring widths. */
        /** The small ring at a well that trembles with the waveform as it plays. */
        const val WELL_RING = 0.06f
        const val WELL_WIDTH = 0.003f

        /** A new ring leaves from the well's own trembling ring, in the shape that ring had. */
        const val START_RADIUS = WELL_RING
        /** How far a new ring splashes out in its first moments, and how fast. */
        const val SPLASH = 0.03f
        const val SPLASH_SECONDS = 0.08f
        const val SPACING = 0.075f
        const val THIN = 0.0022f
        const val THICK = 0.0075f
        /** How far a newborn ring bends with its waveform, and how far the small ring at a well trembles. */
        const val SWING = 0.12f
        const val WELL_SWING = 0.045f


        const val START_ALONG = 0.36f
        const val START_ACROSS = 0.55f

        /** The mix: quarters of the bands, how fast their levels ease, how fast the wells slew, and how far the balance moves a place across. */
        val FORMS = listOf("Lake", "Frost", "Shatter", "Underwater")
        const val LAKE = 0
        const val FROST = 1
        const val SHATTER = 2
        const val UNDERWATER = 3
        /** Cycles the lake stays under water before a new lake freezes over it, when no section comes first. */
        const val UNDERWATER_CYCLES = 8

        /** The drift: rows of cells, half life in heard seconds, creep in centred units a second, and the snow a hat lays. */
        const val DRIFT_ROWS = 54
        const val DRIFT_HALF_LIFE = 8f
        const val DRIFT_X = 0.02f
        const val DRIFT_Y = 0.012f
        const val SNOW_RADIUS = 0.05f
        const val SNOW_INK = 0.6f
        /** How wide in cells and how thick a ring front gathers the snow. */
        const val GATHER_WIDTH = 1.5f
        const val GATHER_INK = 0.3f
        const val GATHER_SECONDS = 2f
        const val LACE_SEEDS = 40
        const val LACE_STEPS = 60f
        const val LACE_LOUD_STEPS = 120f
        const val MOST_LACE_STEPS = 3

        /** Under water: the light of the deep, the rings as wide soft caustics, bubbles per hat, and up. */
        const val UNDER_LIGHT = 0.3f
        const val CAUSTIC_WIDTH = 3f
        const val CAUSTIC_LIGHT = 0.25f
        const val BUBBLES = 4
        const val UP = -TAU / 4f

        /** The camera: the glide's reach across and down, its pace, and the dive's depth and pace. */
        const val GLIDE_REACH = 0.06f
        const val GLIDE_REACH_Y = 0.04f
        const val GLIDE_RATE = 0.1f
        const val DIVE = 1.6f
        const val DIVE_RATE = 0.25f

        const val GROUPS = 4
        const val GROUP_SECONDS = 1f
        const val WELL_RATE = 0.4f
        const val BALANCE_RATE = 4f
        const val BALANCE_REACH = 0.3f

        const val CRACKS = 24
        const val CRACK_POINTS = 9
        const val MOST_KICK_CRACKS = 6
        const val CREEP_ARMS = 4

        const val PIECES = 72
        const val MOST_BANDS = 8
        const val WEDGES = 9
        const val FILL_GAP = 0.2f
        const val ARC_STEPS = 6
        const val ARC_POINTS = 96

        /** The share of full light the lake keeps in a silence. */
        const val IDLE_LIGHT = 0.3f
        const val LONG_AGO = 99f
        const val KEY_TURN = 30f

        // The swatches: cyan cooling to deep blue for well A, orchid cooling to deep violet for well B.
        const val CYAN_L = 0.86f
        const val CYAN_C = 0.14f
        const val CYAN_HUE = 205f
        const val DEEP_BLUE_L = 0.45f
        const val DEEP_BLUE_C = 0.20f
        const val DEEP_BLUE_HUE = 262f
        const val ORCHID_L = 0.62f
        const val ORCHID_C = 0.25f
        const val ORCHID_HUE = 320f
        const val DEEP_VIOLET_L = 0.32f
        const val DEEP_VIOLET_C = 0.16f
        const val DEEP_VIOLET_HUE = 300f

        /** The drift: the snow as soft white, and the lace as a thin white lattice. */
        val DRIFT: String = """
uniform float2 uResolution;
uniform float uLight;
""" + FieldShader.SOURCE + """
half4 main(float2 position) {
    float snow = 0.35 * uLight * clamp(fieldAt(position).x, 0.0, 1.0);
    float lace = isoLine(fieldExtra(position), 0.5, 0.02, fieldExtraSlope(position)) * uLight;
    float alpha = clamp(snow + lace, 0.0, 1.0);
    return half4(half3(alpha), half(alpha));
}
"""

        /** The value at [along], 0 to 1, of [count] samples starting at [from], read between samples. */
        fun sample(values: FloatArray, from: Int, count: Int, along: Float): Float {
            val at = along.coerceIn(0f, 1f) * (count - 1)
            val low = at.toInt().coerceAtMost(count - 2)
            val t = at - low
            return values[from + low] + (values[from + low + 1] - values[from + low]) * t
        }

        fun argb(red: Float, green: Float, blue: Float): Int {
            val r = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val g = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val b = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** A straight line of [width] pixels as one quad. */
        fun line(mesh: TriangleMesh, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, argb: Int) {
            val dx = x1 - x0
            val dy = y1 - y0
            val length = hypot(dx, dy)
            if (length < 1e-3f) return
            val nx = -dy / length * width * 0.5f
            val ny = dx / length * width * 0.5f
            val a = mesh.vertex(x0 + nx, y0 + ny, argb)
            val b = mesh.vertex(x1 + nx, y1 + ny, argb)
            val c = mesh.vertex(x1 - nx, y1 - ny, argb)
            val d = mesh.vertex(x0 - nx, y0 - ny, argb)
            mesh.quad(a, b, c, d)
        }
    }
}
