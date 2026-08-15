package io.github.yuroyami.kiteplayer.output

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong

/** Color detected from MediaCodec plus whether container/codec output made it non-ambiguous. */
internal data class MediaCodecOutputColor(
    val colorSpace: ColorSpaceInfo,
    val reliable: Boolean,
)

/** Codec facts shared with the renderer target before a MediaCodec instance is selected. */
internal data class MediaCodecStreamRequirement(
    val mime: String,
    val profile: Int,
    val level: Int,
    val bitDepth: Int,
    val colorSpace: ColorSpaceInfo?,
)

/**
 * The output target's contract with MediaCodec.
 *
 * A native Surface normally preserves the coded output. A composited target may instead ask the
 * decoder to tone-map into a color space it can represent. Validation still happens against the
 * actual output format, so a vendor cannot silently ignore that request and feed HDR into SDR GL.
 */
internal data class MediaCodecOutputContract(
    val requestedColorTransfer: Int? = null,
    val requireExplicitOutputColor: Boolean = false,
    val trustedOutputColor: ColorSpaceInfo? = null,
    val validateOutput: ((MediaCodecOutputColor) -> Boolean)? = null,
)

internal fun interface MediaCodecOutputAdmission {
    fun admit(requirement: MediaCodecStreamRequirement): MediaCodecOutputContract?
}

private val DIRECT_SURFACE_ADMISSION = MediaCodecOutputAdmission { requirement ->
    MediaCodecOutputContract().takeIf {
        canRepresentMediaCodecColorExactly(requirement.colorSpace)
    }
}

/** A hardware decoder paired with the Surface target owned by one Android video renderer. */
internal class MediaCodecVideoDecoderFactory(
    private val target: MediaCodecSurfaceTarget,
    private val applyCodecRotation: Boolean = true,
    private val outputAdmission: MediaCodecOutputAdmission = DIRECT_SURFACE_ADMISSION,
) : VideoDecoderFactory {
    override val name: String = "Android MediaCodec direct Surface"

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return refuseMediaCodec(hwdec, "direct MediaCodec output requires Android 10 or newer")
        }
        if (stream.kind != TrackKind.Video || !hwdec.allowsMediaCodec()) return null
        val size = stream.videoSize
            ?: return refuseMediaCodec(hwdec, "the stream has no coded video size")
        if (size.width <= 0 || size.height <= 0) {
            return refuseMediaCodec(hwdec, "the stream has invalid coded size ${size.width}x${size.height}")
        }

        val codec = codecInput(stream) ?: return refuseMediaCodec(
            hwdec,
            "the ${stream.codec} configuration is missing, malformed, ambiguous, or outside Android's proved profiles",
        )
        if (!hasMediaCodecTierCapabilityProof(codec.highTier)) {
            return refuseMediaCodec(
                hwdec,
                "AV1 high-tier hardware admission requires peak bitrate metadata, " +
                    "which PlayerStreamInfo does not expose",
            )
        }
        val outputContract = outputAdmission.admit(codec.requirement) ?: return refuseMediaCodec(
            hwdec,
            "the output target cannot safely represent ${codec.colorSpace ?: "unspecified color"}",
        )
        val effectiveStream = if (codec.colorSpace == stream.colorSpace) {
            stream
        } else {
            stream.copy(colorSpace = codec.colorSpace)
        }
        if (
            outputContract.requestedColorTransfer != null &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            return refuseMediaCodec(hwdec, "decoder-side color conversion requires Android 12 or newer")
        }
        val probeFormat = mediaCodecFormat(
            codec = codec,
            stream = stream,
            size = size,
            outputContract = outputContract,
            configuredProfile = codec.androidProfile,
            applyCodecRotation = applyCodecRotation,
            includeOutputRequest = false,
        )
        val decoderProbe = findHardwareDecoders(probeFormat, codec)
        if (decoderProbe.decoders.isEmpty()) return refuseMediaCodec(hwdec, decoderProbe.reason)

        val configurationFailures = mutableListOf<String>()
        for (candidate in decoderProbe.decoders) {
            val format = mediaCodecFormat(
                codec = codec,
                stream = stream,
                size = size,
                outputContract = outputContract,
                configuredProfile = candidate.configuredProfile,
                applyCodecRotation = applyCodecRotation,
                includeOutputRequest = true,
            )
            try {
                val decoder = MediaCodecVideoDecoder(
                    codecName = candidate.info.name,
                    format = format,
                    codecInput = codec,
                    stream = effectiveStream,
                    target = target,
                    frameRotationDegrees = if (applyCodecRotation) {
                        0
                    } else {
                        normalizedQuarterTurn(stream.rotationDegrees)
                    },
                    outputContract = outputContract,
                )
                return try {
                    target.publishGeometry(size, normalizedQuarterTurn(stream.rotationDegrees))
                    decoder
                } catch (failure: Throwable) {
                    decoder.close()
                    throw failure
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                configurationFailures += "${candidate.info.name}: " +
                    (failure.message ?: failure::class.simpleName.orEmpty())
            }
        }
        return refuseMediaCodec(
            hwdec,
            "accelerated decoders matched ${codec.mime} profile=${codec.androidProfile} " +
                "level=${codec.androidLevel}, but configuration failed: " +
                configurationFailures.joinToString(),
        )
    }
}

private fun mediaCodecFormat(
    codec: CodecInput,
    stream: PlayerStreamInfo,
    size: VideoSize,
    outputContract: MediaCodecOutputContract,
    configuredProfile: Int,
    applyCodecRotation: Boolean,
    includeOutputRequest: Boolean,
): MediaFormat = MediaFormat.createVideoFormat(codec.mime, size.width, size.height).apply {
    setInteger(MediaFormat.KEY_PROFILE, configuredProfile)
    setInteger(MediaFormat.KEY_LEVEL, codec.androidLevel)
    codec.csd?.let { data ->
        setByteBuffer("csd-0", ByteBuffer.wrap(data.csd0))
        data.csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
    }
    codec.opaqueCsd?.takeIf { it.isNotEmpty() }?.let {
        setByteBuffer("csd-0", ByteBuffer.wrap(it))
    }
    stream.frameRate?.takeIf { it.isFinite() && it > 0.0 }?.let {
        setFloat(MediaFormat.KEY_FRAME_RATE, it.toFloat())
    }
    stream.bitrate?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.let {
        setInteger(MediaFormat.KEY_BIT_RATE, it.toInt())
    }
    applyColorHints(codec.colorSpace)
    if (includeOutputRequest) {
        outputContract.requestedColorTransfer?.let { requested ->
            setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, requested)
        }
    }
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, codec.maxInputSize(size))
    setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
    normalizedQuarterTurn(stream.rotationDegrees).takeIf { applyCodecRotation && it != 0 }?.let {
        setInteger(MediaFormat.KEY_ROTATION, it)
    }
}

private fun refuseMediaCodec(policy: HwdecPolicy, reason: String): VideoDecoder? {
    if (policy == HwdecPolicy.Require) {
        Log.w(MEDIA_CODEC_LOG_TAG, reason)
        throw IllegalArgumentException(reason)
    }
    return null
}

private const val MEDIA_CODEC_LOG_TAG: String = "KiteMediaCodec"

/** Android's AV1 constants omit tier; average bitrate cannot prove a high-tier capability bound. */
internal fun hasMediaCodecTierCapabilityProof(highTier: Boolean): Boolean = !highTier

/** Decoder-epoch sequence for MediaCodec's absolute timed Surface-release timestamps. */
internal class MediaCodecReleaseTimestampSequence {
    private var lastTimestampNanos: Long? = null

    fun normalize(requestedTimestampNanos: Long): Long {
        val previous = lastTimestampNanos
        val normalized = when {
            previous == null || requestedTimestampNanos > previous -> requestedTimestampNanos
            previous < Long.MAX_VALUE -> previous + 1L
            else -> throw IllegalStateException("timed Surface release timestamp overflow")
        }
        lastTimestampNanos = normalized
        return normalized
    }

    fun reset() {
        lastTimestampNanos = null
    }
}

private data class CodecInput(
    val mime: String,
    val androidProfile: Int,
    val androidLevel: Int,
    val csd: CodecSpecificData? = null,
    val opaqueCsd: ByteArray? = null,
    val colorSpace: ColorSpaceInfo? = null,
    val bitDepth: Int,
    val highTier: Boolean,
) {
    val requirement: MediaCodecStreamRequirement
        get() = MediaCodecStreamRequirement(
            mime = mime,
            profile = androidProfile,
            level = androidLevel,
            bitDepth = bitDepth,
            colorSpace = colorSpace,
        )

    fun packetBytes(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        return csd?.let {
            AnnexB.toAnnexB(bytes, it.nalLengthSize)
                ?: throw IllegalArgumentException("malformed length-prefixed video packet")
        } ?: bytes
    }

    fun configBuffers(): List<ByteArray> = buildList {
        csd?.let { data ->
            add(data.csd0)
            data.csd1?.let(::add)
        } ?: opaqueCsd?.takeIf { it.isNotEmpty() }?.let(::add)
    }

    /**
     * Decoder input allocation using the same conservative compression floors as Media3.
     * It is deliberately an upper bound: a dequeued input slot cannot be returned unfilled.
     */
    fun maxInputSize(size: VideoSize): Int {
        val pixels = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> {
                val blocksWide = (size.width.toLong() + 15L) / 16L
                val blocksHigh = (size.height.toLong() + 15L) / 16L
                blocksWide * blocksHigh * 16L * 16L
            }
            else -> size.width.toLong() * size.height.toLong()
        }
        val compression = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_VP9 -> 4L
            else -> 2L
        }
        val estimated = pixels.coerceAtLeast(1L) * 3L / (2L * compression)
        val codecFloor = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) HEVC_INPUT_FLOOR else 0L
        val initialization = configBuffers().sumOf { it.size.toLong() }
        return (maxOf(estimated, codecFloor) + initialization)
            .coerceIn(MIN_INPUT_SIZE, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private companion object {
        const val HEVC_INPUT_FLOOR: Long = 2L * 1024L * 1024L
        const val MIN_INPUT_SIZE: Long = 64L * 1024L
    }
}

private fun codecInput(stream: PlayerStreamInfo): CodecInput? {
    val parsed = parseMediaCodecConfiguration(stream.codec, stream.codecExtradata, stream.vp9) ?: return null
    val color = if (parsed.declaredColor == null) {
        stream.colorSpace
    } else {
        mergeMediaCodecColor(stream.colorSpace, parsed.declaredColor) ?: return null
    }
    val profile = mediaCodecProfileForColor(parsed.mime, parsed.profile, color)
    return CodecInput(
        mime = parsed.mime,
        androidProfile = profile,
        androidLevel = parsed.level,
        csd = parsed.csd,
        opaqueCsd = parsed.opaqueCsd,
        colorSpace = color,
        bitDepth = parsed.bitDepth,
        highTier = parsed.highTier,
    )
}

/** Selects Android's HDR profile only when the stream declaration proves that stronger contract. */
internal fun mediaCodecProfileForColor(mime: String, baseProfile: Int, color: ColorSpaceInfo?): Int {
    val transfer = color?.transfer
    return when {
        mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            baseProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 &&
            transfer == ColorTransfer.Pq -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
        mime == MediaFormat.MIMETYPE_VIDEO_VP9 && transfer == ColorTransfer.Pq &&
            baseProfile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ->
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR
        mime == MediaFormat.MIMETYPE_VIDEO_VP9 && transfer == ColorTransfer.Pq &&
            baseProfile == MediaCodecInfo.CodecProfileLevel.VP9Profile3 ->
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR
        mime == MediaFormat.MIMETYPE_VIDEO_AV1 &&
            baseProfile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 &&
            transfer == ColorTransfer.Pq -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
        else -> baseProfile
    }
}

internal fun HwdecPolicy.allowsMediaCodec(): Boolean = when (this) {
    HwdecPolicy.Auto -> true
    HwdecPolicy.Require -> true
    HwdecPolicy.Off -> false
    is HwdecPolicy.Prefer -> false
}

private data class MediaCodecDecoderCandidate(
    val info: MediaCodecInfo,
    val configuredProfile: Int,
)

private data class MediaCodecDecoderProbe(
    val decoders: List<MediaCodecDecoderCandidate>,
    val reason: String,
)

private fun findHardwareDecoders(format: MediaFormat, codec: CodecInput): MediaCodecDecoderProbe {
    val accepted = mutableListOf<MediaCodecDecoderCandidate>()
    val candidates = mutableListOf<String>()
    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
        if (info.isEncoder || !info.isHardwareAccelerated) continue
        if (info.supportedTypes.none { it.equals(codec.mime, ignoreCase = true) }) continue
        var advertised = "unreadable capabilities"
        val acceptedProfile = try {
            val capabilities = info.getCapabilitiesForType(codec.mime)
            advertised = capabilities.profileLevels.joinToString(",") { "${it.profile}/${it.level}" }
            val profiles = capabilities.profileLevels
                .filter { advertised ->
                    advertisedProfileLevelSupports(
                        mime = codec.mime,
                        advertisedProfile = advertised.profile,
                        advertisedLevel = advertised.level,
                        requiredProfile = codec.androidProfile,
                        requiredLevel = codec.androidLevel,
                    )
                }
                .map { it.profile }
                .distinct()
                .sortedBy { if (it == codec.androidProfile) 0 else 1 }
            profiles.firstOrNull { advertisedProfile ->
                // Constrained AVC profiles are subsets. Some vendors advertise only the parent
                // Baseline or High capability, which is proof of support but isFormatSupported
                // compares profiles for exact equality. Probe and configure the advertised parent.
                format.setInteger(MediaFormat.KEY_PROFILE, advertisedProfile)
                capabilities.isFormatSupported(format)
            }
        } catch (_: Exception) {
            null
        }
        if (acceptedProfile != null) {
            accepted += MediaCodecDecoderCandidate(info, acceptedProfile)
        } else {
            candidates += "${info.name}[$advertised]"
        }
    }
    if (accepted.isNotEmpty()) return MediaCodecDecoderProbe(accepted, "")
    val requirement = "${codec.mime} profile=${codec.androidProfile} level=${codec.androidLevel} " +
        "depth=${codec.bitDepth}"
    val reason = if (candidates.isEmpty()) {
        "no accelerated decoder advertises $requirement"
    } else {
        "no accelerated decoder accepts $requirement; candidates=${candidates.joinToString()}"
    }
    return MediaCodecDecoderProbe(emptyList(), reason)
}

private fun normalizedQuarterTurn(degrees: Int): Int {
    val normalized = ((degrees % 360) + 360) % 360
    return normalized.takeIf { it == 0 || it == 90 || it == 180 || it == 270 } ?: 0
}

private data class MediaCodecResources(
    val reader: ImageReader,
    val surface: android.view.Surface,
    val codec: MediaCodec,
)

private fun createMediaCodecResources(codecName: String, size: VideoSize): MediaCodecResources {
    val reader = ImageReader.newInstance(
        size.width,
        size.height,
        ImageFormat.PRIVATE,
        3,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )
    return try {
        MediaCodecResources(reader, reader.surface, MediaCodec.createByCodecName(codecName))
    } catch (failure: Throwable) {
        reader.close()
        throw failure
    }
}

/**
 * Synchronous MediaCodec state. Decode operations run on KitePlayer's video worker. Surface swaps
 * are the sole cross-thread call and share [codecLock] with every codec operation, so a
 * `surfaceDestroyed` callback can synchronously move the producer before Android disconnects it.
 */
private class MediaCodecVideoDecoder(
    codecName: String,
    format: MediaFormat,
    private val codecInput: CodecInput,
    private val stream: PlayerStreamInfo,
    private val target: MediaCodecSurfaceTarget,
    private val frameRotationDegrees: Int,
    private val outputContract: MediaCodecOutputContract,
) : VideoDecoder, MediaCodecFrameOwner, MediaCodecSurfaceTarget.Switcher {
    override val hardware: HwdecStatus = HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec)

    private val configuredSize: VideoSize = requireNotNull(stream.videoSize)
    private val resources = createMediaCodecResources(codecName, configuredSize)
    private val fallbackReader: ImageReader = resources.reader
    private val fallbackSurface: android.view.Surface = resources.surface
    private val codec: MediaCodec = resources.codec
    private val codecLock = Any()
    private val bufferInfo = MediaCodec.BufferInfo()
    private val readyFrames = ArrayDeque<VideoFrame>()
    private val releaseCommands = ConcurrentLinkedQueue<MediaCodecReleaseCommand>()
    private val outstanding = mutableMapOf<Int, Long>()
    private val pendingConfig = ArrayDeque<ByteArray>()
    private val releaseTimestamps = MediaCodecReleaseTimestampSequence()
    private var pendingInput: PendingInput? = null

    private var generation: Generation = Generation.Initial
    private var decoderEpoch: Long = 0L
    private var drainQueued: Boolean = false
    private var eosOutputSeen: Boolean = false
    private var outputEstablished: Boolean = false
    @Volatile private var drained: Boolean = false
    @Volatile private var closed: Boolean = false
    private var codecReleased: Boolean = false
    @Volatile private var fatalFailure: Throwable? = null
    private var nextSyntheticPtsUs: Long = 0L
    private var outputSize: VideoSize = configuredSize
    private var outputColor: ColorSpaceInfo = outputContract.trustedOutputColor ?: stream.colorSpace
        ?: ColorSpaceInfo.guessFor(configuredSize.height)
    private var outputColorValidated: Boolean = outputContract.validateOutput == null ||
        !outputContract.requireExplicitOutputColor &&
        (outputContract.trustedOutputColor ?: stream.colorSpace)?.let { declared ->
            outputContract.validateOutput.invoke(MediaCodecOutputColor(declared, reliable = true))
        } == true
    private var configuredSurface: android.view.Surface = fallbackSurface
    private var configuredSurfaceVersion: Long? = null

    override val isDrained: Boolean get() = drained

    init {
        try {
            synchronized(codecLock) {
                codec.configure(format, fallbackSurface, null, 0)
                outputContract.requestedColorTransfer?.let { requested ->
                    val accepted = codec.inputFormat.intOrNull(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
                    check(accepted == requested) {
                        "MediaCodec rejected output transfer request $requested (reported $accepted)"
                    }
                }
                codec.start()
            }
            target.bind(this)
        } catch (failure: Throwable) {
            target.unbind(this)
            synchronized(codecLock) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                codecReleased = true
            }
            runCatching { fallbackReader.close() }
            throw failure
        }
    }

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "MediaCodec decoder is closed" }
        fatalFailure?.let { throw IllegalStateException("MediaCodec decoder failed", it) }
        pumpInput()
        if (packet == null && (drainQueued || pendingInput?.eos == true)) return true
        if (pendingInput != null) return false
        check(packet == null || !drainQueued) { "a packet was offered after MediaCodec drain started" }

        // Copy once and take ownership immediately. MediaCodec may transiently expose no input slot;
        // retaining the AU here keeps that ordinary state out of the SPI's stricter false-send path.
        val payload = packet?.let { codecInput.packetBytes(it.copyBytes()) }
        if (payload?.isEmpty() == true) return true
        pendingInput = if (payload == null) {
            PendingInput(bytes = EMPTY_INPUT, ptsUs = 0L, eos = true)
        } else {
            PendingInput(bytes = payload, ptsUs = requireNotNull(packet).let(::packetPts), eos = false)
        }
        pumpInput()
        return true
    }

    override suspend fun receive(): VideoFrame? {
        check(!closed) { "MediaCodec decoder is closed" }
        fatalFailure?.let { throw IllegalStateException("MediaCodec decoder failed", it) }
        maintainCodec()
        pumpInput()
        readyFrames.pollFirst()?.let { return it }
        pumpOutput()
        readyFrames.pollFirst()?.let { return it }
        if (eosOutputSeen) drained = true
        return null
    }

    override suspend fun flush(newGeneration: Generation) {
        check(!closed) { "MediaCodec decoder is closed" }
        while (true) readyFrames.pollFirst()?.close() ?: break
        // Seeking invalidates every queued timed release. Rendering one here would publish an old
        // generation after the new timeline has already started.
        rejectReleaseCommands()
        val replayCodecConfig = !outputEstablished
        codecCall { flush() }
        decoderEpoch += 1L
        releaseTimestamps.reset()
        outstanding.clear()
        rejectReleaseCommands()
        generation = newGeneration
        drainQueued = false
        eosOutputSeen = false
        drained = false
        nextSyntheticPtsUs = 0L
        pendingInput = null
        pendingConfig.clear()
        if (replayCodecConfig) codecInput.configBuffers().forEach { pendingConfig.addLast(it) }
        drainFallbackImages()
    }

    override fun release(command: MediaCodecReleaseCommand) {
        if (closed) {
            command.complete(rendered = false)
            return
        }
        releaseCommands.add(command)
        if (closed && releaseCommands.remove(command)) command.complete(rendered = false)
    }

    override fun close() {
        if (closed) return
        closed = true
        while (true) readyFrames.pollFirst()?.close() ?: break
        if (!codecReleased) {
            // Close is a discard boundary, not a final presentation opportunity.
            runCatching { rejectReleaseCommands() }
            outstanding.keys.toList().forEach { index ->
                runCatching { codecCall { releaseOutputBuffer(index, false) } }
            }
        }
        outstanding.clear()
        rejectReleaseCommands()
        try {
            synchronized(codecLock) {
                if (!codecReleased) {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                    codecReleased = true
                }
            }
        } finally {
            runCatching { drainFallbackImages() }
            runCatching { fallbackReader.close() }
            target.unbind(this)
        }
    }

    private fun pumpInput() {
        val bytes = pendingConfig.firstOrNull() ?: pendingInput?.bytes ?: return
        val index = acquireInputOrStageOutput() ?: return
        synchronized(codecLock) {
            ensureCodecHealthyLocked()
            val input = requireNotNull(codec.getInputBuffer(index)) {
                "MediaCodec returned a null input buffer in synchronous byte-buffer mode"
            }
            input.clear()
            check(bytes.size <= input.remaining()) {
                "MediaCodec input holds ${input.remaining()} bytes, queued input needs ${bytes.size}"
            }
            input.put(bytes)
            if (pendingConfig.isNotEmpty()) {
                codec.queueInputBuffer(index, 0, bytes.size, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                pendingConfig.removeFirst()
            } else {
                val offered = requireNotNull(pendingInput)
                val flags = if (offered.eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(index, 0, bytes.size, offered.ptsUs, flags)
                if (offered.eos) drainQueued = true else commitPacketPts(offered.ptsUs)
                pendingInput = null
            }
        }
    }

    /** One bounded codec poll. Null means the caller must receive before retrying the same packet. */
    private fun acquireInputOrStageOutput(): Int? {
        if (readyFrames.isNotEmpty()) return null
        maintainCodec()
        val index = codecCall { dequeueInputBuffer(DEQUEUE_POLL_US) }
        if (index >= 0) return index
        pumpOutput()
        return null
    }

    /** Returns true only when a real frame was added to [readyFrames]. */
    private fun pumpOutput(): Boolean {
        while (true) {
            val index = codecCall { dequeueOutputBuffer(bufferInfo, 0L) }
            when {
                index >= 0 -> {
                    outputEstablished = true
                    check(outputColorValidated) {
                        "MediaCodec did not report color metadata safe for this output target"
                    }
                    val flags = bufferInfo.flags
                    val eos = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val config = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (config || bufferInfo.size <= 0) {
                        codecCall { releaseOutputBuffer(index, false) }
                        if (eos) eosOutputSeen = true
                        continue
                    }
                    val epoch = decoderEpoch
                    outstanding[index] = epoch
                    readyFrames.addLast(
                        MediaCodecBufferFrame(
                            owner = this,
                            outputIndex = index,
                            decoderEpoch = epoch,
                            target = target,
                            pts = Pts(bufferInfo.presentationTimeUs),
                            duration = frameDuration(),
                            generation = generation,
                            size = outputSize,
                            colorSpace = outputColor,
                            rotationDegrees = frameRotationDegrees,
                        ),
                    )
                    if (eos) eosOutputSeen = true
                    return true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputEstablished = true
                    updateOutputFormat(codecCall { outputFormat })
                    codecCall { setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                else -> return false
            }
        }
    }

    private fun maintainCodec() {
        processReleaseCommands()
        drainFallbackImages()
    }

    private fun processReleaseCommands() {
        while (true) {
            val command = releaseCommands.poll() ?: break
            val ownedEpoch = outstanding[command.outputIndex]
            if (ownedEpoch == null || command.decoderEpoch != decoderEpoch || ownedEpoch != command.decoderEpoch) {
                command.complete(rendered = false)
                continue
            }
            outstanding.remove(command.outputIndex)

            target.withSnapshotCompletion(command::complete) { display ->
                var rendered = false
                val requestedVersion = command.displayVersion
                val renderNanos = command.renderNanos
                val render = renderNanos != null &&
                    requestedVersion != null &&
                    requestedVersion == display.version &&
                    display.isDisplayable
                synchronized(codecLock) {
                    ensureCodecHealthyLocked()
                    val targetsConfiguredDisplay = configuredSurface === display.surface &&
                        configuredSurfaceVersion == display.version
                    if (render && targetsConfiguredDisplay) {
                        val timestamp = try {
                            releaseTimestamps.normalize(requireNotNull(renderNanos)).also {
                                command.beforeRender(it)
                            }
                        } catch (prepareFailure: Throwable) {
                            try {
                                codec.releaseOutputBuffer(command.outputIndex, false)
                            } catch (discardFailure: Throwable) {
                                prepareFailure.addSuppressed(discardFailure)
                            }
                            throw prepareFailure
                        }
                        try {
                            codec.releaseOutputBuffer(command.outputIndex, timestamp)
                        } catch (releaseFailure: Throwable) {
                            try {
                                command.onRenderFailed(timestamp)
                            } catch (cancelFailure: Throwable) {
                                releaseFailure.addSuppressed(cancelFailure)
                            }
                            throw releaseFailure
                        }
                        rendered = true
                    } else {
                        codec.releaseOutputBuffer(command.outputIndex, false)
                    }
                }
                rendered
            }
        }
    }

    private fun rejectReleaseCommands() {
        while (true) {
            val command = releaseCommands.poll() ?: return
            command.complete(rendered = false)
        }
    }

    override fun switchTo(snapshot: MediaCodecSurfaceTarget.Snapshot) {
        synchronized(codecLock) {
            ensureCodecHealthyLocked()
            val desired = snapshot.surface?.takeIf { snapshot.isDisplayable } ?: fallbackSurface
            val desiredVersion = snapshot.version.takeIf { desired !== fallbackSurface }
            if (desired === configuredSurface && desiredVersion == configuredSurfaceVersion) return
            try {
                codec.setOutputSurface(desired)
                configuredSurface = desired
                configuredSurfaceVersion = desiredVersion
            } catch (switchFailure: Throwable) {
                if (desired !== fallbackSurface) {
                    try {
                        codec.setOutputSurface(fallbackSurface)
                        configuredSurface = fallbackSurface
                        configuredSurfaceVersion = null
                        // The private surface only drains safely. It is not a successful display
                        // switch, so surface recovery must rebuild this decoder rather than leave
                        // playback black while the codec appears healthy.
                        fatalFailure = switchFailure
                    } catch (fallbackFailure: Throwable) {
                        switchFailure.addSuppressed(fallbackFailure)
                        abortCodecLocked(switchFailure)
                    }
                } else {
                    abortCodecLocked(switchFailure)
                }
                throw switchFailure
            }
        }
    }

    private inline fun <T> codecCall(block: MediaCodec.() -> T): T = synchronized(codecLock) {
        ensureCodecHealthyLocked()
        codec.block()
    }

    private fun ensureCodecHealthyLocked() {
        fatalFailure?.let { throw IllegalStateException("MediaCodec decoder failed", it) }
        check(!codecReleased) { "MediaCodec decoder is released" }
    }

    /** Stops a codec that could not detach from a dying display Surface. Caller holds [codecLock]. */
    private fun abortCodecLocked(failure: Throwable) {
        fatalFailure = failure
        if (codecReleased) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        codecReleased = true
    }

    private fun drainFallbackImages() {
        while (true) {
            val image = runCatching { fallbackReader.acquireNextImage() }.getOrNull() ?: return
            image.close()
        }
    }

    private fun packetPts(packet: PlayerPacket): Long {
        val declared = packet.pts ?: packet.dts
        return declared?.micros ?: nextSyntheticPtsUs
    }

    private fun commitPacketPts(ptsUs: Long) {
        nextSyntheticPtsUs = ptsUs + frameDurationMicros()
    }

    private fun frameDuration(): Pts? = frameDurationMicros().takeIf { it > 0L }?.let(::Pts)

    private fun frameDurationMicros(): Long = stream.frameRate
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { (1_000_000.0 / it).roundToLong().coerceAtLeast(1L) }
        ?: 33_333L

    private fun updateOutputFormat(format: MediaFormat) {
        val width = format.intOrNull(MediaFormat.KEY_CROP_RIGHT)
            ?.let { right -> right - (format.intOrNull(MediaFormat.KEY_CROP_LEFT) ?: 0) + 1 }
            ?: format.intOrNull(MediaFormat.KEY_WIDTH)
            ?: outputSize.width
        val height = format.intOrNull(MediaFormat.KEY_CROP_BOTTOM)
            ?.let { bottom -> bottom - (format.intOrNull(MediaFormat.KEY_CROP_TOP) ?: 0) + 1 }
            ?: format.intOrNull(MediaFormat.KEY_HEIGHT)
            ?: outputSize.height
        val detectedColor = mediaCodecOutputColorFromCodes(
            standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD),
            transferCode = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
            range = format.intOrNull(MediaFormat.KEY_COLOR_RANGE),
            fallback = outputColor,
            outputContract = outputContract,
            sourceColorDeclared = stream.colorSpace != null,
        )
        outputContract.validateOutput?.let { validate ->
            check(validate(detectedColor)) {
                "MediaCodec reported color metadata this output target cannot represent: " +
                    detectedColor.colorSpace
            }
        }
        outputColor = detectedColor.colorSpace
        outputColorValidated = true
        if (width > 0 && height > 0) {
            outputSize = VideoSize(
                width = width,
                height = height,
                pixelAspectNumerator = configuredSize.pixelAspectNumerator,
                pixelAspectDenominator = configuredSize.pixelAspectDenominator,
            )
            target.publishGeometry(outputSize, normalizedQuarterTurn(stream.rotationDegrees))
        }
    }

    private companion object {
        private const val DEQUEUE_POLL_US: Long = 2_000L
        private val EMPTY_INPUT: ByteArray = ByteArray(0)
    }
}

private data class PendingInput(val bytes: ByteArray, val ptsUs: Long, val eos: Boolean)

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.applyColorHints(color: ColorSpaceInfo?) {
    val hints = color?.let(::mediaCodecColorHints) ?: return
    hints.standard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
    hints.transfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
    hints.range?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
}

internal data class MediaCodecColorHints(
    val standard: Int?,
    val transfer: Int?,
    val range: Int?,
)

/** Only emits Android keys that are exact representations of the stream declaration. */
internal fun mediaCodecColorHints(color: ColorSpaceInfo): MediaCodecColorHints = MediaCodecColorHints(
    standard = when {
        color.matrix == ColorMatrix.Unspecified && color.primaries == ColorPrimaries.Unspecified -> null
        color.matrix == ColorMatrix.Bt709 && color.primaries == ColorPrimaries.Bt709 ->
            MediaFormat.COLOR_STANDARD_BT709
        color.matrix == ColorMatrix.Bt470bg && color.primaries == ColorPrimaries.Bt470bg ->
            MediaFormat.COLOR_STANDARD_BT601_PAL
        color.matrix == ColorMatrix.Smpte170m && color.primaries == ColorPrimaries.Smpte170m ->
            MediaFormat.COLOR_STANDARD_BT601_NTSC
        color.matrix == ColorMatrix.Bt2020Ncl && color.primaries == ColorPrimaries.Bt2020 ->
            MediaFormat.COLOR_STANDARD_BT2020
        else -> null
    },
    transfer = when (color.transfer) {
        ColorTransfer.Bt601,
        ColorTransfer.Bt709,
        ColorTransfer.Bt2020Ten,
        ColorTransfer.Bt2020Twelve,
        -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        ColorTransfer.Linear -> MediaFormat.COLOR_TRANSFER_LINEAR
        ColorTransfer.Pq -> MediaFormat.COLOR_TRANSFER_ST2084
        ColorTransfer.Hlg -> MediaFormat.COLOR_TRANSFER_HLG
        ColorTransfer.Srgb,
        ColorTransfer.Gamma22,
        ColorTransfer.Gamma28,
        ColorTransfer.Unspecified,
        ColorTransfer.Smpte240m,
        ColorTransfer.Log,
        ColorTransfer.LogSqrt,
        ColorTransfer.Iec6196624,
        ColorTransfer.Bt1361Ecg,
        ColorTransfer.SmpteSt428,
        -> null
    },
    range = if (!color.rangeSpecified) null else if (color.fullRange) {
        MediaFormat.COLOR_RANGE_FULL
    } else {
        MediaFormat.COLOR_RANGE_LIMITED
    },
)

/** A declared value must either have an exact Android key or stay on the software decoder. */
internal fun canRepresentMediaCodecColorExactly(color: ColorSpaceInfo?): Boolean {
    color ?: return true
    val hints = mediaCodecColorHints(color)
    val standardDeclared = color.matrix != ColorMatrix.Unspecified ||
        color.primaries != ColorPrimaries.Unspecified
    val transferDeclared = color.transfer != ColorTransfer.Unspecified
    return (!standardDeclared || hints.standard != null) &&
        (!transferDeclared || hints.transfer != null) &&
        (!color.rangeSpecified || hints.range != null)
}

internal fun isRecognizedMediaCodecColorStandard(value: Int?): Boolean = when (value) {
    MediaFormat.COLOR_STANDARD_BT601_NTSC,
    MediaFormat.COLOR_STANDARD_BT601_PAL,
    MediaFormat.COLOR_STANDARD_BT2020,
    MediaFormat.COLOR_STANDARD_BT709,
    -> true
    else -> false
}

internal fun isRecognizedMediaCodecColorTransfer(value: Int?): Boolean = when (value) {
    MediaFormat.COLOR_TRANSFER_LINEAR,
    MediaFormat.COLOR_TRANSFER_ST2084,
    MediaFormat.COLOR_TRANSFER_HLG,
    MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
    -> true
    else -> false
}

internal fun isRecognizedMediaCodecColorRange(value: Int?): Boolean = when (value) {
    MediaFormat.COLOR_RANGE_FULL,
    MediaFormat.COLOR_RANGE_LIMITED,
    -> true
    else -> false
}

/** Resolves only recognized public color keys; an accepted transfer request proves transfer only. */
internal fun mediaCodecOutputColorFromCodes(
    standard: Int?,
    transferCode: Int?,
    range: Int?,
    fallback: ColorSpaceInfo,
    outputContract: MediaCodecOutputContract,
    sourceColorDeclared: Boolean,
): MediaCodecOutputColor {
    val recognizedStandard = standard.takeIf(::isRecognizedMediaCodecColorStandard)
    val recognizedTransfer = transferCode.takeIf(::isRecognizedMediaCodecColorTransfer)
    val recognizedRange = range.takeIf(::isRecognizedMediaCodecColorRange)
    // configure() verified that inputFormat echoed this request. Some codecs omit the redundant
    // SDR transfer key from their output format; that handshake never proves the color standard.
    val effectiveTransfer = recognizedTransfer ?: outputContract.requestedColorTransfer
    val requestedOutputFallback = if (
        outputContract.requestedColorTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    ) {
        // Android's decoder-side HDR conversion contract is limited-range BT.709 SDR. This fills
        // omitted transfer/range dimensions, while reliability below still requires a recognized
        // standard when the source itself was only guessed.
        ColorSpaceInfo()
    } else {
        fallback
    }
    val color = colorSpaceFromCodes(
        standard = recognizedStandard,
        transferCode = effectiveTransfer,
        range = recognizedRange,
        fallback = requestedOutputFallback,
    )
    val reliable = outputContract.trustedOutputColor != null ||
        (recognizedStandard != null && effectiveTransfer != null) ||
        (!outputContract.requireExplicitOutputColor && sourceColorDeclared)
    return MediaCodecOutputColor(color, reliable)
}

internal fun colorSpaceFromCodes(
    standard: Int?,
    transferCode: Int?,
    range: Int?,
    fallback: ColorSpaceInfo,
): ColorSpaceInfo {
    val matrix = when (standard) {
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> ColorMatrix.Smpte170m
        MediaFormat.COLOR_STANDARD_BT601_PAL -> ColorMatrix.Bt470bg
        MediaFormat.COLOR_STANDARD_BT2020 -> ColorMatrix.Bt2020Ncl
        MediaFormat.COLOR_STANDARD_BT709 -> ColorMatrix.Bt709
        else -> fallback.matrix
    }
    val primaries = when (standard) {
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> ColorPrimaries.Smpte170m
        MediaFormat.COLOR_STANDARD_BT601_PAL -> ColorPrimaries.Bt470bg
        MediaFormat.COLOR_STANDARD_BT2020 -> ColorPrimaries.Bt2020
        MediaFormat.COLOR_STANDARD_BT709 -> ColorPrimaries.Bt709
        else -> fallback.primaries
    }
    val transfer = when (transferCode) {
        MediaFormat.COLOR_TRANSFER_LINEAR -> ColorTransfer.Linear
        MediaFormat.COLOR_TRANSFER_ST2084 -> ColorTransfer.Pq
        MediaFormat.COLOR_TRANSFER_HLG -> ColorTransfer.Hlg
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> when (standard) {
            MediaFormat.COLOR_STANDARD_BT601_NTSC,
            MediaFormat.COLOR_STANDARD_BT601_PAL,
            -> ColorTransfer.Bt601
            MediaFormat.COLOR_STANDARD_BT2020 -> ColorTransfer.Bt2020Ten
            MediaFormat.COLOR_STANDARD_BT709 -> ColorTransfer.Bt709
            else -> when (fallback.transfer) {
                ColorTransfer.Bt601,
                ColorTransfer.Bt709,
                ColorTransfer.Bt2020Ten,
                ColorTransfer.Bt2020Twelve,
                -> fallback.transfer
                else -> ColorTransfer.Bt709
            }
        }
        else -> fallback.transfer
    }
    return ColorSpaceInfo(
        matrix = matrix,
        primaries = primaries,
        transfer = transfer,
        fullRange = when (range) {
            MediaFormat.COLOR_RANGE_FULL -> true
            MediaFormat.COLOR_RANGE_LIMITED -> false
            else -> fallback.fullRange
        },
        chromaLocation = fallback.chromaLocation,
        rangeSpecified = isRecognizedMediaCodecColorRange(range) || fallback.rangeSpecified,
    )
}
