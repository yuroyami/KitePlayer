package io.github.yuroyami.kiteplayer.audioviz

import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a stream of audio samples into something that looks like a music player's spectrum.
 *
 * Feed it interleaved samples with [feed] as they go to the speakers. It mixes them to mono,
 * runs an FFT every [hop] samples, and publishes the result to [latest].
 *
 * The FFT is the easy half. What makes the picture look right is the shaping after it:
 * bars spaced by ear rather than evenly, a decibel scale, a fast rise with a slow fall, and
 * caps that hang for a moment before dropping. A raw FFT plotted directly looks like noise.
 *
 * Three things here are not about the spectrum at all, and they are what let a drawing tell a
 * ballad from a banger. [MoodTracker] places the present moment inside the range this song has
 * used, so the answer does not depend on how the track was mastered. [TempoTracker] finds the
 * beat and keeps a running position inside the bar. [Timbre] reads the key and the brightness.
 * All three run off numbers the FFT produced anyway.
 *
 * One thread feeds, any thread reads [latest]. There is no lock: each analysis publishes a
 * whole new [SpectrumFrame] and the reader either sees the old one or the new one.
 */
@AudioVizAuthoringApi
public class SpectrumAnalyzer(
    /** Points per FFT. 2048 at 48 kHz is about 43 ms of sound, which is the usual compromise. */
    public val fftSize: Int = 2048,
    /** How many bars to draw. */
    public val bandCount: Int = 48,
    /** How many samples of new audio trigger the next analysis. Sets the refresh rate. */
    public val hop: Int = 512,
    /** Points in the oscilloscope trace. */
    public val scopePoints: Int = 256,
    private val sampleRate: Int = 48_000,
    /** The lowest bar's frequency. Below this is mostly rumble and room noise. */
    private val minHz: Float = 35f,
    /** The highest bar's frequency, capped to what the rate can carry. */
    private val maxHz: Float = 16_000f,
    /** Anything quieter than this reads as an empty bar. */
    private val floorDb: Float = -72f,
    /** How fast a bar rises towards a louder reading, 0 to 1. */
    private val attack: Float = 0.6f,
    /** How fast a bar falls towards a quieter one, 0 to 1. Slower than [attack] on purpose. */
    private val release: Float = 0.13f,
    /** How many analyses a cap hangs at its high point before it starts to fall. */
    private val peakHoldFrames: Int = 14,
    /** How much speed a falling cap gains per analysis. */
    private val peakGravity: Float = 0.0035f,
    /** How much of the beat flash survives each analysis. Lower makes a sharper flash. */
    private val pulseDecay: Float = 0.86f,
    /** Points in each channel of the stereo trace, for a drawing that plots left against right. */
    public val stereoPoints: Int = 512,
) {
    init {
        require(bandCount in 1..512) { "bandCount must be 1..512, was $bandCount" }
        require(hop in 1..fftSize) { "hop must be 1..$fftSize, was $hop" }
        require(scopePoints in 2..fftSize) { "scopePoints must be 2..$fftSize, was $scopePoints" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    }

    private val fft = Fft(fftSize)
    private val window = Fft.hannWindow(fftSize)
    private val usableBins = fftSize / 2
    private val stereoLength = stereoPoints.coerceIn(2, fftSize)

    /**
     * A second, much longer FFT used only for the lowest bars.
     *
     * At 2048 points a bin is 23 Hz wide, so every bar below about 100 Hz shares its bins with
     * its neighbours and they all move together in a block. Four times the points is four times
     * the resolution exactly where the ear has the most, and it costs little because it only has
     * to run every fourth analysis: bass does not change fast enough to need more.
     */
    private val bassFftSize = (fftSize * 4).coerceAtMost(16_384)
    private val bassFft = Fft(bassFftSize)
    private val bassWindow = Fft.hannWindow(bassFftSize)
    private val bassUsableBins = bassFftSize / 2
    private val bassRing = FloatArray(bassFftSize)
    private var bassWriteIndex = 0
    private val bassReal = FloatArray(bassFftSize)
    private val bassImaginary = FloatArray(bassFftSize)
    private val bassMagnitudes = FloatArray(bassUsableBins)
    // Starts primed so the lowest bars are right on the very first analysis rather than after four.
    private var sinceBassAnalysis = BASS_EVERY

    // The newest fftSize mono samples, oldest overwritten first.
    private val ring = FloatArray(fftSize)
    private val ringLeft = FloatArray(fftSize)
    private val ringRight = FloatArray(fftSize)
    private var writeIndex = 0
    private var samplesSinceAnalysis = 0
    private var samplesSeen = 0L
    private var startMicros = -1L

    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val magnitudes = FloatArray(usableBins)

    // Carried between analyses: this is where the smoothing and the caps live.
    private val smoothed = FloatArray(bandCount)
    private val peaks = FloatArray(bandCount)
    private val peakHold = IntArray(bandCount)
    private val peakSpeed = FloatArray(bandCount)
    private val relative = FloatArray(bandCount)
    private val quietScratch = FloatArray(bandCount)
    private val sorted = FloatArray(bandCount)
    private var smoothBass = 0f
    private var smoothMid = 0f
    private var smoothTreble = 0f
    private var kickPulse = 0f
    private var snarePulse = 0f
    private var hatPulse = 0f
    private var pulse = 0f
    private var smoothWidth = 0f

    private val analysesPerSecond = sampleRate.toFloat() / hop
    private val secondsPerAnalysis = hop.toFloat() / sampleRate

    private val beatDetector = BeatDetector(usableBins, sampleRate, fftSize)
    private val tempo = TempoTracker(analysesPerSecond)
    private val timbre = Timbre(usableBins, sampleRate, fftSize)
    private val moodTracker = MoodTracker(analysesPerSecond)
    private val loudnessWeights = Loudness.weightsFor(usableBins, sampleRate, fftSize)

    private val levelRange = RunningRange(0.35f, 0.2f, 0f, 0.6f)
    private val bassRange = RunningRange(0.35f, 0.2f, 0f, 0.6f)
    private val midRange = RunningRange(0.35f, 0.2f, 0f, 0.6f)
    private val trebleRange = RunningRange(0.35f, 0.2f, 0f, 0.6f)

    /** First and last FFT bin of each bar, spaced logarithmically because hearing is. */
    private val bandStart = IntArray(bandCount)
    private val bandEnd = IntArray(bandCount)

    /** The same bars against the long FFT, for the ones low enough to need it. */
    private val bassBandStart = IntArray(bandCount)
    private val bassBandEnd = IntArray(bandCount)
    private val bassBandValue = FloatArray(bandCount)
    private var bassBandCount = 0

    private val binsPerHz = fftSize.toFloat() / sampleRate
    private val bassEndBin = binFor(250f)
    private val midEndBin = binFor(2_000f)

    init {
        val usableTop = minOf(maxHz, sampleRate / 2f * 0.95f)
        val ratio = ln(usableTop / minHz)
        val lastBin = usableBins - 1
        val bassBinsPerHz = bassFftSize.toFloat() / sampleRate
        val lastBassBin = bassUsableBins - 1
        for (band in 0 until bandCount) {
            val low = minHz * exp(ratio * band / bandCount)
            val high = minHz * exp(ratio * (band + 1) / bandCount)
            val start = (low * binsPerHz).toInt().coerceIn(1, lastBin)
            // At least one bin wide, or the lowest bars would all read the same value.
            val end = (high * binsPerHz).toInt().coerceIn(start + 1, lastBin + 1)
            bandStart[band] = start
            bandEnd[band] = end

            if (high < FINE_BASS_TOP_HZ) {
                val bassStart = (low * bassBinsPerHz).toInt().coerceIn(1, lastBassBin)
                val bassEnd = (high * bassBinsPerHz).toInt().coerceIn(bassStart + 1, lastBassBin + 1)
                bassBandStart[band] = bassStart
                bassBandEnd[band] = bassEnd
                bassBandCount = band + 1
            }
        }
    }

    private fun binFor(hz: Float): Int = (hz * binsPerHz).toInt().coerceIn(1, usableBins - 1)

    /** The newest analysis. Read it from the drawing thread as often as you like. */
    @Volatile
    public var latest: SpectrumFrame = SpectrumFrame.silent(bandCount, scopePoints)
        private set

    /**
     * Called for every analysis, on the feeding thread, before [latest] changes.
     *
     * One [feed] can produce several analyses when it carries more than [hop] samples, so a
     * consumer that wants all of them (a [SpectrumTimeline], for instance) reads them here rather
     * than polling [latest].
     */
    public var onAnalysis: ((SpectrumFrame) -> Unit)? = null

    /**
     * Takes [frames] sample frames of [channels]-channel interleaved audio and analyses whatever
     * it can. [ptsMicros] is the media timestamp of the first frame, or a negative number when
     * there is none: it is copied onto the results so a drawing can be lined up with the sound.
     */
    public fun feed(interleaved: FloatArray, frames: Int, channels: Int, ptsMicros: Long = -1L) {
        require(channels > 0) { "channels must be positive, was $channels" }
        if (frames <= 0) return
        if (startMicros < 0 && ptsMicros >= 0) {
            startMicros = ptsMicros - samplesSeen * 1_000_000L / sampleRate
        }

        for (frame in 0 until frames) {
            var mono = 0f
            val base = frame * channels
            for (channel in 0 until channels) mono += interleaved[base + channel]
            mono /= channels
            ring[writeIndex] = mono
            ringLeft[writeIndex] = interleaved[base]
            ringRight[writeIndex] = if (channels > 1) interleaved[base + 1] else interleaved[base]
            writeIndex = (writeIndex + 1) % fftSize
            bassRing[bassWriteIndex] = mono
            bassWriteIndex = (bassWriteIndex + 1) % bassFftSize
            samplesSeen++
            if (++samplesSinceAnalysis >= hop) {
                samplesSinceAnalysis = 0
                analyse(channels)
            }
        }
    }

    /** Drops every carried value. Call it on a seek so old bars do not fall into the new music. */
    public fun reset() {
        ring.fill(0f)
        ringLeft.fill(0f)
        ringRight.fill(0f)
        bassRing.fill(0f)
        bassBandValue.fill(0f)
        smoothed.fill(0f)
        peaks.fill(0f)
        peakHold.fill(0)
        peakSpeed.fill(0f)
        relative.fill(0f)
        smoothBass = 0f
        smoothMid = 0f
        smoothTreble = 0f
        pulse = 0f
        kickPulse = 0f
        snarePulse = 0f
        hatPulse = 0f
        smoothWidth = 0f
        beatDetector.reset()
        tempo.reset()
        timbre.reset()
        moodTracker.reset()
        levelRange.reset()
        bassRange.reset()
        midRange.reset()
        trebleRange.reset()
        writeIndex = 0
        bassWriteIndex = 0
        sinceBassAnalysis = BASS_EVERY
        samplesSinceAnalysis = 0
        samplesSeen = 0
        startMicros = -1L
        latest = SpectrumFrame.silent(bandCount, scopePoints)
    }

    private fun analyse(channels: Int) {
        // Unwrap the ring into the FFT input, oldest sample first, windowed on the way. The
        // stereo sums ride along in the same pass rather than costing a second one.
        var read = writeIndex
        var sumOfSquares = 0.0
        var sumLeftRight = 0.0
        var sumLeftLeft = 0.0
        var sumRightRight = 0.0
        for (index in 0 until fftSize) {
            val sample = ring[read]
            real[index] = sample * window[index]
            imaginary[index] = 0f
            sumOfSquares += sample.toDouble() * sample
            val left = ringLeft[read].toDouble()
            val right = ringRight[read].toDouble()
            sumLeftRight += left * right
            sumLeftLeft += left * left
            sumRightRight += right * right
            read++
            if (read == fftSize) read = 0
        }

        fft.forward(real, imaginary)

        // Two for the window's coherent gain, two for folding the negative frequencies back in.
        val scale = 4f / (fftSize * Fft.HANN_COHERENT_GAIN * 2f)
        for (bin in 0 until usableBins) {
            magnitudes[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * scale
        }

        if (bassBandCount > 0 && ++sinceBassAnalysis >= BASS_EVERY) {
            sinceBassAnalysis = 0
            analyseBass()
        }

        var loudestBand = 0f
        for (band in 0 until bandCount) {
            var loudest = 0f
            for (bin in bandStart[band] until bandEnd[band]) {
                if (magnitudes[bin] > loudest) loudest = magnitudes[bin]
            }
            // The lowest bars take the long FFT's answer, which can actually tell them apart.
            val target = if (band < bassBandCount) bassBandValue[band] else normalise(loudest)

            val current = smoothed[band]
            smoothed[band] = current + (target - current) * if (target > current) attack else release
            if (smoothed[band] > loudestBand) loudestBand = smoothed[band]

            if (smoothed[band] >= peaks[band]) {
                peaks[band] = smoothed[band]
                peakHold[band] = peakHoldFrames
                peakSpeed[band] = 0f
            } else if (peakHold[band] > 0) {
                peakHold[band]--
            } else {
                peakSpeed[band] += peakGravity
                peaks[band] = (peaks[band] - peakSpeed[band]).coerceAtLeast(smoothed[band])
            }
        }

        smoothBass = follow(smoothBass, loudestBetween(1, bassEndBin))
        smoothMid = follow(smoothMid, loudestBetween(bassEndBin, midEndBin))
        smoothTreble = follow(smoothTreble, loudestBetween(midEndBin, usableBins))

        val beat = beatDetector.feed(magnitudes, usableBins)
        pulse = maxOf(pulse * pulseDecay, beat)
        kickPulse = maxOf(beatDetector.kick, kickPulse * exp(-secondsPerAnalysis / 0.18f))
        snarePulse = maxOf(beatDetector.snare, snarePulse * exp(-secondsPerAnalysis / 0.12f))
        hatPulse = maxOf(beatDetector.hat, hatPulse * exp(-secondsPerAnalysis / 0.055f))

        val decibels = Loudness.decibels(magnitudes, loudnessWeights, usableBins)
        timbre.feed(magnitudes, secondsPerAnalysis)
        tempo.feed(beatDetector.novelty, beatDetector.strength, secondsPerAnalysis)
        // The quiet quarter of the spectrum, which is where the bottom of the bars' range sits.
        smoothed.copyInto(quietScratch)
        quietScratch.sort()
        moodTracker.feed(
            loudnessDecibels = decibels,
            loudestBand = loudestBand,
            quietBand = quietScratch[bandCount / 4],
            onsetStrength = beatDetector.strength,
            beatConfidence = tempo.confidence,
            deltaSeconds = secondsPerAnalysis,
        )

        for (band in 0 until bandCount) relative[band] = moodTracker.placeBand(smoothed[band])
        relative.copyInto(sorted)
        sorted.sort()

        val correlation =
            if (channels < 2 || sumLeftLeft <= 1e-12 || sumRightRight <= 1e-12) 1.0
            else sumLeftRight / sqrt(sumLeftLeft * sumRightRight)
        // Identical channels give 1 and fully out of phase gives -1, so this reads 0 for mono
        // and 1 for the widest thing a stereo file can hold.
        val width = ((1.0 - correlation) * 0.5).toFloat().coerceIn(0f, 1f)
        smoothWidth += (width - smoothWidth) * 0.08f

        val rootMeanSquare = sqrt(sumOfSquares / fftSize).toFloat()
        val level = normalise(rootMeanSquare)

        // The Hann window is centred here. Dating it at its oldest sample would put the picture
        // half an FFT window ahead of the sound (23 ms at 44.1 kHz).
        val windowPts =
            if (startMicros < 0) -1L
            else startMicros + (samplesSeen - fftSize / 2).coerceAtLeast(0) * 1_000_000L / sampleRate

        // The tempo tracker advances at the newest sample. Publish its phase at the same
        // centre timestamp as the spectrum, rather than mixing two time origins in one frame.
        val phaseLead = fftSize / (2f * sampleRate) * tempo.bpm / 60f
        fun phaseAtCentre(phase: Float, beats: Float): Float {
            val shifted = phase - phaseLead / beats
            return shifted - kotlin.math.floor(shifted)
        }
        val beatPhase = phaseAtCentre(tempo.beatPhase, 1f)
        val stereoStart = triggerStart(stereoLength)
        val frame = SpectrumFrame(
            ptsMicros = windowPts,
            bands = smoothed.copyOf(),
            peaks = peaks.copyOf(),
            scope = readTriggeredScope(),
            level = level,
            bass = smoothBass,
            mid = smoothMid,
            treble = smoothTreble,
            beat = beat,
            pulse = pulse,
            bandsRel = relative.copyOf(),
            bandsSorted = sorted.copyOf(),
            levelRel = levelRange.place(level, secondsPerAnalysis),
            bassRel = bassRange.place(smoothBass, secondsPerAnalysis),
            midRel = midRange.place(smoothMid, secondsPerAnalysis),
            trebleRel = trebleRange.place(smoothTreble, secondsPerAnalysis),
            kick = beatDetector.kick,
            snare = beatDetector.snare,
            hat = beatDetector.hat,
            kickPulse = kickPulse,
            snarePulse = snarePulse,
            hatPulse = hatPulse,
            onsetStrength = beatDetector.strength,
            novelty = beatDetector.novelty,
            energy = moodTracker.energy,
            density = moodTracker.density,
            mood = moodTracker.mood,
            loudShort = moodTracker.loudShort,
            loudLong = moodTracker.loudLong,
            trend = moodTracker.trend,
            drop = moodTracker.drop,
            dropPulse = moodTracker.dropPulse,
            breakdown = moodTracker.breakdown,
            bpm = tempo.bpm,
            beatConfidence = tempo.confidence,
            beatPhase = beatPhase,
            barPhase = phaseAtCentre(tempo.alignedBarPhase(), 4f),
            phrasePhase = phaseAtCentre(tempo.alignedPhrasePhase(), 16f),
            beatInSeconds = if (tempo.bpm > 0f) (1f - beatPhase) * 60f / tempo.bpm else -1f,
            chroma = timbre.chroma.copyOf(),
            keyHue = timbre.keyHue,
            keyConfidence = timbre.keyConfidence,
            centroid = timbre.centroid,
            flatness = timbre.flatness,
            width = smoothWidth,
            scopeLeft = readChannel(ringLeft, stereoStart),
            scopeRight = readChannel(ringRight, stereoStart),
        )
        onAnalysis?.invoke(frame)
        latest = frame
    }

    /** The long FFT, run for the low bars only. */
    private fun analyseBass() {
        var read = bassWriteIndex
        for (index in 0 until bassFftSize) {
            bassReal[index] = bassRing[read] * bassWindow[index]
            bassImaginary[index] = 0f
            read++
            if (read == bassFftSize) read = 0
        }
        bassFft.forward(bassReal, bassImaginary)
        val scale = 4f / (bassFftSize * Fft.HANN_COHERENT_GAIN * 2f)
        for (bin in 0 until bassUsableBins) {
            bassMagnitudes[bin] =
                sqrt(bassReal[bin] * bassReal[bin] + bassImaginary[bin] * bassImaginary[bin]) * scale
        }
        for (band in 0 until bassBandCount) {
            var loudest = 0f
            for (bin in bassBandStart[band] until bassBandEnd[band]) {
                if (bassMagnitudes[bin] > loudest) loudest = bassMagnitudes[bin]
            }
            bassBandValue[band] = normalise(loudest)
        }
    }

    /**
     * The waveform, started at a rising zero crossing so the trace holds still.
     *
     * Taken straight from the ring, the same note drawn twice starts at a different point in its
     * cycle each time and the picture swims. Every oscilloscope ever built solves this the same
     * way: wait for the signal to cross zero going upward, and start drawing there.
     */
    private fun readTriggeredScope(): FloatArray {
        val out = FloatArray(scopePoints)
        val start = triggerStart(scopePoints)
        for (point in 0 until scopePoints) {
            out[point] = ring[((start + point) % fftSize + fftSize) % fftSize]
        }
        return out
    }

    /**
     * Where a trace of [points] samples should start: the first rising zero crossing of the mix
     * that still leaves room for the whole trace, or simply the newest samples when there is none.
     * The answer is kept unwrapped, so it can be below zero. That is fine: every read wraps.
     */
    private fun triggerStart(points: Int): Int {
        val newest = writeIndex
        // Room to look for a crossing, and room to read a whole trace after it.
        val searchLength = (fftSize - points).coerceAtMost(fftSize / 2)
        val searchFrom = newest - points - searchLength
        var previous = ring[((searchFrom - 1) % fftSize + fftSize) % fftSize]
        for (step in 0 until searchLength) {
            val sample = ring[((searchFrom + step) % fftSize + fftSize) % fftSize]
            if (previous <= 0f && sample > 0f) return searchFrom + step
            previous = sample
        }
        return newest - points
    }

    /**
     * The newest samples of one channel, started where the mix crosses zero on the way up, like the
     * scope. Music that repeats then holds its shape from one frame to the next, the way it does on a
     * real vectorscope, instead of the figure jumping every frame.
     */
    private fun readChannel(from: FloatArray, start: Int): FloatArray {
        val out = FloatArray(stereoLength)
        for (point in 0 until stereoLength) {
            out[point] = from[((start + point) % fftSize + fftSize) % fftSize]
        }
        return out
    }

    private fun loudestBetween(fromBin: Int, toBin: Int): Float {
        var loudest = 0f
        for (bin in fromBin until toBin) {
            if (magnitudes[bin] > loudest) loudest = magnitudes[bin]
        }
        return normalise(loudest)
    }

    /** A magnitude as a 0..1 position on the decibel scale between [floorDb] and full scale. */
    private fun normalise(magnitude: Float): Float =
        ((20f * log10(magnitude + 1e-9f) - floorDb) / -floorDb).coerceIn(0f, 1f)

    private fun follow(current: Float, target: Float): Float =
        current + (target - current) * if (target > current) attack else release

    private companion object {
        /** Bars whose top edge is below this get the long FFT's resolution. */
        const val FINE_BASS_TOP_HZ = 200f

        /** How many normal analyses pass between long ones. */
        const val BASS_EVERY = 4
    }
}
