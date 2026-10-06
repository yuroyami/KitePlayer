package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioResampler
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration

/**
 * Everything that happens to decoded audio between the decoder and the ring.
 *
 * Four stages, in this order, and the order is the design:
 *
 * 1. [ChannelMixer] puts the channels in the speakers the device has. Downmixing first means the rate
 *    conversion runs on two channels instead of eight.
 * 2. The resampler makes the rate the one the device accepted: [SincResampler], or what the
 *    configured [AudioResamplerFactory] makes.
 * 3. [TempoStage] plays the sound at `speed`, keeping its pitch or not. After the resampler, so
 *    it works at the device's rate, and before the equaliser and the trim.
 *
 * After them, [SilenceStage] shortens the pauses when skip silence is on (#429), on the sound as it
 * will be heard, and then the stereo mode, the equaliser, the night mode and the trim scale it.
 *
 * The gain is NOT here. Volume and mute moved to the ring's read side on 2026-08-31, because a gain
 * applied on the way INTO the ring cannot reach audio already buffered and a change stayed inaudible
 * for the ring's whole depth. See AudioRingHandle.setGain.
 *
 * A stage that has nothing to do costs nothing beyond a copy: matching layouts copy channels,
 * matching rates skip the conversion entirely, and unity gain skips its own multiply.
 *
 * ### Rebuilding
 *
 * A pipeline is built for one pair of formats. A decoder may change its output format mid-stream, so
 * the feeder checks [matches] against the decoder's current format before every buffer and calls
 * [rebuiltFor] when it stops matching. Rebuilding rather than reconfiguring is what keeps the stages
 * free of half-applied state, and the volume settings carry over so a rebuild is inaudible.
 *
 * ### Ownership
 *
 * [output] is this pipeline's own buffer and it is reused by the next [process], so the caller reads
 * what it needs before calling again. The caller's input array is never written to, never held, and
 * never handed back.
 *
 * One instance per audio stream, owned by the audio feeder. Not thread safe by design: the feeder is
 * the ring's single producer and this sits directly in front of it.
 *
 * ### Limits
 *
 * No stage allocates more than [MAX_STAGE_VALUES] values for one buffer. A decoder format with no
 * channels or no rate, a conversion that would need more room, and a resampler whose answers break
 * its contract are refused with a [PlaybackException] carrying [PlaybackError.DecoderFailed], before
 * anything is allocated. An unusual rate plays whenever its buffers fit.
 */
internal class AudioPipeline(
    /** What the decoder produces. The mask on it is what the mixer keys on. */
    val sourceFormat: AudioFormat,
    /** What the device accepted, which is what the ring and the sink expect. */
    val targetFormat: AudioFormat,
    private val onWarning: (PlaybackWarning) -> Unit = {},
    /** The tempo stage's first pitch law; see [preservePitch]. */
    preservePitch: Boolean = true,
    /** The LFE and headroom policy the downmix applies; see `DownmixConfig`. */
    private val downmix: io.github.yuroyami.kiteplayer.DownmixConfig =
        io.github.yuroyami.kiteplayer.DownmixConfig(),
    /**
     * Makes the rate conversion when the rates differ. Null uses [SincResampler]. Dropped after
     * its first refusal, so a later speed change does not ask again.
     */
    private var resamplerFactory: AudioResamplerFactory? = null,
    /** Told when [resamplerFactory] throws, after this pipeline fell back to [SincResampler]. */
    private val onResamplerRefused: (Throwable) -> Unit = {},
    /** The tempo stage's first rate; see [speed]. */
    initialSpeed: Double = 1.0,
    /** Whether mono or stereo also fills a surround device; see `UpmixMode`. */
    private val upmix: io.github.yuroyami.kiteplayer.UpmixMode = io.github.yuroyami.kiteplayer.UpmixMode.Off,
) : AutoCloseable {
    init {
        // A decoder can report any format, so it is checked before any stage multiplies by it.
        if (sourceFormat.sampleRate <= 0 || sourceFormat.channels <= 0) {
            throw refused("the decoder reported ${sourceFormat.channels} channels at ${sourceFormat.sampleRate} Hz")
        }
        require(targetFormat.sampleRate > 0 && targetFormat.channels > 0) { "$targetFormat is not a device format" }
    }

    private val mixer = ChannelMixer(sourceFormat, targetFormat, onWarning, downmix, upmix)

    /** The rate conversion, or null when the rates match and there is nothing to convert. */
    private var resampler: AudioResampler? = buildResampler()

    /** The rate the resampler converts from. The speed never changes it: the tempo stage owns that. */
    private fun conversionRate(): Int = sourceFormat.sampleRate

    private fun buildResampler(): AudioResampler? {
        val sourceRate = conversionRate()
        val targetRate = targetFormat.sampleRate
        if (sourceRate == targetRate) return null
        val channels = targetFormat.channels
        val factory = resamplerFactory ?: return SincResampler(sourceRate, targetRate, channels)
        return try {
            factory.create(sourceRate, targetRate, channels)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            resamplerFactory = null
            onResamplerRefused(failure)
            SincResampler(sourceRate, targetRate, channels)
        }
    }

    private val tempo = TempoStage(targetFormat.channels, targetFormat.sampleRate).also {
        it.speed = checkedSpeed(initialSpeed)
        it.preservePitch = preservePitch
    }

    /**
     * The second half of a pitch shift (#465): a fold at the pitch ratio after the stretch, which
     * moves the pitch and gives back the time the stretch took, as VLC's `scaletempo_pitch` pairs a
     * stretch with a resampler. It bypasses at no shift, so it costs nothing then.
     */
    private val pitchStage = TempoStage(targetFormat.channels, targetFormat.sampleRate).also {
        it.preservePitch = false
    }

    private var wantedSpeed: Double = checkedSpeed(initialSpeed)
    private var wantedPreservePitch: Boolean = preservePitch

    /** Source frames per device frame, which turns the tempo stage's positions into source frames. */
    private val sourcePerTarget: Double = sourceFormat.sampleRate.toDouble() / targetFormat.sampleRate

    private val targetChannels = targetFormat.channels

    private var mixed = FloatArray(0)
    private var resampled = FloatArray(0)

    /**
     * The samples the last [process] produced, interleaved in [targetFormat].
     *
     * Only the first `frames * targetFormat.channels` values are the answer, where `frames` is what
     * [process] returned. The array is longer than that whenever an earlier buffer was longer.
     */
    /**
     * The processed samples. When every stage passes through, this ALIASES the last
     * [process] call's input rather than copying it; the caller's buffer is scratch by the
     * submit contract, consumed before the next decode reuses it.
     */
    var output: FloatArray = FloatArray(0)
        private set

    /**
     * The playback rate. 1.0 passes the sound through untouched. A new value applies to the next
     * buffer, with no seam: see [TempoStage].
     *
     * Owned by the feeder, like every stage of the pipeline.
     */
    var speed: Double
        get() = wantedSpeed
        set(value) {
            wantedSpeed = checkedSpeed(value)
            configureRates()
        }

    /**
     * True keeps pitch at speeds other than 1.0; false lets pitch move with the rate, mpv's
     * `audio-pitch-correction=no`. A new value applies to the next buffer, with no seam.
     */
    var preservePitch: Boolean
        get() = wantedPreservePitch
        set(value) {
            wantedPreservePitch = value
            configureRates()
        }

    /**
     * The pitch as a ratio of the sound's own, 2.0 an octave up and 0.5 an octave down (#465). 1.0
     * bypasses the pitch stage. A new value applies to the next buffer, with no seam, and moves no
     * timing: the stretch plays at the speed over the pitch and the fold at the pitch, so the two
     * together play at the speed.
     */
    var pitch: Double = 1.0
        set(value) {
            require(value.isFinite() && value >= MIN_PITCH && value <= MAX_PITCH) {
                "the pitch ratio must be within $MIN_PITCH..$MAX_PITCH, was $value"
            }
            field = value
            configureRates()
        }

    /**
     * The two stages' rates for the speed, the pitch law and the pitch. With no pitch the tempo
     * stage alone plays the speed, as it always has. With one, the fold takes the pitch, and the
     * speed's own pitch too when it is not preserved, and the stretch makes the rest of the speed.
     */
    private fun configureRates() {
        if (pitch == 1.0) {
            pitchStage.speed = 1.0
            tempo.speed = wantedSpeed
            tempo.preservePitch = wantedPreservePitch
        } else {
            val fold = pitch * (if (wantedPreservePitch) 1.0 else wantedSpeed)
            pitchStage.speed = fold
            tempo.speed = wantedSpeed / fold
            tempo.preservePitch = true
        }
    }

    private fun checkedSpeed(value: Double): Double {
        require(value.isFinite() && value >= TempoStage.MIN_SPEED && value <= TempoStage.MAX_SPEED) {
            "speed must be within ${TempoStage.MIN_SPEED}..${TempoStage.MAX_SPEED}, was $value"
        }
        return value
    }

    // Where the last call's output came from, in source frames since construction or the last
    // [reset]: [pieceCount] runs, each from [pieceStart] in the output, reading the source from
    // [pieceSource] at [pieceSlope] source frames per output frame. The audio clock is dated from
    // these, so it follows every change of rate exactly where the output changes.
    private val chainRuns = OutputRuns()

    /** The runs the last call reports: the chain's own, or the silence stage's when it ran. */
    private var reported: OutputRuns = chainRuns

    /** Runs of output the last [process] or [finish] made. */
    val pieceCount: Int get() = reported.count

    fun pieceStart(index: Int): Int = reported.start(index)

    fun pieceSource(index: Int): Double = reported.source(index)

    fun pieceSlope(index: Int): Double = reported.slope(index)

    /**
     * Media seconds per output second across piece [index]: the speed it plays at. Taken from the
     * tempo stage as it is, so a speed of 1.5 reads 1.5 exactly whatever the two sample rates.
     */
    fun pieceRate(index: Int): Double = reported.rate(index)

    /** Starts a call's runs afresh. */
    private fun clearPieces() {
        chainRuns.clear()
        reported = chainRuns
    }

    // The tempo stage's runs in its own output frames since the last [reset], kept while the pitch
    // stage may still read the frames they made: the pitch stage's runs point into them (#465).
    private val lineStarts = LongArray(LINE_CAPACITY)
    private val lineSources = DoubleArray(LINE_CAPACITY)
    private val lineSlopes = DoubleArray(LINE_CAPACITY)
    private var lineCount = 0

    /** Records the tempo stage's runs from its last call, which began at its output frame [emittedBefore]. */
    private fun recordTempoLine(emittedBefore: Long) {
        for (index in 0 until tempo.pieceCount) {
            val start = emittedBefore + tempo.pieceStart(index)
            val source = tempo.pieceSource(index)
            val slope = tempo.pieceSlope(index)
            if (lineCount > 0) {
                val last = lineCount - 1
                val predicted = lineSources[last] + (start - lineStarts[last]) * lineSlopes[last]
                if (lineSlopes[last] == slope && abs(predicted - source) < LINE_TOLERANCE) continue
                if (lineStarts[last] == start) lineCount--
            }
            if (lineCount == LINE_CAPACITY) dropOldestLines(LINE_CAPACITY / 2)
            lineStarts[lineCount] = start
            lineSources[lineCount] = source
            lineSlopes[lineCount] = slope
            lineCount++
        }
        // Past a second behind what the pitch stage has taken in, nothing reads a run any more.
        val needed = pitchStage.receivedFrames - targetFormat.sampleRate
        var drop = 0
        while (drop + 1 < lineCount && lineStarts[drop + 1] <= needed) drop++
        if (drop > 0) dropOldestLines(drop)
    }

    private fun dropOldestLines(count: Int) {
        lineStarts.copyInto(lineStarts, 0, count, lineCount)
        lineSources.copyInto(lineSources, 0, count, lineCount)
        lineSlopes.copyInto(lineSlopes, 0, count, lineCount)
        lineCount -= count
    }

    /** The recorded tempo run that made the tempo stage's output frame [position]. */
    private fun lineAt(position: Double): Int {
        var index = lineCount - 1
        while (index > 0 && lineStarts[index] > position) index--
        return index
    }

    /**
     * The pitch stage's runs from its last call, which made [frames] frames, each traced through
     * the tempo stage's runs to the source and recorded after any already there, shifted to start at
     * [at] in the output. A run of the pitch stage that crosses a change of the tempo stage's rate
     * splits there. With no pitch the pitch stage passes through, and these are the tempo stage's
     * own runs exactly.
     */
    private fun composePieces(at: Int, frames: Int) {
        if (lineCount == 0) return
        for (index in 0 until pitchStage.pieceCount) {
            val start = pitchStage.pieceStart(index)
            val end = if (index + 1 < pitchStage.pieceCount) pitchStage.pieceStart(index + 1) else frames
            val source = pitchStage.pieceSource(index)
            val slope = pitchStage.pieceSlope(index)
            var out = start
            while (out < end) {
                val position = source + (out - start) * slope
                val line = lineAt(position)
                val traced = lineSources[line] + (position - lineStarts[line]) * lineSlopes[line]
                addPiece(at + out, traced, slope * lineSlopes[line])
                if (line + 1 >= lineCount || slope <= 0.0) break
                val next = start + ceil((lineStarts[line + 1] - source) / slope).toInt()
                if (next <= out || next >= end) break
                out = next
            }
        }
    }

    private fun addPiece(start: Int, source: Double, rate: Double) {
        chainRuns.add(start, source * sourcePerTarget, rate * sourcePerTarget, rate)
    }

    /**
     * Runs [frames] of [input], which the tempo stage made in the call that began at its output
     * frame [emittedBefore], through the pitch stage, and records where the result came from, from
     * [at] in the output. The result is in [chainOutput].
     */
    private fun pitchRun(input: FloatArray, frames: Int, emittedBefore: Long, at: Int): Int {
        recordTempoLine(emittedBefore)
        val produced: Int
        if (pitchStage.isBypassing) {
            pitchStage.passThrough(input, frames)
            chainOutput = input
            produced = frames
        } else {
            produced = pitchStage.process(input, frames)
            chainOutput = pitchStage.output
        }
        composePieces(at, produced)
        return produced
    }

    /** Where [pitchRun] left its result. */
    private var chainOutput: FloatArray = FloatArray(0)

    /** True when this pipeline was built for exactly the format [decoderFormat] describes. */
    fun matches(decoderFormat: AudioFormat): Boolean = decoderFormat == sourceFormat

    /**
     * A pipeline for [decoderFormat] into the same target, carrying this one's volume settings over.
     *
     * The rate conversion's carried frame is deliberately not carried over: it belongs to the old
     * format and interpolating it into the new one is exactly the discontinuity the new pipeline
     * exists to avoid. The caller closes this pipeline once it has the new one.
     */
    fun rebuiltFor(
        decoderFormat: AudioFormat,
        preservePitch: Boolean = this.preservePitch,
        speed: Double = this.speed,
        resamplerFactory: AudioResamplerFactory? = this.resamplerFactory,
    ): AudioPipeline =
        AudioPipeline(
            decoderFormat, targetFormat, onWarning, preservePitch, downmix,
            resamplerFactory, onResamplerRefused, initialSpeed = speed, upmix = upmix,
        ).also {
            it.speed = speed
            it.pitch = pitch
            // No gain crosses here any more, and none needs to: the gain lives in the ring, which
            // outlives every pipeline rebuild. A rebuild used to have to carry the ramp POSITION
            // across or a swap un-muted itself for one whole ramp.
        }

    /**
     * Runs [frames] sample frames of interleaved [input], in [sourceFormat], through the three stages.
     *
     * @return sample frames written to [output], which differs from [frames] whenever the rates
     *         differ. It can be zero for a very short buffer being converted downward, when no output
     *         position fell inside it. Nothing is dropped by that: the read position and the last
     *         input frame carry to the next call, so the conversion stays continuous across it.
     */
    fun process(input: FloatArray, frames: Int): Int {
        clearPieces()
        if (frames <= 0) return 0

        /* An identity mixer used to copy the whole buffer anyway. Skipping it means
         * plain stereo-to-stereo playback runs zero pipeline copies until the ring write: the
         * mixer, the resampler at equal rates and the tempo stage at 1.0 all stand aside. The
         * gain is not one of these stages and has not been since it moved to the read side; it is
         * applied as frames LEAVE the ring, which is what makes a volume change audible within one
         * device period instead of one ring depth. The alias keys on isIdentity, NOT isPassThrough:
         * a pass-through with unequal counts still restrides frame by frame, and aliasing it played
         * raw interleave on the wrong speakers. */
        var produced = frames
        var result: FloatArray
        if (mixer.isIdentity) {
            result = input
        } else {
            mixed = grown(mixed, mixValues(frames))
            mixer.mix(input, mixed, frames)
            result = mixed
        }
        val conversion = resampler
        if (conversion != null) {
            val capacity = conversion.outputCapacity(frames)
            resampled = grown(resampled, conversionValues(capacity, frames))
            produced = conversion.process(result, frames, resampled)
            checkWritten(produced, capacity)
            result = resampled
        }

        // The tempo stage owns lookahead, so at speeds other than 1.0 it may answer zero while it
        // accumulates, and its output buffer replaces ours. At 1.0 with nothing held the samples
        // stay where they are, so normal playback pays not even a copy for it.
        val tempoBefore = tempo.emittedFrames
        if (tempo.isBypassing) {
            tempo.passThrough(result, produced)
        } else {
            produced = tempo.process(result, produced)
            result = tempo.output
        }
        produced = pitchRun(result, produced, tempoBefore, 0)
        result = chainOutput
        // Skip silence (#429), on what will be heard, before the stages that only scale it. Skipped
        // while off with nothing held, so an ordinary item pays nothing for it.
        if (!silence.isIdentity) {
            produced = silence.process(result, produced, chainRuns, ending = false)
            result = silence.output
            reported = silence.runs
        }

        /* Last, so it scales exactly what reaches the ring, and skipped entirely at unity so a file
         * with no ReplayGain tags pays nothing for the feature. In place: `result` is either our own
         * scratch or, in the all-bypass case, the caller's input, and the caller hands that buffer
         * over for the duration of the call. */
        // The stereo mode first, so the balance and the equaliser act on the sides the listener
        // hears (#462). Skipped in stereo, so it costs an ordinary file nothing.
        stereo.apply(result, produced)
        // Before the trim, so ReplayGain and balance scale what the equaliser produced rather than
        // the equaliser amplifying a level the trim already set.
        equalizer.apply(result, produced)
        // After the equaliser, so a band it raised is caught too, and before the trim, so the
        // balance and ReplayGain act on what the listener hears (#442).
        night.apply(result, produced)
        trim.apply(result, produced)

        output = result
        return produced
    }

    /**
     * The per-channel pre-gain: ReplayGain today, balance next.
     *
     * Exposed rather than configured through the constructor because it is set AFTER open, once the
     * container's tags have been read and the peak clamp resolved against the volume ceiling.
     */
    val trim: TrimStage = TrimStage(targetFormat.channels)

    /**
     * What the two front speakers play (#462), on the device's channels, after the downmix. Ramped
     * over 10 ms on a change, so it never clicks.
     */
    val stereo: StereoStage = StereoStage(targetFormat.channels, rampFrames = targetFormat.sampleRate / 100)

    /**
     * The ten-band equaliser, at the device's own rate because that is what its coefficients are
     * derived from. Flat by default and then free: see [EqualizerStage].
     */
    val equalizer: EqualizerStage = EqualizerStage(targetFormat.channels, targetFormat.sampleRate)

    /** Raises or lowers a centre the downmix folds into other speakers, by [db] decibels (#442). */
    fun setDialogueLevel(db: Float) = mixer.setDialogueLevel(db)

    /** The night mode (#442), skipped while off. See [NightStage]. */
    val night: NightStage = NightStage(targetFormat.channels, targetFormat.sampleRate)

    /** Skip silence (#429), skipped while off with nothing held. See [SilenceStage]. */
    val silence: SilenceStage = SilenceStage(targetFormat.channels, targetFormat.sampleRate)

    /**
     * Pushes out what the stages are still holding, for the end of the stream.
     *
     * Three stages hold something. The silence stage holds the pause it has not decided on yet, which
     * plays now, as it is. The tempo stage keeps up to two pitch periods of lookahead
     * that no further input will ever trigger, and dropping them loses the end of the media. The
     * rate conversion holds half a kernel, which is 0.36 ms at 44.1 kHz: small, but it
     * is real audio and the old interpolator's excuse for skipping it (it held under one frame, and
     * producing that frame would have meant inventing the sample after the end of the stream) no
     * longer applies. Silence after the end of the media is not an invention, it is the truth, so
     * the filter is drained with it and the tail comes out.
     *
     * The drained tail passes the tempo stage, then the equaliser and the trim, exactly as every
     * other buffer does, so ReplayGain and balance hold to the last sample. Volume and mute live on
     * the ring's read side, so they reach the tail anyway.
     *
     * Call once, at end of stream, on the feeder that owns this pipeline. Safe to call again: the
     * second call finds nothing queued and answers zero.
     *
     * @return sample frames written to [output], zero when no stage was holding anything.
     */
    fun finish(): Int {
        clearPieces()
        var total = 0
        // 1. The rate conversion's tail, through the tempo stage like any other buffer.
        val conversion = resampler
        if (conversion != null) {
            val capacity = conversion.outputCapacity(0)
            resampled = grown(resampled, conversionValues(capacity, 0))
            val drained = conversion.flush(resampled)
            checkWritten(drained, capacity)
            if (drained > 0) {
                val tempoBefore = tempo.emittedFrames
                var made = drained
                var stretched = resampled
                if (tempo.isBypassing) {
                    tempo.passThrough(resampled, drained)
                } else {
                    made = tempo.process(resampled, drained)
                    stretched = tempo.output
                }
                val shifted = pitchRun(stretched, made, tempoBefore, total)
                total = appendFinished(chainOutput, shifted, total)
            }
        }
        // 2. Whatever the tempo stage was still holding, after it, through the pitch stage.
        val tempoBefore = tempo.emittedFrames
        val last = tempo.finish()
        if (last > 0) {
            val shifted = pitchRun(tempo.output, last, tempoBefore, total)
            total = appendFinished(chainOutput, shifted, total)
        }
        // 3. Whatever the pitch stage was still holding, after that.
        val held = pitchStage.finish()
        if (held > 0) {
            composePieces(total, held)
            total = appendFinished(pitchStage.output, held, total)
        }

        // The silence stage hands on what it holds, the kept half of a cut included, so the last
        // frame out is the stream's last (#429).
        var tail = finished
        if (!silence.isIdentity) {
            total = silence.process(finished, total, chainRuns, ending = true)
            tail = silence.output
            reported = silence.runs
        }
        if (total <= 0) return 0
        // The same last stages as process, in the same order (#257, #462).
        stereo.apply(tail, total)
        equalizer.apply(tail, total)
        night.apply(tail, total)
        trim.apply(tail, total)
        output = tail
        return total
    }

    /** The end-of-stream tail, which is up to two pieces and has to leave as one buffer. */
    private var finished: FloatArray = FloatArray(0)

    private fun appendFinished(source: FloatArray, frames: Int, at: Int): Int {
        if (frames <= 0) return at
        val values = (at + frames) * targetChannels
        if (finished.size < values) finished = finished.copyOf(values)
        source.copyInto(finished, at * targetChannels, 0, frames * targetChannels)
        return at + frames
    }

    /**
     * Drops what the rate conversion carried across the last buffer. The seek path.
     *
     * Belongs to whoever owns the flush, with the feeder quiescent, exactly like the ring's own
     * flush. The gain keeps its position: the volume did not change because the position did.
     */
    fun reset() {
        clearPieces()
        mixer.reset()
        resampler?.reset()
        tempo.reset()
        pitchStage.reset()
        lineCount = 0
        // What it held is of the position that was abandoned.
        silence.reset()
        // The filters ring for a few dozen samples, so a seek that kept their history would splice
        // the tail of the old position onto the head of the new one.
        equalizer.reset()
    }

    /** Releases the rate conversion. The owner calls it when it replaces this pipeline or closes. */
    override fun close() {
        resampler?.close()
        resampler = null
    }

    private fun grown(buffer: FloatArray, values: Int): FloatArray =
        if (buffer.size >= values) buffer else FloatArray(values)

    /** The values the channel mix writes for [frames] frames, refused past [MAX_STAGE_VALUES]. */
    private fun mixValues(frames: Int): Int {
        val values = frames.toLong() * targetChannels
        if (values > MAX_STAGE_VALUES) {
            throw refused(
                "the channel mix needs $values values for one buffer of $frames frames, and the limit is " +
                    "$MAX_STAGE_VALUES",
            )
        }
        return values.toInt()
    }

    /**
     * The values the rate conversion asks for, room for [capacity] frames, refused before any is
     * allocated when the answer is negative or passes [MAX_STAGE_VALUES]. [frames] is the input.
     */
    private fun conversionValues(capacity: Int, frames: Int): Int {
        val values = capacity.toLong() * targetChannels
        if (capacity < 0 || values > MAX_STAGE_VALUES) {
            throw refused(
                "the rate conversion from ${conversionRate()} Hz to ${targetFormat.sampleRate} Hz asks for room " +
                    "for $capacity frames of $targetChannels channels for $frames input frames, and the limit " +
                    "is $MAX_STAGE_VALUES values",
            )
        }
        return values.toInt()
    }

    /** A resampler that wrote outside the room it asked for broke its contract. */
    private fun checkWritten(written: Int, capacity: Int) {
        if (written < 0 || written > capacity) {
            throw refused("the rate conversion wrote $written frames into room for $capacity")
        }
    }

    internal companion object {
        /**
         * The most float values one stage may allocate for one buffer, which is 16 MiB. A block of
         * 65,535 frames of 7.1 audio converted from 44.1 kHz to 192 kHz needs about 2.3 Mi.
         */
        const val MAX_STAGE_VALUES: Int = 4 * 1024 * 1024

        /** The pitch ratios [pitch] takes: an octave either way. */
        const val MIN_PITCH: Double = 0.5
        const val MAX_PITCH: Double = 2.0

        /** How many of the tempo stage's runs are kept for the pitch stage to trace through. */
        private const val LINE_CAPACITY = 64

        /** How far apart, in frames, two runs on one line may predict a position and still be one. */
        private const val LINE_TOLERANCE = 1e-6

        /** The typed refusal. The audio feed replaces "audio" with the stream's codec. */
        fun refused(detail: String): PlaybackException =
            PlaybackException(PlaybackError.DecoderFailed("audio", detail))
    }
}
