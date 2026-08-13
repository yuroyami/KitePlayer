package io.github.yuroyami.kiteplayer.output

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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

/** A hardware decoder paired with the Surface target owned by one Android video renderer. */
internal class MediaCodecVideoDecoderFactory(
    private val target: MediaCodecSurfaceTarget,
) : VideoDecoderFactory {
    override val name: String = "Android MediaCodec direct Surface"

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (stream.kind != TrackKind.Video || !hwdec.allowsMediaCodec()) return null
        val size = stream.videoSize ?: return null
        if (size.width <= 0 || size.height <= 0) return null

        val codec = codecInput(stream) ?: return null
        val format = MediaFormat.createVideoFormat(codec.mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_PROFILE, codec.androidProfile)
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
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, codec.maxInputSize(size))
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
            normalizedQuarterTurn(stream.rotationDegrees).takeIf { it != 0 }?.let {
                setInteger(MediaFormat.KEY_ROTATION, it)
            }
        }
        val decoderInfo = findHardwareDecoder(format, codec.mime) ?: return null
        val decoder = MediaCodecVideoDecoder(
            codecName = decoderInfo.name,
            format = format,
            codecInput = codec,
            stream = stream,
            target = target,
        )
        return try {
            target.publishGeometry(size, normalizedQuarterTurn(stream.rotationDegrees))
            decoder
        } catch (failure: Throwable) {
            decoder.close()
            throw failure
        }
    }
}

private data class CodecInput(
    val mime: String,
    val androidProfile: Int,
    val csd: CodecSpecificData? = null,
    val opaqueCsd: ByteArray? = null,
) {
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
    val codec = stream.codec.lowercase()
    val extradata = stream.codecExtradata
    return when (codec) {
        "h264", "avc", "avc1" -> {
            val record = extradata ?: return null
            val androidProfile = androidAvcProfile(record.getOrNull(1)?.toInt()?.and(0xFF) ?: return null)
                ?: return null
            CodecInput(
                mime = MediaFormat.MIMETYPE_VIDEO_AVC,
                androidProfile = androidProfile,
                csd = AnnexB.parseAvcC(record) ?: return null,
            )
        }
        "hevc", "h265", "hev1", "hvc1" -> {
            val record = extradata ?: return null
            if (!isEightBitMainHevc(record)) return null
            CodecInput(
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
                androidProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                csd = AnnexB.parseHvcC(record) ?: return null,
            )
        }
        else -> null
    }
}

private fun androidAvcProfile(profileIdc: Int): Int? = when (profileIdc) {
    66 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
    77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
    88 -> MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
    100 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    else -> null
}

/** Initial direct tier is deliberately SDR 8-bit. Main10 and 4:2:2 keep the software fallback. */
private fun isEightBitMainHevc(record: ByteArray): Boolean {
    if (record.size < 23 || record[0].toInt() != 1) return false
    val profileIdc = record[1].toInt() and 0x1F
    val chromaFormat = record[16].toInt() and 0x03
    val lumaDepth = 8 + (record[17].toInt() and 0x07)
    val chromaDepth = 8 + (record[18].toInt() and 0x07)
    return profileIdc == 1 && chromaFormat in 0..1 && lumaDepth == 8 && chromaDepth == 8
}

private fun HwdecPolicy.allowsMediaCodec(): Boolean = when (this) {
    // The direct decoder cannot replay already-accepted packets into the backend's software
    // decoder after a vendor MediaCodec runtime failure. Until core owns cross-factory replay,
    // Auto must keep its documented software-fallback guarantee instead of selecting this path.
    HwdecPolicy.Auto -> false
    HwdecPolicy.Require -> true
    HwdecPolicy.Off -> false
    is HwdecPolicy.Prefer -> false
}

private fun findHardwareDecoder(format: MediaFormat, mime: String): MediaCodecInfo? =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
        !info.isEncoder &&
            info.isHardwareAccelerated &&
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
            runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
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
    private var outputColor: ColorSpaceInfo = stream.colorSpace ?: ColorSpaceInfo.guessFor(configuredSize.height)
    private var configuredSurface: android.view.Surface = fallbackSurface
    private var configuredSurfaceVersion: Long? = null

    override val isDrained: Boolean get() = drained

    init {
        try {
            synchronized(codecLock) {
                codec.configure(format, fallbackSurface, null, 0)
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
        processReleaseCommands()
        val replayCodecConfig = !outputEstablished
        codecCall { flush() }
        decoderEpoch += 1L
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
            runCatching { processReleaseCommands() }
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
                        codec.releaseOutputBuffer(command.outputIndex, requireNotNull(renderNanos))
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
        if (width > 0 && height > 0) {
            outputSize = VideoSize(
                width = width,
                height = height,
                pixelAspectNumerator = configuredSize.pixelAspectNumerator,
                pixelAspectDenominator = configuredSize.pixelAspectDenominator,
            )
            target.publishGeometry(outputSize, normalizedQuarterTurn(stream.rotationDegrees))
        }
        outputColor = colorSpaceFrom(format, outputColor)
    }

    private companion object {
        private const val DEQUEUE_POLL_US: Long = 2_000L
        private val EMPTY_INPUT: ByteArray = ByteArray(0)
    }
}

private data class PendingInput(val bytes: ByteArray, val ptsUs: Long, val eos: Boolean)

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun colorSpaceFrom(format: MediaFormat, fallback: ColorSpaceInfo): ColorSpaceInfo {
    val standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
    val transferCode = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
    val range = format.intOrNull(MediaFormat.KEY_COLOR_RANGE)
    val matrix = when (standard) {
        MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> ColorMatrix.Bt601
        MediaFormat.COLOR_STANDARD_BT2020 -> ColorMatrix.Bt2020Ncl
        MediaFormat.COLOR_STANDARD_BT709 -> ColorMatrix.Bt709
        else -> fallback.matrix
    }
    val primaries = when (standard) {
        MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> ColorPrimaries.Bt601
        MediaFormat.COLOR_STANDARD_BT2020 -> ColorPrimaries.Bt2020
        MediaFormat.COLOR_STANDARD_BT709 -> ColorPrimaries.Bt709
        else -> fallback.primaries
    }
    val transfer = when (transferCode) {
        MediaFormat.COLOR_TRANSFER_LINEAR -> ColorTransfer.Linear
        MediaFormat.COLOR_TRANSFER_ST2084 -> ColorTransfer.Pq
        MediaFormat.COLOR_TRANSFER_HLG -> ColorTransfer.Hlg
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> ColorTransfer.Bt709
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
        chromaLocation = fallback.chromaLocation.takeIf { it != ChromaLocation.Left } ?: ChromaLocation.Left,
    )
}
