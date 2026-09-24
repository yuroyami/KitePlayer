package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Musical Spectrum, a port of "ShowCQTBar" by Muhammad Faiz.
 *
 * - Title: ShowCQTBar ("ShowCQTBar Audio Spectrum Visualization")
 * - Author: Muhammad Faiz (mfcc64)
 * - URL: https://mfcc64.github.io/html5-showcqtbar/
 * - Repository: https://github.com/mfcc64/html5-showcqtbar (the page); the transform engine is
 *   https://github.com/mfcc64/showcqt-js
 * - Licence as found: the page's repository has no licence file. The engine is LGPL-3.0-or-later,
 *   "Copyright (c) 2020 Muhammad Faiz <mfcc64@gmail.com>". No engine code is copied here: the
 *   transform is re-implemented from its formulas.
 * - Year: 2017 (the page), 2020 (the engine)
 * - The engine is the author's port of the showcqt filter he wrote for FFmpeg.
 *
 * Deviations from the original:
 * - The panel fills the frame at any shape. The page's canvas was at most 8:3, so on a 16:9 frame
 *   the bars are half as tall again for their width.
 * - The transform reads the analyser's stereo sample history rather than two browser analysers,
 *   and centres its window on the heard instant. The page centred it 33 ms before the newest sample
 *   in its audio graph, which leads the speaker by the output latency.
 * - The transform runs 60 times per heard second, as the page ran once per frame on a 60 Hz
 *   screen, so the picture holds while paused or silent.
 * - Columns are one per dp, where the page had one per CSS pixel, up to 1920, stretched to the
 *   frame. A frame narrower than 640 dp gets one column per dp; the page never drew fewer than 640.
 * - The note names are strokes drawn in place of the page's axis image. The band, the lines and
 *   the semitone dots keep the image's layout.
 * - The page's knobs and its waterfall are left out, at their defaults: bar 19 dB, brightness
 *   25 dB, bass -30 dB, waterfall off.
 * - Colours are multiplied by the flash guard's light share.
 * - Where runtime shaders cannot run, the bars are drawn from a picture 96 rows tall, stretched.
 *
 * Ten octaves of hair-thin bars stand on a musical scale from E0 at the left to E10 at the right,
 * a semitone to every twelfth of an octave, with a note-name ruler across the middle and a mirror
 * below. Each bar is the loudness of its note, brightest at its base and fading to black at its
 * tip. Its colour is where the note sits in the stereo image: grey-white in the centre, amber on the
 * left, azure on the right. The deepest bass is cut before the transform, so a kick does not flood
 * the left edge.
 */
internal class MusicalSpectrum : ShaderPreset(
    source = SOURCE,
    name = "Musical Spectrum",
    bucket = VizEnergy.Mid,
    seed = 19f,
    kit = Kit(seed = 1_907L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f,
        cuts = false, minZoom = 1f, maxZoom = 1f)),
) {
    override val post: PostSpec get() = PostSpec.Off
    override val frontParallax: Float get() = 0f
    override val hasFallback: Boolean get() = true

    override val mapping: VizMapping by mappingOf(
        // Every note's loudness sets its bar's height and the brightness of its colour.
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Bands, VizProperty.Brightness),
        // Where each note sits between the speakers sets its colour.
        VizDrive(VizDriver.Width, VizProperty.Colour),
        silence = VizSilence.Still,
    )

    private val axis = NoteAxis()

    private var transform: ConstantQBars? = null
    private var stream: BassCutStream? = null
    private var windowLeft = FloatArray(0)
    private var windowRight = FloatArray(0)

    /** Red, green, blue and height per column, from the last transform. */
    internal var row = FloatArray(0)
        private set

    /** The row as the shader reads it: colours in the first line, heights in 16 bits in the second. */
    private var rowImage: PixelImage? = null
    private var fallbackImage: PixelImage? = null
    private var fallbackFresh = false

    // The page transformed once per 60 Hz frame; here once per sixtieth of a heard second.
    private var readClock = READ_PERIOD
    private var readDue = true

    /** How many transforms have run since the last reset. */
    internal var reads: Long = 0L
        private set

    /** Columns across the frame: one per dp, at most 1920. */
    internal var columns: Int = 0
        private set

    override fun advance(state: VizRenderState) {
        readClock = minOf(readClock + state.stepSeconds, 2f * READ_PERIOD)
        if (readClock >= READ_PERIOD * 0.999f) {
            readClock -= READ_PERIOD
            readDue = true
        }
    }

    /** Runs the transform for [frame] when one is due or the layout changed, on a frame [widthDp] wide. */
    private fun refresh(frame: SpectrumFrame, widthDp: Float) {
        val wanted = widthDp.roundToInt().coerceIn(1, MOST_COLUMNS)
        val history = frame.stereoHistory
        val rate = history?.sampleRate ?: transform?.sampleRate ?: DEFAULT_RATE
        val current = transform
        if (current == null || current.columns != wanted || current.sampleRate != rate) {
            val built = ConstantQBars(rate, wanted)
            transform = built
            stream = BassCutStream(rate, built.span)
            windowLeft = FloatArray(built.span)
            windowRight = FloatArray(built.span)
            row = FloatArray(wanted * 4)
            rowImage = PixelImage(wanted, 2)
            fallbackImage = null
            columns = wanted
            readDue = true
        }
        if (!readDue) return
        readDue = false
        val cqt = checkNotNull(transform)
        val centre = if (history == null || !frame.hasTimestamp) null else history.indexAt(frame.analysisRevision, frame.ptsMicros)
        if (history == null || centre == null) {
            row.fill(0f)
        } else {
            val filtered = checkNotNull(stream)
            filtered.advanceTo(history, frame.analysisRevision, centre + cqt.attackSize)
            filtered.latest(windowLeft, windowRight)
            // The page's stereo panner spreads a mono source over both sides at -3 dB each.
            val gain = if (history.sourceChannels(frame.analysisRevision) == 1) MONO_PAN else 1f
            cqt.transform(windowLeft, windowRight, gain, BAR_VOLUME, COLOUR_VOLUME, row)
        }
        reads++
        writeRow()
        fallbackFresh = false
    }

    private fun writeRow() {
        val image = rowImage ?: return
        val pixels = image.pixels
        for (column in 0 until columns) {
            val at = column * 4
            val red = (row[at] * 255f + 0.5f).toInt()
            val green = (row[at + 1] * 255f + 0.5f).toInt()
            val blue = (row[at + 2] * 255f + 0.5f).toInt()
            pixels[column] = OPAQUE or (red shl 16) or (green shl 8) or blue
            val height = ((row[at + 3] / HEIGHT_RANGE).coerceIn(0f, 1f) * 65_535f + 0.5f).toInt()
            pixels[columns + column] = OPAQUE or ((height shr 8) shl 16) or ((height and 0xFF) shl 8)
        }
        image.upload()
    }

    /** The ruler's height in pixels: the page's round(width / 80) * 2 of its own pixels, 48 at 1920. */
    private fun axisHeight(width: Float): Float =
        ((columns / 80f).roundToInt() * 2).toFloat() * width / columns.coerceAtLeast(1)

    /** Rows of one bar area: what the ruler leaves, halved. */
    private fun barHeight(width: Float, height: Float): Float =
        floor((height - axisHeight(width)) / 2f).coerceAtLeast(0f)

    override fun DrawScope.drawShader(program: ShaderProgram, state: VizRenderState) {
        refresh(state.frame, size.width / density)
        val image = rowImage ?: return
        program.child("uRow", image.image)
        program.uniform("uColumns", columns.toFloat())
        program.uniform("uBarHeight", barHeight(size.width, size.height))
        program.uniform("uLight", state.lightScale.coerceIn(0f, 1f))
        val brush = program.brush() ?: return
        drawRect(brush)
    }

    override fun DrawScope.drawFallback(state: VizRenderState) {
        drawRect(Color.Black)
        refresh(state.frame, size.width / density)
        val barHeight = barHeight(size.width, size.height)
        val image = fallbackImage ?: PixelImage(columns, FALLBACK_ROWS + 1).also { fallbackImage = it; fallbackFresh = false }
        if (!fallbackFresh) {
            writeFallback(image)
            fallbackFresh = true
        }
        val source = IntSize(columns, FALLBACK_ROWS)
        val bars = IntSize(size.width.roundToInt(), barHeight.roundToInt())
        if (bars.height > 0) {
            drawImage(image.image, IntOffset.Zero, source, IntOffset.Zero, bars, filterQuality = FilterQuality.Low)
            scale(1f, -1f, Offset(size.width / 2f, size.height / 2f)) {
                drawImage(image.image, IntOffset.Zero, source, IntOffset.Zero, bars, filterQuality = FilterQuality.Low)
            }
        }
        val middle = (size.height - 2f * barHeight).roundToInt()
        if (middle > 0) {
            drawImage(image.image, IntOffset(0, FALLBACK_ROWS), IntSize(columns, 1),
                IntOffset(0, barHeight.roundToInt()), IntSize(size.width.roundToInt(), middle), filterQuality = FilterQuality.Low)
        }
        val light = state.lightScale.coerceIn(0f, 1f)
        if (light < 1f) drawRect(Color.Black.copy(alpha = 1f - light))
    }

    /** The top bar area as [FALLBACK_ROWS] rows, base at the bottom, then the colour line. */
    private fun writeFallback(image: PixelImage) {
        val pixels = image.pixels
        for (y in 0 until FALLBACK_ROWS) {
            val ht = (FALLBACK_ROWS - y - 0.5f) / FALLBACK_ROWS
            for (column in 0 until columns) {
                val at = column * 4
                val h = row[at + 3]
                val mul = if (h <= ht) 0f else (h - ht) / (h + 0.0001f)
                pixels[y * columns + column] = colour(row[at] * mul, row[at + 1] * mul, row[at + 2] * mul)
            }
        }
        for (column in 0 until columns) {
            val at = column * 4
            pixels[FALLBACK_ROWS * columns + column] = colour(row[at], row[at + 1], row[at + 2])
        }
        image.upload()
    }

    private fun colour(red: Float, green: Float, blue: Float): Int =
        OPAQUE or ((red * 255f + 0.5f).toInt() shl 16) or ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (columns == 0) return
        with(axis) { drawAxis(barHeight(size.width, size.height), axisHeight(size.width)) }
    }

    override fun onReset() {
        stream?.reset()
        row.fill(0f)
        writeRow()
        fallbackFresh = false
        readClock = READ_PERIOD
        readDue = true
        reads = 0L
    }

    private companion object {
        /** The page read its analysers once per animation frame, sixty a second. */
        const val READ_PERIOD = 1f / 60f

        /** The page's bar knob, 19 dB. */
        const val BAR_VOLUME = 8.912509f

        /** The page's brightness knob, 25 dB. */
        const val COLOUR_VOLUME = 17.782795f

        /** The page's widest canvas. */
        const val MOST_COLUMNS = 1920

        /** Where a height is stored, 16 bits over 0 to this. At 16 a bar's top is 94 percent lit. */
        const val HEIGHT_RANGE = 16f

        /** A stereo panner's gain on each side for a mono source at the centre: cos(pi / 4). */
        const val MONO_PAN = 0.70710677f

        const val DEFAULT_RATE = 48_000
        const val FALLBACK_ROWS = 96
        const val OPAQUE = -0x1000000

        const val SOURCE = """
uniform shader uRow;
uniform float uColumns;
uniform float uBarHeight;
uniform float uLight;

// One column's colour at a height `ht` of its bar area, 1 at the top and just above 0 at the base,
// as the page drew a row: black above the bar's height, then fading up to full colour at the base.
// A negative `ht` is the colour line the page drew between the two bar areas, under the ruler.
float3 barAt(float column, float ht) {
    float3 colour = uRow.eval(float2(column + 0.5, 0.5)).rgb;
    float4 texel = uRow.eval(float2(column + 0.5, 1.5));
    float h = (floor(texel.r * 255.0 + 0.5) * 256.0 + floor(texel.g * 255.0 + 0.5)) / 65535.0 * 16.0;
    if (ht < 0.0) return colour;
    if (h <= ht) return float3(0.0);
    return colour * ((h - ht) / (h + 0.0001));
}

half4 main(float2 position) {
    float rows = floor(uResolution.y + 0.5);
    float y = floor(position.y);
    float ht = -1.0;
    if (uBarHeight > 0.0) {
        if (y < uBarHeight) {
            ht = (uBarHeight - y) / uBarHeight;
        } else if (y >= rows - uBarHeight) {
            ht = (uBarHeight - (rows - 1.0 - y)) / uBarHeight;
        }
    }
    // Columns are stretched over the frame; between two column centres the picture blends, as a
    // browser blends a canvas it shows larger than its own pixels.
    float at = position.x * uColumns / uResolution.x - 0.5;
    float first = clamp(floor(at), 0.0, uColumns - 1.0);
    float second = min(first + 1.0, uColumns - 1.0);
    float blend = clamp(at - floor(at), 0.0, 1.0);
    float3 colour = mix(barAt(first, ht), barAt(second, ht), blend);
    return half4(colour * uLight, 1.0);
}
"""
    }
}
