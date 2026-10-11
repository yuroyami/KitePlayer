package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import kotlin.math.roundToInt

/**
 * The music's own signal as a broken broadcast: the spectrogram as large blocks. Across is time, four
 * heard seconds scrolling to the left; up is band, with the bass at the bottom. A block takes the first,
 * second or third swatch of the set by its level. The waveform is a thick white trace across a clear band
 * in the middle, and a brighter scan bar rolls down once a bar. Dense music coarsens the blocks into
 * pairs and fours; that reads the frame's density, which no driver names, so the mapping declares nothing
 * for it. The key biases only which set the next section brings, so it declares nothing either.
 *
 * The drums break it the way damaged video breaks: a kick splits the red, green and blue layers for
 * a moment, a snare tears slices sideways by how much the spectrum changed, and a hat slips one row of
 * blocks. A section changes the channel to the next set, which the key biases, and the walk turns the
 * hues a little within a set. A breakdown collapses the picture into one bright line over a faint
 * static, and a drop freezes the frame into a datamosh that smears into the new picture until a clean
 * frame snaps back on the next first beat.
 *
 * The forms are Broadcast, Tracking (the roll reaches twice as far), Mosh (a datamosh every cycle) and
 * Dead air (the breakdown). A birth tears a new set in from the top.
 *
 * For the eyes: every block is at least 24 pixels, the hues change only with the set, neighbouring blocks
 * step by at most half the light, nothing is a hairline, and the flash guard dims every layer, the static
 * and the datamosh included.
 */
internal class Glitch : Layered(
    "Glitch", VizEnergy.Mid,
    Kit(7_315L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f,
        cuts = false, minZoom = 1f, maxZoom = 1f)),
) {
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Brightness, response = VizResponse.envelope(GlitchScene.GLOW_RISE)),
        // The newest column of blocks is the bands now.
        VizDrive(VizDriver.Bands, VizProperty.Shape, response = VizResponse.envelope(GlitchScene.GLOW_RISE)),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Colour, VizCurve.Scaled, VizResponse.lifetime(GlitchScene.SPLIT_SECONDS)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.lifetime(4f / 60f)),
        // A hat slips one row of blocks for two to four frames.
        VizDrive(VizDriver.HighHit, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.07f)),
        // The pulse, or the mood while no pulse is known, sets how fast the scan bar rolls.
        VizDrive(VizDriver.Pulse, VizProperty.Shape, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Shape, response = VizResponse.Rate),
        VizDrive(VizDriver.Section, VizProperty.Colour, VizCurve.Discrete),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete),
        silence = VizSilence.Still,
    )

    /**
     * Only the lines of an old screen, every third row 15 percent darker, and a little glow on what is
     * white. The split is drawn by the drawing itself, sideways and for a moment after a kick, and the
     * drop has its own moment, so the finishing pass adds neither.
     */
    override val post: PostSpec = PostSpec(bloom = 0.2f, bloomRadius = 0.02f, threshold = 0.85f,
        vignette = 0f, grain = 0f, glitch = false, aberration = 0f, scanlines = SCANLINES)

    /** The picture as numbers. Tests read it to know which fault a frame shows. */
    internal val scene = GlitchScene()
    private val geometry = GlitchGeometry()
    private val fields = GlitchFields()

    override val forms: FormReadout
        get() = FormReadout(GlitchScene.FORMS[scene.form], evolution.morphs, evolution.births)

    override fun advance(state: VizRenderState) {
        scene.advance(state, gestures, evolution, random, genes.walk)
    }

    // Everything is drawn sharp in the front. The datamosh keeps its own frames.
    override fun DrawScope.drawEcho(state: VizRenderState) {}

    override fun DrawScope.drawTop(state: VizRenderState) {
        // The frame last shown is still in the batch until it is rebuilt, so it is the one that freezes.
        if (scene.takeMoshStart()) with(fields) { freeze(geometry.picture, scene) }
        scene.layout(size.width, size.height)
        geometry.build(scene, size.width, size.height)
        if (scene.moshing) with(fields) { slide(geometry.picture, scene, scene.takeMoshSeconds()) }
        drawRect(Color.Black)
        val light = state.lightScale.coerceIn(0f, 1f)
        if (scene.staticOn) {
            with(fields) { drawStatic(scene, light) }
            return
        }
        // Dead air: a faint static under the line of a breakdown.
        if (scene.deadAir) with(fields) { drawStatic(scene, light * GlitchScene.DEAD_AIR_STATIC / GlitchFields.STATIC_LIGHT) }
        val split = (scene.split * size.width).roundToInt().toFloat()
        val reduced = state.motionScale < 1f
        signal(split, reduced, light)
        for (slice in 0 until scene.slices) {
            val top = (scene.sliceTop[slice] * size.height).roundToInt().toFloat()
            val bottom = ((scene.sliceTop[slice] + scene.sliceHeight[slice]) * size.height).roundToInt().toFloat()
            val shift = (scene.sliceShift[slice] * size.width).roundToInt().toFloat()
            if (bottom <= top || shift == 0f) continue
            // A torn slice slides sideways and wraps round, the way a line of a signal loses its hold.
            val wrap = if (shift > 0f) shift - size.width else shift + size.width
            clipRect(0f, top, size.width, bottom) {
                drawRect(Color.Black, Offset(0f, top), Size(size.width, bottom - top))
                translate(shift, 0f) { signal(split, reduced, light) }
                translate(wrap, 0f) { signal(split, reduced, light) }
            }
        }
    }

    /** The picture, or the datamosh at [light] while one runs. The picture is already lit. */
    private fun DrawScope.signal(split: Float, reduced: Boolean, light: Float) {
        if (scene.moshing) with(fields) { drawMosh(split, reduced, light) } else with(geometry) { drawPicture(split) }
    }

    override fun onReset() {
        scene.reset()
        fields.reset()
    }

    private companion object {
        /** The tile's dark row is 0.45 grey, so this much of it darkens every third row by 15 percent. */
        const val SCANLINES = 0.27f
    }
}
