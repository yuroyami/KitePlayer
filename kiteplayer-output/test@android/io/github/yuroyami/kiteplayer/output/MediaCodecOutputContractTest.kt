package io.github.yuroyami.kiteplayer.output

import android.media.MediaCodecInfo
import android.media.MediaFormat
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCodecOutputContractTest {
    @Test
    fun hdrToneMapUsesThePlatformBt709LimitedOutputContract() {
        listOf(ColorTransfer.Pq, ColorTransfer.Hlg).forEach { transfer ->
            val source = ColorSpaceInfo(
                matrix = ColorMatrix.Bt2020Ncl,
                primaries = ColorPrimaries.Bt2020,
                transfer = transfer,
            )
            val contract = requireNotNull(
                composeMediaCodecOutputContract(requirement(source), sdkInt = 31),
            )

            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.requestedColorTransfer)
            assertEquals(ColorSpaceInfo(), contract.trustedOutputColor)
            assertFalse(contract.requireExplicitOutputColor)
            assertTrue(
                requireNotNull(contract.validateOutput).invoke(
                    MediaCodecOutputColor(ColorSpaceInfo(), reliable = true),
                ),
            )
            assertFalse(
                contract.validateOutput.invoke(
                    MediaCodecOutputColor(
                        source.copy(transfer = ColorTransfer.Bt2020Ten),
                        reliable = true,
                    ),
                ),
                "tone-mapped output must not retain BT.2020 primaries",
            )
            assertFalse(
                contract.validateOutput.invoke(
                    MediaCodecOutputColor(ColorSpaceInfo(fullRange = true), reliable = true),
                ),
                "the platform tone-map contract is limited range",
            )
        }
    }

    @Test
    fun guessedInputNeedsARecognizedOutputStandardEvenAfterTransferRequestAcceptance() {
        val contract = requireNotNull(
            composeMediaCodecOutputContract(
                requirement(ColorSpaceInfo.guessFor(576)),
                sdkInt = 31,
            ),
        )
        assertTrue(contract.requireExplicitOutputColor)
        assertNull(contract.trustedOutputColor)

        val unspecified = mediaCodecOutputColorFromCodes(
            standard = 0,
            transferCode = 0,
            range = 0,
            fallback = ColorSpaceInfo.guessFor(576),
            outputContract = contract,
            sourceColorDeclared = true,
        )
        assertFalse(unspecified.reliable)
        assertFalse(requireNotNull(contract.validateOutput).invoke(unspecified))

        val bt709 = mediaCodecOutputColorFromCodes(
            standard = MediaFormat.COLOR_STANDARD_BT709,
            transferCode = null,
            range = MediaFormat.COLOR_RANGE_LIMITED,
            fallback = ColorSpaceInfo.guessFor(576),
            outputContract = contract,
            sourceColorDeclared = true,
        )
        assertTrue(bt709.reliable, "the accepted request proves the omitted SDR transfer only")
        assertEquals(ColorSpaceInfo(), bt709.colorSpace)
        assertTrue(contract.validateOutput.invoke(bt709))
    }

    @Test
    fun conventionalHdGuessStaysDirectWhileAmbiguousSdGuessesNeedOutputEvidence() {
        val kiteCodecSdGuess = ColorSpaceInfo(
            matrix = ColorMatrix.Bt470bg,
            primaries = ColorPrimaries.Bt470bg,
            transfer = ColorTransfer.Bt709,
            rangeSpecified = false,
        )
        listOf(
            ColorSpaceInfo.guessFor(576),
            kiteCodecSdGuess,
        ).forEach { guessed ->
            val contract = requireNotNull(
                composeMediaCodecOutputContract(requirement(guessed), sdkInt = 31),
            )

            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.requestedColorTransfer)
            assertTrue(contract.requireExplicitOutputColor)
            assertNull(contract.trustedOutputColor)
        }

        val conventionalHd = requireNotNull(
            composeMediaCodecOutputContract(requirement(ColorSpaceInfo.guessFor(1080)), sdkInt = 31),
        )
        assertNull(conventionalHd.requestedColorTransfer)
        assertFalse(conventionalHd.requireExplicitOutputColor)

        val declared = requireNotNull(
            composeMediaCodecOutputContract(requirement(ColorSpaceInfo()), sdkInt = 31),
        )
        assertNull(declared.requestedColorTransfer)
        assertFalse(declared.requireExplicitOutputColor)
    }

    @Test
    fun unknownColorCodesNeverBecomeExplicitEvidence() {
        val contract = MediaCodecOutputContract(requireExplicitOutputColor = true)
        val fallback = ColorSpaceInfo.Unspecified

        val detected = mediaCodecOutputColorFromCodes(
            standard = Int.MAX_VALUE,
            transferCode = Int.MAX_VALUE,
            range = Int.MAX_VALUE,
            fallback = fallback,
            outputContract = contract,
            sourceColorDeclared = false,
        )

        assertFalse(detected.reliable)
        assertEquals(fallback, detected.colorSpace)
        assertFalse(detected.colorSpace.rangeSpecified)
        assertFalse(isRecognizedMediaCodecColorStandard(0))
        assertFalse(isRecognizedMediaCodecColorTransfer(0))
        assertFalse(isRecognizedMediaCodecColorRange(0))
    }

    @Test
    fun directPreservationRequiresBothGpuAndMediaCodecColorRepresentation() {
        val srgb = ColorSpaceInfo(transfer = ColorTransfer.Srgb)
        assertTrue(isComposeGpuColorRepresentable(srgb))
        assertFalse(canRepresentMediaCodecColorExactly(srgb))
        assertNull(composeMediaCodecOutputContract(requirement(srgb), sdkInt = 31))
    }

    @Test
    fun entirelyUnspecifiedInputNeverBecomesTrustedToneMapMetadata() {
        val contract = requireNotNull(
            composeMediaCodecOutputContract(requirement(ColorSpaceInfo.Unspecified), sdkInt = 31),
        )

        assertTrue(contract.requireExplicitOutputColor)
        assertNull(contract.trustedOutputColor)
    }

    private fun requirement(color: ColorSpaceInfo): MediaCodecStreamRequirement =
        MediaCodecStreamRequirement(
            mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
            bitDepth = 10,
            colorSpace = color,
        )
}
