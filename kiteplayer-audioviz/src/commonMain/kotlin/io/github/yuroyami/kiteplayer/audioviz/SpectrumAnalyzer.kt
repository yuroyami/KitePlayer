package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import io.github.yuroyami.kiteplayer.audioviz.viz.StereoHistory
import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Causal, sample-timed audio analysis with per-channel power and one shared display gain.
 *
 * Feed sanitised or borrowed interleaved samples from one analysis thread. Short spectra use a
 * Hann window, per-channel power averaging and fractional ERB integration. Programme loudness
 * uses an independently timed 400 ms K-weighted channel sum. All energy display drivers share
 * its bounded causal or fixed-song reference and their fast/slow envelopes advance in media time.
 * Mono mixing is only for the decorative scope; opposite channel polarities retain their energy.
 *
 * Each analysis publishes new snapshot storage. Readers may retain an old frame while analysis
 * continues. The recognition helpers are separate from the calibrated power and display paths.
 */
@AudioVizAuthoringApi
public class SpectrumAnalyzer(
    /** Points per FFT, or zero for a power of two spanning at most 50 ms. */
    fftSize: Int = 0,
    /** How many bars to draw. */
    public val bandCount: Int = 40,
    /** New samples per analysis, or zero for 10 ms rounded to whole samples. */
    hop: Int = 0,
    /** Requested scope points, limited to the resolved FFT size. */
    scopePoints: Int = 256,
    private val sampleRate: Int = 48_000,
    /** Points in each channel of the stereo trace, for a drawing that plots left against right. */
    stereoPoints: Int = 512,
) {
    public val fftSize: Int = if (fftSize == 0) transientFftSize(sampleRate) else fftSize
    public val hop: Int = if (hop == 0) (sampleRate / 100.0).roundToInt().coerceAtLeast(1) else hop
    public val scopePoints: Int = minOf(scopePoints, this.fftSize)
    public val stereoPoints: Int = minOf(stereoPoints, this.fftSize)

    init {
        require(this.fftSize in 4..32_768) { "fftSize must be 4..32768" }
        require(bandCount in 1..512) { "bandCount must be 1..512, was $bandCount" }
        require(this.hop in 1..this.fftSize) { "hop must fit the analysis window" }
        require(this.scopePoints in 2..this.fftSize) { "scopePoints must contain at least two points" }
        require(this.stereoPoints in 2..this.fftSize) { "stereoPoints must contain at least two points" }
        require(sampleRate in 1_000..768_000) { "supported sample rates are 1000..768000 Hz" }
    }

    private val spectralPower = SpectralPower(this.fftSize, sampleRate, bandCount)
    private var channelRings = emptyArray<FloatArray>()
    private var inputFormat: AudioFormat? = null
    private var sanitisedSamples = 0L
    /** Number of non-finite or beyond-headroom samples sanitised by direct PCM input. */
    public val sanitizedSamples: Long get() = sanitisedSamples
    private val usableBins = this.fftSize / 2
    private val stereoLength = this.stereoPoints

    private var programmePower: ProgrammePower? = null
    private val displayScale = DisplayScale()
    private val bandEnvelopes = Array(bandCount) { EnergyEnvelope() }
    private val overallEnvelope = EnergyEnvelope()
    private val bassEnvelope = EnergyEnvelope()
    private val midEnvelope = EnergyEnvelope()
    private val trebleEnvelope = EnergyEnvelope()

    // The newest fftSize mono samples, oldest overwritten first.
    private val ring = FloatArray(this.fftSize)
    private val ringLeft = FloatArray(this.fftSize)
    private val ringRight = FloatArray(this.fftSize)
    private var writeIndex = 0
    private var samplesSinceAnalysis = 0
    private var samplesSeen = 0L
    private var startMicros: Long? = null
    internal var generation: Generation = Generation.Initial
    internal var analysisRevision: Long = 0L

    private val magnitudes = FloatArray(usableBins)

    /** The last seconds of the input as a stereo pair, for a drawing that analyses raw samples. */
    internal val stereoHistory = StereoHistory(sampleRate)

    private var kickPulse = 0f
    private var snarePulse = 0f
    private var hatPulse = 0f
    private var pulse = 0f
    private var smoothWidth = 0f

    private val analysesPerSecond = sampleRate.toFloat() / this.hop
    private val secondsPerAnalysis = this.hop.toFloat() / sampleRate

    private val beatDetector = BeatDetector(usableBins, sampleRate, this.fftSize, this.hop)
    private val tempo = TempoTracker(analysesPerSecond)
    private val timbre = Timbre(usableBins, sampleRate, this.fftSize)
    private val keyTracker = KeyTracker(sampleRate)
    private val sections = SectionDetector(bandCount, secondsPerAnalysis.toDouble())
    private val moodTracker = MoodTracker(analysesPerSecond)
    /** The newest analysis. Read it from the drawing thread as often as you like. */
    @Volatile
    public var latest: SpectrumFrame = SpectrumFrame.silent(bandCount, this.scopePoints)
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
     * it can. [ptsMicros] is the media timestamp of the first frame, or null when unknown.
     * Zero and negative positions are valid timestamps.
     */
    public fun feed(interleaved: FloatArray, frames: Int, channels: Int, ptsMicros: Long? = null) {
        feed(interleaved, frames, AudioFormat(sampleRate, channels, SampleFormat.F32, ChannelLayout.Unknown), ptsMicros)
    }

    /**
     * PCM with explicit layout metadata. Its sample rate must match this analyser. Reset before
     * changing channels or layout; a different sample rate needs a new analyser. Inputs are floats
     * regardless of [AudioFormat.sampleFormat]. Non-finite values become zero and finite values
     * are limited to amplitude +/-16, with each change counted in [sanitizedSamples].
     */
    public fun feed(interleaved: FloatArray, frames: Int, format: AudioFormat, ptsMicros: Long? = null) {
        val channels = format.channels
        require(channels in 1..64) { "channels must be 1..64, was $channels" }
        require(format.sampleRate == sampleRate) { "sample rate does not match this analyser" }
        require(frames >= 0 && frames <= interleaved.size / channels) { "sample frame count does not fit input" }
        if (frames == 0) return
        require(inputFormat == null || inputFormat == format) { "reset before changing the audio format" }
        if (inputFormat == null) programmePower = ProgrammePower(format)
        inputFormat = format
        if (channelRings.size != channels) channelRings = Array(channels) { FloatArray(fftSize) }
        if (startMicros == null && ptsMicros != null) {
            startMicros = ptsMicros - samplesSeen * 1_000_000L / sampleRate
        }

        for (frame in 0 until frames) {
            var mono = 0f
            val base = frame * channels
            for (channel in 0 until channels) {
                val input = interleaved[base + channel]
                val safe = if (input.isFinite()) input.coerceIn(-16f, 16f) else 0f
                if (safe != input) sanitisedSamples++
                channelRings[channel][writeIndex] = safe
                programmePower?.addChannel(channel, safe)
                mono += safe
            }
            programmePower?.endFrame()
            keyTracker.push(channelRings[0][writeIndex], channelRings[if (channels > 1) 1 else 0][writeIndex])
            mono /= channels
            ring[writeIndex] = mono
            ringLeft[writeIndex] = channelRings[0][writeIndex]
            ringRight[writeIndex] = channelRings[if (channels > 1) 1 else 0][writeIndex]
            writeIndex = (writeIndex + 1) % fftSize
            samplesSeen++
            if (++samplesSinceAnalysis >= hop) {
                samplesSinceAnalysis = 0
                analyse(channels)
            }
        }
        stereoHistory.write(interleaved, frames, channels, startMicros, analysisRevision)
    }

    /**
     * A complete-song reference in linear K-weighted programme power. Positive finite values
     * transition the one shared gain over two media seconds. Null resumes the causal reference.
     * Call on the feeding thread. Reset retains this value for seeks; clear it for another song.
     */
    public fun setSongReferencePower(power: Double?) {
        displayScale.setReference(power)
    }

    /** Drops carried values and advances local continuity. Pair with [SpectrumTimeline.clear]. */
    public fun reset() {
        reset(generation, analysisRevision + 1L)
    }

    /**
     * Resets into an explicitly supplied timeline identity. Call on the feeding thread, then
     * publish into a timeline with matching [SpectrumTimeline.generation] and
     * [SpectrumTimeline.revision]. This does not itself reset that timeline.
     */
    public fun reset(generation: Generation, analysisRevision: Long) {
        this.generation = generation
        this.analysisRevision = analysisRevision
        inputFormat = null
        programmePower = null
        displayScale.reset()
        bandEnvelopes.forEach { it.reset() }
        overallEnvelope.reset()
        bassEnvelope.reset()
        midEnvelope.reset()
        trebleEnvelope.reset()
        channelRings.forEach { it.fill(0f) }
        ring.fill(0f)
        ringLeft.fill(0f)
        ringRight.fill(0f)
        pulse = 0f
        kickPulse = 0f
        snarePulse = 0f
        hatPulse = 0f
        smoothWidth = 0f
        beatDetector.reset()
        tempo.reset()
        timbre.reset()
        keyTracker.reset()
        sections.reset()
        moodTracker.reset()
        writeIndex = 0
        samplesSinceAnalysis = 0
        samplesSeen = 0
        startMicros = null
        stereoHistory.reset()
        latest = SpectrumFrame.silent(bandCount, scopePoints)
    }

    private fun analyse(channels: Int) {
        // Unweighted time-domain energy and paired stereo statistics. Spectral measurement below
        // keeps channels separate through the transform, so opposite polarities retain power.
        var read = writeIndex
        var sumLeftRight = 0.0
        var sumLeftLeft = 0.0
        var sumRightRight = 0.0
        for (index in 0 until fftSize) {
            val left = ringLeft[read].toDouble()
            val right = ringRight[read].toDouble()
            sumLeftRight += left * right
            sumLeftLeft += left * left
            sumRightRight += right * right
            read++
            if (read == fftSize) read = 0
        }

        spectralPower.measure(channelRings, writeIndex)
        for (bin in 0 until usableBins) {
            magnitudes[bin] = spectralPower.toneAmplitude(bin)
        }

        val programme = checkNotNull(programmePower)
        val delta = hop.toDouble() / sampleRate
        displayScale.advance(programme.meanSquare, programme.digitalSilence, delta)
        var saturated = 0
        fun advance(envelope: EnergyEnvelope, power: Double) {
            val normalised = power * displayScale.gain
            if (normalised > 1.0) saturated++
            envelope.advance(normalised, delta)
        }
        // A padded warmup window is not a measurement and cannot drive a false opening attack.
        val ready = samplesSeen >= fftSize
        for (band in 0 until bandCount) {
            advance(bandEnvelopes[band], if (ready) spectralPower.bands[band].toDouble() else 0.0)
        }
        advance(overallEnvelope, if (ready) spectralPower.totalPower.toDouble() else 0.0)
        advance(bassEnvelope, if (ready) spectralPower.integratedPower(0.0, 250.0) else 0.0)
        advance(midEnvelope, if (ready) spectralPower.integratedPower(250.0, 2_000.0) else 0.0)
        advance(trebleEnvelope, if (ready) spectralPower.integratedPower(2_000.0, sampleRate / 2.0) else 0.0)
        val drivers = EnergyDrivers(
            overallEnvelope.snapshot(), bassEnvelope.snapshot(), midEnvelope.snapshot(), trebleEnvelope.snapshot(),
            Array(bandCount) { bandEnvelopes[it].snapshot() }, displayScale.referencePower, displayScale.gain,
            displayScale.gainLimited, displayScale.handingOver, saturated,
        )
        val smoothed = FloatArray(bandCount) { drivers.band(it).fast }
        val peaks = FloatArray(bandCount) { drivers.band(it).peak }
        val sorted = smoothed.copyOf().also { it.sort() }

        val detectedBeat = if (ready) beatDetector.feed(magnitudes, usableBins) else 0f
        // The strength a detection carries, whatever its confidence. The records keep this.
        fun energy(detected: Float, power: Double): Float =
            if (ready && detected > 0f) powerHeight(power * displayScale.gain).toFloat() else 0f
        // What a picture answers: the same strength, but only where the detector has support.
        fun hit(kind: AudioEventKind, strength: Float): Float =
            if (beatDetector.confidence(kind) >= AudioDetection.HIT_CONFIDENCE) strength else 0f
        val beatEnergy = energy(detectedBeat, spectralPower.totalPower.toDouble())
        val kickEnergy = energy(beatDetector.kick, spectralPower.integratedPower(40.0, 150.0))
        val snareEnergy = energy(beatDetector.snare,
            spectralPower.integratedPower(150.0, 300.0) + spectralPower.integratedPower(1_000.0, 5_000.0))
        val hatEnergy = energy(beatDetector.hat, spectralPower.integratedPower(6_000.0, 16_000.0))
        val beat = hit(AudioEventKind.Onset, beatEnergy)
        val kick = hit(AudioEventKind.LowTransient, kickEnergy)
        val snare = hit(AudioEventKind.BodyTransient, snareEnergy)
        val hat = hit(AudioEventKind.HighTransient, hatEnergy)
        pulse = maxOf(pulse * exp(-secondsPerAnalysis / 0.07f), beat)
        kickPulse = maxOf(kick, kickPulse * exp(-secondsPerAnalysis / 0.18f))
        snarePulse = maxOf(snare, snarePulse * exp(-secondsPerAnalysis / 0.12f))
        hatPulse = maxOf(hat, hatPulse * exp(-secondsPerAnalysis / 0.055f))

        timbre.feed(magnitudes)
        tempo.feed(if (ready) beatDetector.novelty else 0f, if (ready) beatDetector.strength else 0f, secondsPerAnalysis)
        moodTracker.feed(
            fastEnergy = drivers.overall.fast,
            slowEnergy = drivers.overall.slow,
            onsetStrength = beatDetector.strength,
            beatConfidence = if (tempo.usable) tempo.confidence else 0f,
            deltaSeconds = secondsPerAnalysis,
        )

        val correlation =
            if (channels < 2 || sumLeftLeft <= 1e-12 || sumRightRight <= 1e-12) 1.0
            else sumLeftRight / sqrt(sumLeftLeft * sumRightRight)
        // Identical channels give 1 and fully out of phase gives -1, so this reads 0 for mono
        // and 1 for the widest thing a stereo file can hold.
        val width = ((1.0 - correlation) * 0.5).toFloat().coerceIn(0f, 1f)
        smoothWidth += (width - smoothWidth) * (1 - exp(-secondsPerAnalysis / 0.12f))

        // The Hann window is centred here. Dating it at its oldest sample would put the picture
        // half an FFT window ahead of the sound (23 ms at 44.1 kHz).
        val windowPts = startMicros?.let {
            it + (samplesSeen - fftSize / 2).coerceAtLeast(0) * 1_000_000L / sampleRate
        }

        val availableMicros = startMicros?.let { it + samplesSeen * 1_000_000L / sampleRate }
        keyTracker.analyse(availableMicros)
        val detections = if (!ready || windowPts == null) null else {
            val available = checkNotNull(startMicros) + samplesSeen * 1_000_000L / sampleRate
            // Causal growth responds to new input, not the older spectral centre. Estimate the
            // attack within the newest hop; availability still includes the whole consumed window.
            val eventReference = available - hop * 500_000L / sampleRate
            val events = ArrayList<AudioDetection>(5)
            fun record(kind: AudioEventKind, detected: Boolean, strength: Float) {
                if (detected) events.add(AudioDetection(kind, eventReference, available, strength,
                    beatDetector.confidence(kind), beatDetector.surprise(kind)))
            }
            record(AudioEventKind.Onset, detectedBeat > 0f, beatEnergy)
            record(AudioEventKind.LowTransient, beatDetector.kick > 0f, kickEnergy)
            record(AudioEventKind.BodyTransient, beatDetector.snare > 0f, snareEnergy)
            record(AudioEventKind.HighTransient, beatDetector.hat > 0f, hatEnergy)
            record(AudioEventKind.EnergyRise, moodTracker.drop, drivers.overall.fast)
            AudioDetections(available, eventReference, events.toTypedArray())
        }

        // The tempo tracker advances at the newest sample. Publish its phase at the same
        // centre timestamp as the spectrum, rather than mixing two time origins in one frame.
        val phaseLead = fftSize / (2f * sampleRate) * tempo.bpm / 60f
        fun phaseAtCentre(phase: Float, beats: Float): Float {
            val shifted = phase - phaseLead / beats
            return shifted - kotlin.math.floor(shifted)
        }
        val beatPhase = phaseAtCentre(tempo.beatPhase, 1f)
        val rhythm = if (ready && windowPts != null) {
            val available = checkNotNull(startMicros) + samplesSeen * 1_000_000L / sampleRate
            RhythmEstimate(windowPts, available, available + (tempo.remainingEvidenceSeconds * 1_000_000L).toLong(),
                tempo.revision, tempo.bpm, tempo.tempoConfidence, tempo.confidence, tempo.usable,
                beatPhase, tempo.alternativeBpm, tempo.alternativeConfidence)
        } else null
        val structure = if (!ready) null else sections.feed(
            bandPowers = spectralPower.bands,
            totalPower = spectralPower.totalPower,
            programmeMeanSquare = programme.meanSquare.takeIf { programme.supported && programme.ready },
            onsetMicros = detections?.let { batch -> (0 until batch.size).map { batch[it] }
                .firstOrNull { it.kind == AudioEventKind.Onset }?.ptsMicros },
            onsetStrength = beat,
            overallFast = drivers.overall.fast,
            centreMicros = windowPts,
            availableMicros = availableMicros,
        )
        val key = keyTracker.key
        val monoStart = triggerStart(scopePoints)
        val stereoStart = triggerStart(stereoLength)
        val frame = SpectrumFrame(
            ptsMicros = windowPts ?: -1L,
            hasTimestamp = windowPts != null,
            generation = generation,
            analysisRevision = analysisRevision,
            detections = detections,
            availability = if (samplesSeen < fftSize) AnalysisAvailability.WarmingUp else AnalysisAvailability.Ready,
            power = if (samplesSeen < fftSize) null else PowerSpectrum(
                window = AnalysisWindow(
                    startMicros?.let { it + (samplesSeen - fftSize) * 1_000_000L / sampleRate },
                    startMicros?.let { it + samplesSeen * 1_000_000L / sampleRate },
                    windowPts, sampleRate, fftSize,
                ),
                generation = generation,
                analysisRevision = analysisRevision,
                channelCount = channels,
                channelLayout = checkNotNull(inputFormat).channelLayout,
                channelLayoutMask = inputFormat?.channelLayoutMask,
                totalMeanSquare = spectralPower.totalPower,
                bins = spectralPower.bins.copyOf(),
                bands = spectralPower.bands.copyOf(),
                edges = spectralPower.edgesHz.copyOf(),
            ),
            programme = ProgrammeLoudness(
                availability = when {
                    !programme.supported -> AnalysisAvailability.Unavailable
                    !programme.ready -> AnalysisAvailability.WarmingUp
                    else -> AnalysisAvailability.Ready
                },
                window = if (!programme.ready) null else AnalysisWindow(
                    startMicros?.let { it + (samplesSeen - programme.windowSamples) * 1_000_000L / sampleRate },
                    startMicros?.let { it + samplesSeen * 1_000_000L / sampleRate },
                    startMicros?.let { it + (samplesSeen - programme.windowSamples / 2) * 1_000_000L / sampleRate },
                    sampleRate, programme.windowSamples,
                ),
                meanSquare = programme.meanSquare,
                digitalSilence = programme.digitalSilence,
            ),
            drivers = drivers,
            bands = smoothed,
            peaks = peaks,
            scope = readTriggeredScope(monoStart),
            scopeMetadata = traceMetadata(monoStart, scopePoints, channels, WaveformChannels.MeanAll),
            stereoScopeMetadata = traceMetadata(stereoStart, stereoLength, channels, WaveformChannels.FirstPair),
            level = drivers.overall.fast,
            bass = drivers.bass.fast,
            mid = drivers.mid.fast,
            treble = drivers.treble.fast,
            beat = beat,
            pulse = pulse,
            bandsRel = smoothed,
            bandsSorted = sorted,
            levelRel = drivers.overall.fast,
            bassRel = drivers.bass.fast,
            midRel = drivers.mid.fast,
            trebleRel = drivers.treble.fast,
            kick = kick,
            snare = snare,
            hat = hat,
            kickPulse = kickPulse,
            snarePulse = snarePulse,
            hatPulse = hatPulse,
            onsetStrength = beat,
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
            beatConfidence = if (tempo.usable) tempo.confidence else 0f,
            beatPhase = beatPhase,
            barPhase = 0f,
            phrasePhase = 0f,
            beatInSeconds = rhythm?.beatInSeconds ?: -1f,
            rhythm = rhythm,
            chroma = keyTracker.chroma.copyOf(),
            keyHue = key?.hue ?: 0f,
            keyConfidence = key?.confidence ?: 0f,
            key = key,
            structure = structure,
            centroid = timbre.centroid,
            flatness = timbre.flatness,
            width = smoothWidth,
            scopeLeft = readChannel(ringLeft, stereoStart),
            scopeRight = readChannel(ringRight, stereoStart),
            stereoHistory = stereoHistory,
        )
        onAnalysis?.invoke(frame)
        latest = frame
    }

    /**
     * The waveform, started at a rising zero crossing so the trace holds still.
     *
     * Taken straight from the ring, the same note drawn twice starts at a different point in its
     * cycle each time and the picture swims. Every oscilloscope ever built solves this the same
     * way: wait for the signal to cross zero going upward, and start drawing there.
     */
    private fun readTriggeredScope(start: Int): FloatArray {
        val out = FloatArray(scopePoints)
        for (point in 0 until scopePoints) {
            out[point] = ring[((start + point) % fftSize + fftSize) % fftSize]
        }
        return out
    }

    private fun traceMetadata(start: Int, points: Int, channels: Int, projection: WaveformChannels): WaveformMetadata? {
        if (samplesSeen < fftSize) return null
        val first = samplesSeen + start - writeIndex
        return WaveformMetadata(
            window = AnalysisWindow(
                startMicros?.let { it + first * 1_000_000L / sampleRate },
                startMicros?.let { it + (first + points) * 1_000_000L / sampleRate },
                startMicros?.let { it + (first * 2 + points) * 500_000L / sampleRate },
                sampleRate, points,
            ),
            firstSampleIndex = first,
            triggerOffsetSamples = start - (writeIndex - points),
            channels = projection,
            sourceChannelCount = channels,
        )
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

}

/** Largest supported power of two whose window spans at most 50 ms. */
internal fun transientFftSize(sampleRate: Int): Int {
    require(sampleRate in 1_000..768_000) { "supported sample rates are 1000..768000 Hz" }
    val maximum = minOf(sampleRate / 20, 32_768)
    var size = 4
    while (size <= maximum / 2) size *= 2
    return size
}
