package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kitecodec.CodecId
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecoderFallbackTest {

    @Test
    fun adapterReplayRestoresSyntheticTimestampSeedAndSharesColorWarningLatch() {
        val continuity = VideoDecoderContinuity()
        val step = Pts(40_000)

        assertEquals(Pts.Zero, continuity.timestamp(null, step, null, isKeyframe = true))
        assertEquals(Pts(40_000), continuity.timestamp(null, step, null, isKeyframe = false))
        assertTrue(continuity.claimColorWarning())

        // A fresh software wrapper is flushed to the current generation before replay begins.
        continuity.resetEpoch()
        continuity.beginReplay()
        assertEquals(Pts.Zero, continuity.timestamp(null, step, null, isKeyframe = true))
        assertEquals(Pts(40_000), continuity.timestamp(null, step, null, isKeyframe = false))
        assertEquals(Pts(80_000), continuity.timestamp(null, step, null, isKeyframe = false))
        assertFalse(continuity.claimColorWarning(), "fallback wrapper must not repeat the stream warning")
    }

    @Test
    fun adapterSeekResetsTimestampEpochButNotColorWarningTruth() {
        val continuity = VideoDecoderContinuity()
        assertEquals(Pts(123), continuity.timestamp(Pts(123), null, null, isKeyframe = true))
        assertTrue(continuity.claimColorWarning())
        continuity.resetEpoch()
        assertEquals(Pts.Zero, continuity.timestamp(null, Pts(1), null, isKeyframe = true))
        assertFalse(continuity.claimColorWarning())
    }

    /**
     * The S2.b arm, found red by the first VideoToolbox matrix run (tsoffset1400.ts): an hwaccel
     * attach succeeds cheaply and the hardware then refuses the very FIRST send, before any
     * output exists. Before the first confirmation the retained window still holds every packet
     * since open, so falling back and replaying from the head is complete and safe; failing
     * terminal here turned a refusable stream into a dead player.
     */
    @Test
    fun hardwareFailingBeforeAnyOutputFallsBackFromTheStreamHead() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 1) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertEquals(listOf(CodecId.H264MediaCodec, null), h.opens)
        assertEquals(1, h.warnings.size)
        assertEquals(HwdecStatus.Software, decoder.hardware)
        assertDelivered(decoder, h.packet(2), 2)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun hardwareOpenRefusalWarnsOnceAndOpensSoftware() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { throw IllegalStateException("no codec") }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertEquals(listOf(CodecId.H264MediaCodec, null), h.opens)
        assertEquals(1, h.warnings.size)
        assertEquals(HwdecStatus.Software, decoder.hardware)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun sendFailureOnThirdPacketReplaysTwoFrameGopByOrdinal() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 3) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        assertDelivered(decoder, h.packet(3), 3)
        assertEquals(HwdecStatus.Software, decoder.hardware)
        assertEquals(1, h.warnings.size)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun flaglessOutputsConfirmTheBoundaryByTimestamp() = runBlocking {
        // FFmpeg's mediacodec wrapper marks no output frame as a keyframe. Confirmation must
        // then come from the timestamp, or every failure before the cap is wrongly terminal.
        val flagless: (TestPacket) -> List<FrameSpec> = { packet ->
            listOf(FrameSpec(packet.id, keyframe = false))
        }
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 3, output = flagless) }
            softwareFactory = { ScriptDecoder(ledger, HwdecStatus.Software, output = flagless) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        assertDelivered(decoder, h.packet(3), 3)
        assertEquals(HwdecStatus.Software, decoder.hardware)
        assertEquals(1, h.warnings.size)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun flaglessOutputsReleaseTheWindowBeforeTheCap() = runBlocking {
        // The A3 device shape: keyframed 8 MiB packets, outputs never flagged. Without the
        // timestamp confirmation the window kept every packet, hit the 16 MiB cap mid-file and
        // failed terminally with no seek to save it.
        val flagless: (TestPacket) -> List<FrameSpec> = { packet ->
            listOf(FrameSpec(packet.id, keyframe = false))
        }
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, output = flagless) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        val eightMiB = 8 * 1024 * 1024
        assertDelivered(decoder, h.packet(1, keyframe = true, bytes = eightMiB), 1)
        assertDelivered(decoder, h.packet(2, keyframe = true, bytes = eightMiB), 2)
        assertDelivered(decoder, h.packet(3, keyframe = true, bytes = eightMiB), 3)
        assertDelivered(decoder, h.packet(4, keyframe = true, bytes = eightMiB), 4)
        assertEquals(h.hardwareStatus, decoder.hardware, "the window released; nothing demoted")
        assertTrue(h.warnings.isEmpty())
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun receiveFailureAfterOneFrameReplaysFromRetainedKeyframe() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failReceiveAt = 2) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertNull(decoder.receive()) // Fails, replays keyframe, suppresses exactly its one output.
        assertDelivered(decoder, h.packet(2), 2)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun replayHonoursSendReceiveBackpressure() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 3) }
            softwareFactory = {
                ScriptDecoder(
                    ledger = ledger,
                    hardware = HwdecStatus.Software,
                    backpressureOnce = setOf(2),
                    backpressureOutput = { packet -> FrameSpec(packet.id - 1, keyframe = true) },
                    output = { packet ->
                        if (packet.id == 1) emptyList() else listOf(FrameSpec(packet.id, packet.isKeyframe))
                    },
                )
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        assertDelivered(decoder, h.packet(3), 3)
        assertTrue(h.software().backpressureObserved)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun hiddenReplayBackpressureIsAbsorbedBeforeCallerPacketIsAccepted() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 3) }
            softwareFactory = {
                ScriptDecoder(
                    ledger = ledger,
                    hardware = HwdecStatus.Software,
                    backpressureOnce = setOf(3),
                    backpressureOutput = { FrameSpec(2, keyframe = false) },
                    output = { packet ->
                        when (packet.id) {
                            1, 3 -> listOf(FrameSpec(packet.id, packet.isKeyframe))
                            else -> emptyList()
                        }
                    },
                )
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        assertDelivered(decoder, h.packet(3), 3)
        assertTrue(h.software().backpressureObserved)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun duplicatePtsDoNotChangeReplaySuppressionCount() = runBlocking {
        val duplicate = Pts(77)
        val h = Harness().apply {
            hardwareFactory = {
                ScriptDecoder(ledger, hardwareStatus, failSendAt = 3) { packet ->
                    listOf(FrameSpec(packet.id, packet.isKeyframe, duplicate))
                }
            }
            softwareFactory = {
                ScriptDecoder(ledger, HwdecStatus.Software) { packet ->
                    listOf(FrameSpec(packet.id, packet.isKeyframe, duplicate))
                }
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1, duplicate)
        assertDelivered(decoder, h.packet(2), 2, duplicate)
        assertDelivered(decoder, h.packet(3), 3, duplicate)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun noPtsSentinelDoesNotChangeReplaySuppressionCount() = runBlocking {
        val noPts = Pts(Long.MIN_VALUE)
        val h = Harness().apply {
            hardwareFactory = {
                ScriptDecoder(ledger, hardwareStatus, failSendAt = 3) { packet ->
                    listOf(FrameSpec(packet.id, packet.isKeyframe, noPts))
                }
            }
            softwareFactory = {
                ScriptDecoder(ledger, HwdecStatus.Software) { packet ->
                    listOf(FrameSpec(packet.id, packet.isKeyframe, noPts))
                }
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1, noPts)
        assertDelivered(decoder, h.packet(2), 2, noPts)
        assertDelivered(decoder, h.packet(3), 3, noPts)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun pendingKeyframeKeepsOlderGopThroughDelayedBFrameAndFailure() = runBlocking {
        val h = Harness().apply {
            val hardware = ScriptDecoder(ledger, hardwareStatus, failReceiveAt = 4) { packet ->
                when (packet.id) {
                    3 -> listOf(FrameSpec(20, keyframe = false), FrameSpec(3, keyframe = true))
                    else -> listOf(FrameSpec(packet.id, packet.isKeyframe))
                }
            }
            hardwareFactory = { hardware }
            softwareFactory = {
                ScriptDecoder(ledger, HwdecStatus.Software) { packet ->
                    when (packet.id) {
                        3 -> listOf(FrameSpec(20, keyframe = false), FrameSpec(3, keyframe = true))
                        else -> listOf(FrameSpec(packet.id, packet.isKeyframe))
                    }
                }
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        val candidate = h.packet(3, keyframe = true)
        assertTrue(decoder.send(candidate))
        candidate.close()
        assertFrame(decoder.receive(), 20).close()
        assertFrame(decoder.receive(), 3).close() // Failure occurs before candidate keyframe is observed.
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun confirmedHandoverMakesDeliveredKeyframeOrdinalOne() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 4) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        assertDelivered(decoder, h.packet(3, keyframe = true), 3)
        // Only keyframe 3 remains in the replay window. Its one output is suppressed, then 4 is next.
        assertDelivered(decoder, h.packet(4), 4)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun sixteenMiBCapCountsOldAndCandidateWindowsBeforeAcceptingCrossingPacket() = runBlocking {
        val h = Harness().apply {
            // The delayed frame belongs to the OLD window, so its timestamp sits BEFORE the
            // candidate keyframe's. It used to carry a fictional future pts, which was harmless
            // under flag-only confirmation and impossible in a conformant stream; the timestamp
            // confirmation made the contract notice.
            val delayedCandidate: (TestPacket) -> List<FrameSpec> = { packet ->
                if (packet.id == 3) listOf(FrameSpec(30, keyframe = false, pts = Pts(0)))
                else listOf(FrameSpec(packet.id, packet.isKeyframe))
            }
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, output = delayedCandidate) }
            softwareFactory = { ScriptDecoder(ledger, HwdecStatus.Software, output = delayedCandidate) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true, bytes = 8 * 1024 * 1024), 1)
        assertDelivered(decoder, h.packet(2, bytes = 6 * 1024 * 1024), 2)
        assertDelivered(decoder, h.packet(3, keyframe = true, bytes = 1 * 1024 * 1024), 30, expectedPts = Pts(0))
        assertDelivered(decoder, h.packet(4, bytes = 2 * 1024 * 1024), 4)
        assertEquals(HwdecStatus.Software, decoder.hardware)
        assertEquals(3, h.hardware().acceptedPacketIds.size)
        assertEquals(1, h.warnings.size)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun failureWithoutConfirmedKeyframeIsTypedAndNeverPresentedAsFallback() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1), 1)
        val packet = h.packet(2)
        val failure = assertFailsWith<PlaybackException> { decoder.send(packet) }
        packet.close()
        assertTrue(failure.error.message.contains("no replay keyframe was confirmed"))
        assertEquals(1, h.opens.size)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun requireReturnsNullOnOpenRefusalAndPropagatesRuntimeFailureWithoutSoftware() = runBlocking {
        val refused = Harness().apply { hardwareFactory = { error("refused") } }
        assertNull(refused.open(requireSelection()))
        assertEquals(listOf<CodecId?>(CodecId.H264MediaCodec), refused.opens)
        assertTrue(refused.warnings.isEmpty())
        refused.assertLedgerZero()

        val runtime = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 1) }
        }
        val decoder = requireNotNull(runtime.open(requireSelection()))
        val packet = runtime.packet(1, keyframe = true)
        val original = assertFailsWith<DecoderBoom> { decoder.send(packet) }
        packet.close()
        assertEquals("send 1", original.message)
        assertEquals(listOf<CodecId?>(CodecId.H264MediaCodec), runtime.opens)
        decoder.close()
        runtime.assertLedgerZero()
    }

    @Test
    fun offDoesNotProbeHardware() = runBlocking {
        val h = Harness().apply { hardwareFactory = { error("must not probe") } }
        val decoder = requireNotNull(
            h.open(DecoderSelection(null, mayFallback = false, requiresHardware = false)),
        )
        assertEquals(listOf<CodecId?>(null), h.opens)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun flushClosesWholeRetainedGopAndResetsEpoch() = runBlocking {
        val h = Harness()
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        sendOnly(decoder, h.packet(2))
        assertTrue(h.ledger.packetClones > 0)
        decoder.flush(Generation(9))
        assertEquals(0, h.ledger.packetClones)
        assertEquals(listOf(Generation(9)), h.hardware().flushes)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun postFlushDemotionReplaysInTheNewGeneration() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 3) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        decoder.flush(Generation(9))
        assertDelivered(decoder, h.packet(2, keyframe = true), 2, expectedGeneration = Generation(9))
        assertDelivered(decoder, h.packet(3), 3, expectedGeneration = Generation(9))
        assertEquals(listOf(Generation(9)), h.software().flushes)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun drainBackpressureIsResolvedInternallyBecauseCallerDoesNotRetryNull() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = {
                ScriptDecoder(
                    ledger = ledger,
                    hardware = hardwareStatus,
                    drainBackpressureOnce = true,
                    drainBackpressureOutput = FrameSpec(2, keyframe = false),
                    output = { packet ->
                        if (packet.id == 1) listOf(FrameSpec(1, keyframe = true)) else emptyList()
                    },
                )
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertTrue(decoder.send(null))
        assertFrame(decoder.receive(), 2).close()
        assertTrue(h.hardware().drainBackpressureObserved)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun drainFailureDropsQueuedHardwareCopyAndReplaysTailExactlyOnce() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = {
                ScriptDecoder(
                    ledger = ledger,
                    hardware = hardwareStatus,
                    failSendAt = 4,
                    drainBackpressureOnce = true,
                    drainBackpressureOutput = FrameSpec(3, keyframe = false),
                )
            }
            softwareFactory = {
                ScriptDecoder(ledger, HwdecStatus.Software) { packet ->
                    when (packet.id) {
                        1 -> listOf(FrameSpec(1, keyframe = true))
                        2 -> listOf(FrameSpec(2, keyframe = false), FrameSpec(3, keyframe = false))
                        else -> emptyList()
                    }
                }
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)

        assertTrue(decoder.send(null))
        assertFrame(decoder.receive(), 3).close()
        assertNull(decoder.receive(), "the queued hardware copy must not follow the replayed tail")
        assertTrue(h.hardware().drainBackpressureObserved)
        assertEquals(HwdecStatus.Software, decoder.hardware)

        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun demotionReportsSoftwareBeforeWarningAndSoftwareOpen() = runBlocking {
        lateinit var decoder: VideoDecoder
        var statusAtWarning: HwdecStatus? = null
        var statusAtSoftwareOpen: HwdecStatus? = null
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
            warningSink = { statusAtWarning = decoder.hardware }
            softwareFactory = {
                statusAtSoftwareOpen = decoder.hardware
                ScriptDecoder(ledger, HwdecStatus.Software)
            }
        }
        decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)

        assertEquals(HwdecStatus.Software, statusAtWarning)
        assertEquals(HwdecStatus.Software, statusAtSoftwareOpen)
        assertEquals(HwdecStatus.Software, decoder.hardware)

        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun failedSoftwareReopenClosesHardwareAndEveryRetainedOwner() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
            softwareFactory = { throw IllegalStateException("software gone") }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        val packet = h.packet(2)
        assertFailsWith<PlaybackException> { decoder.send(packet) }
        packet.close()
        decoder.close() // Terminal cleanup made close idempotent.
        h.assertLedgerZero()
    }

    @Test
    fun failedInitialSoftwareFlushClosesTheFreshDecoder() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
            softwareFactory = { ScriptDecoder(ledger, HwdecStatus.Software, failFlush = true) }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        val packet = h.packet(2)
        assertFailsWith<PlaybackException> { decoder.send(packet) }
        packet.close()
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun cancellationDuringSoftwareOpenCleansRetainedOwnersAndStaysCancellation() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
            softwareFactory = { throw CancellationException("cancel open") }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        val packet = h.packet(2)
        assertFailsWith<CancellationException> { decoder.send(packet) }
        packet.close()
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun cancellationDuringReplayClosesSoftwareAndRetainedOwners() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
            softwareFactory = {
                ScriptDecoder(
                    ledger = ledger,
                    hardware = HwdecStatus.Software,
                    failSendAt = 1,
                    failSendThrowable = CancellationException("cancel replay"),
                )
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        val packet = h.packet(2)
        assertFailsWith<CancellationException> { decoder.send(packet) }
        packet.close()
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun throwingWarningCallbackCannotStrandTheRetainedWindow() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failSendAt = 2) }
            warningSink = { throw IllegalStateException("warning callback") }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        val packet = h.packet(2)
        val failure = assertFailsWith<IllegalStateException> { decoder.send(packet) }
        packet.close()
        assertEquals("warning callback", failure.message)
        decoder.close()
        h.assertLedgerZero()
    }

    @Test
    fun delayedReplayShortageBecomesTypedFailureAtSoftwareDrain() = runBlocking {
        val h = Harness().apply {
            hardwareFactory = { ScriptDecoder(ledger, hardwareStatus, failReceiveAt = 3) }
            softwareFactory = {
                ScriptDecoder(ledger, HwdecStatus.Software) { packet ->
                    if (packet.id == 2) emptyList() else listOf(FrameSpec(packet.id, packet.isKeyframe))
                }
            }
        }
        val decoder = requireNotNull(h.open(autoSelection()))
        assertDelivered(decoder, h.packet(1, keyframe = true), 1)
        assertDelivered(decoder, h.packet(2), 2)
        assertNull(decoder.receive()) // Runtime failure; replay has reproduced only ordinal one.
        assertTrue(decoder.send(null))
        val failure = assertFailsWith<PlaybackException> { decoder.receive() }
        assertTrue(failure.error.message.contains("replay drained"))
        decoder.close()
        h.assertLedgerZero()
    }

    private suspend fun assertDelivered(
        decoder: VideoDecoder,
        packet: TestPacket,
        expectedId: Int,
        expectedPts: Pts = Pts(expectedId.toLong()),
        expectedGeneration: Generation = Generation.Initial,
    ) {
        try {
            while (!decoder.send(packet)) {
                requireNotNull(decoder.receive()) { "a false send must expose a frame" }.close()
            }
        } finally {
            packet.close()
        }
        assertFrame(decoder.receive(), expectedId, expectedPts, expectedGeneration).close()
    }

    private suspend fun sendOnly(decoder: VideoDecoder, packet: TestPacket) {
        try {
            assertTrue(decoder.send(packet))
        } finally {
            packet.close()
        }
    }

    private fun assertFrame(
        frame: VideoFrame?,
        id: Int,
        pts: Pts = Pts(id.toLong()),
        generation: Generation = Generation.Initial,
    ): TestFrame {
        val actual = assertIs<TestFrame>(frame)
        assertEquals(id, actual.id)
        assertEquals(pts, actual.pts)
        assertEquals(generation, actual.generation)
        return actual
    }

    private fun autoSelection(): DecoderSelection = DecoderSelection(
        HardwareRoute.NamedDecoder(CodecId.H264MediaCodec, HwdecKind.MediaCodec),
        mayFallback = true,
        requiresHardware = false,
    )

    private fun requireSelection(): DecoderSelection = DecoderSelection(
        HardwareRoute.NamedDecoder(CodecId.H264MediaCodec, HwdecKind.MediaCodec),
        mayFallback = false,
        requiresHardware = true,
    )
}

private class Harness {
    val ledger = OwnershipLedger()
    val opens = mutableListOf<CodecId?>()
    val warnings = mutableListOf<PlaybackWarning>()
    val hardwareStatus = HwdecStatus.HardwareWithDownload(HwdecKind.MediaCodec)
    var warningSink: (PlaybackWarning) -> Unit = {}

    var hardwareFactory: () -> ScriptDecoder = { ScriptDecoder(ledger, hardwareStatus) }
    var softwareFactory: () -> ScriptDecoder = { ScriptDecoder(ledger, HwdecStatus.Software) }

    private val openedHardware = mutableListOf<ScriptDecoder>()
    private val openedSoftware = mutableListOf<ScriptDecoder>()

    suspend fun open(selection: DecoderSelection): VideoDecoder? = openDecoderWithFallback(
        stream = PlayerStreamInfo(index = 0, kind = TrackKind.Video, codec = "h264"),
        selection = selection,
        open = { route ->
            // The ledger of opens stays decoder-name shaped: every route this suite drives is
            // the named-decoder shape, so the existing assertions keep their vocabulary.
            opens += (route as? HardwareRoute.NamedDecoder)?.decoder
            if (route == null) softwareFactory().also(openedSoftware::add)
            else hardwareFactory().also(openedHardware::add)
        },
        copyPacket = {
            val packet = it as TestPacket
            TestPacket(packet.id, packet.isKeyframe, packet.sizeBytes, ledger)
        },
        isKeyframe = { (it as TestFrame).keyframe },
        warn = { warning ->
            warnings += warning
            warningSink(warning)
        },
    )

    fun packet(id: Int, keyframe: Boolean = false, bytes: Int = 1): TestPacket =
        TestPacket(id, keyframe, bytes, ledger = null)

    fun hardware(): ScriptDecoder = openedHardware.single()
    fun software(): ScriptDecoder = openedSoftware.single()

    fun assertLedgerZero() {
        assertEquals(0, ledger.packetClones, "packet clone ledger")
        assertEquals(0, ledger.frames, "frame ledger")
        assertEquals(0, ledger.decoders, "decoder ledger")
    }
}

private class OwnershipLedger {
    var packetClones: Int = 0
    var frames: Int = 0
    var decoders: Int = 0
}

private class TestPacket(
    val id: Int,
    override val isKeyframe: Boolean,
    override val sizeBytes: Int,
    private val ledger: OwnershipLedger?,
) : PlayerPacket {
    private var closed = false

    init {
        ledger?.let { it.packetClones += 1 }
    }

    override val streamIndex: Int = 0
    override val pts: Pts = Pts(id.toLong())
    override val dts: Pts = pts
    override val duration: Pts = Pts(1)
    override val bytePosition: Long? = null

    override fun close() {
        check(!closed) { "packet $id closed twice" }
        closed = true
        ledger?.let { it.packetClones -= 1 }
    }
}

private data class FrameSpec(
    val id: Int,
    val keyframe: Boolean,
    val pts: Pts = Pts(id.toLong()),
)

private class TestFrame(
    val id: Int,
    val keyframe: Boolean,
    override val pts: Pts,
    override val generation: Generation,
    private val ledger: OwnershipLedger,
) : VideoFrame {
    private var closed = false

    init {
        ledger.frames += 1
    }

    override val duration: Pts = Pts(1)
    override val size: VideoSize = VideoSize(2, 2)
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
    override val hardwareSurface: HwSurfaceKind? = null
    override fun close() {
        check(!closed) { "frame $id closed twice" }
        closed = true
        ledger.frames -= 1
    }
}

private class DecoderBoom(message: String) : RuntimeException(message)

private class ScriptDecoder(
    private val ledger: OwnershipLedger,
    override val hardware: HwdecStatus,
    private val failSendAt: Int? = null,
    private val failSendThrowable: Throwable? = null,
    private val failReceiveAt: Int? = null,
    private val failFlush: Boolean = false,
    private val backpressureOnce: Set<Int> = emptySet(),
    private val backpressureOutput: (TestPacket) -> FrameSpec? = { null },
    private val drainBackpressureOnce: Boolean = false,
    private val drainBackpressureOutput: FrameSpec? = null,
    private val output: (TestPacket) -> List<FrameSpec> = { packet ->
        listOf(FrameSpec(packet.id, packet.isKeyframe))
    },
) : VideoDecoder {
    private val queued = ArrayDeque<TestFrame>()
    private val backpressured = mutableSetOf<Int>()
    private var sendCalls = 0
    private var receiveCalls = 0
    private var closed = false
    private var drained = false
    private var currentGeneration = Generation.Initial

    val acceptedPacketIds = mutableListOf<Int>()
    val flushes = mutableListOf<Generation>()
    var backpressureObserved: Boolean = false
        private set
    var drainBackpressureObserved: Boolean = false
        private set

    init {
        ledger.decoders += 1
    }

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed)
        sendCalls += 1
        if (sendCalls == failSendAt) throw failSendThrowable ?: DecoderBoom("send $sendCalls")
        if (packet == null) {
            if (drainBackpressureOnce && !drainBackpressureObserved) {
                drainBackpressureObserved = true
                drainBackpressureOutput?.let { spec ->
                    queued.addLast(TestFrame(spec.id, spec.keyframe, spec.pts, currentGeneration, ledger))
                }
                return false
            }
            drained = true
            return true
        }
        val testPacket = packet as TestPacket
        if (testPacket.id in backpressureOnce && backpressured.add(testPacket.id)) {
            backpressureObserved = true
            backpressureOutput(testPacket)?.let { spec ->
                queued.addLast(TestFrame(spec.id, spec.keyframe, spec.pts, currentGeneration, ledger))
            }
            return false
        }
        acceptedPacketIds += testPacket.id
        output(testPacket).forEach { spec ->
            queued.addLast(TestFrame(spec.id, spec.keyframe, spec.pts, currentGeneration, ledger))
        }
        return true
    }

    override suspend fun receive(): VideoFrame? {
        check(!closed)
        receiveCalls += 1
        if (receiveCalls == failReceiveAt) throw DecoderBoom("receive $receiveCalls")
        return queued.removeFirstOrNull()
    }

    override val isDrained: Boolean get() = drained && queued.isEmpty()

    override suspend fun flush(newGeneration: Generation) {
        if (failFlush) throw DecoderBoom("flush")
        while (queued.isNotEmpty()) queued.removeFirst().close()
        drained = false
        currentGeneration = newGeneration
        flushes += newGeneration
    }

    override fun close() {
        if (closed) return
        closed = true
        while (queued.isNotEmpty()) queued.removeFirst().close()
        ledger.decoders -= 1
    }
}
