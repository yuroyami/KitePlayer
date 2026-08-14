package io.github.yuroyami.kiteplayer.output

import android.media.MediaCodecInfo
import android.media.MediaFormat
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.Vp9BitDepth
import io.github.yuroyami.kiteplayer.spi.Vp9ChromaSubsampling
import io.github.yuroyami.kiteplayer.spi.Vp9CodecConfiguration
import io.github.yuroyami.kiteplayer.spi.Vp9Level
import io.github.yuroyami.kiteplayer.spi.Vp9Profile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCodecConfigurationTest {
    @Test
    fun exactColorHintsAreForwardedWithoutInventingUnrepresentableStandards() {
        val bt709 = mediaCodecColorHints(
            ColorSpaceInfo(
                matrix = ColorMatrix.Bt709,
                primaries = ColorPrimaries.Bt709,
                transfer = ColorTransfer.Bt709,
                fullRange = true,
            ),
        )
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, bt709.standard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, bt709.transfer)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, bt709.range)

        val generic601 = mediaCodecColorHints(
            ColorSpaceInfo(
                matrix = ColorMatrix.Bt601,
                primaries = ColorPrimaries.Bt601,
                transfer = ColorTransfer.Bt601,
            ),
        )
        assertNull(generic601.standard, "the model cannot distinguish Android's PAL and NTSC keys")
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, generic601.transfer)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, generic601.range)

        val pal601 = mediaCodecColorHints(
            ColorSpaceInfo(
                matrix = ColorMatrix.Bt470bg,
                primaries = ColorPrimaries.Bt470bg,
                transfer = ColorTransfer.Bt709,
            ),
        )
        assertEquals(MediaFormat.COLOR_STANDARD_BT601_PAL, pal601.standard)

        val absent = mediaCodecColorHints(
            ColorSpaceInfo(
                matrix = ColorMatrix.Unspecified,
                primaries = ColorPrimaries.Unspecified,
                transfer = ColorTransfer.Unspecified,
                rangeSpecified = false,
            ),
        )
        assertNull(absent.standard)
        assertNull(absent.transfer)
        assertNull(absent.range)
    }

    @Test
    fun outputColorKeepsExact601StandardAndAmbiguousSdrTransferIdentity() {
        val fallback = ColorSpaceInfo(
            matrix = ColorMatrix.Bt2020Ncl,
            primaries = ColorPrimaries.Bt2020,
            transfer = ColorTransfer.Bt2020Ten,
            rangeSpecified = false,
        )

        val parsed = colorSpaceFromCodes(
            standard = MediaFormat.COLOR_STANDARD_BT601_PAL,
            transferCode = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            range = null,
            fallback = fallback,
        )

        assertEquals(ColorMatrix.Bt470bg, parsed.matrix)
        assertEquals(ColorPrimaries.Bt470bg, parsed.primaries)
        assertEquals(ColorTransfer.Bt2020Ten, parsed.transfer)
        assertFalse(parsed.rangeSpecified)
    }

    @Test
    fun directAdmissionRefusesAuthoritativeColorThatAndroidCannotExpress() {
        assertTrue(canRepresentMediaCodecColorExactly(null))
        assertTrue(
            canRepresentMediaCodecColorExactly(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt470bg,
                    primaries = ColorPrimaries.Bt470bg,
                    transfer = ColorTransfer.Bt709,
                ),
            ),
        )
        assertFalse(
            canRepresentMediaCodecColorExactly(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt601,
                    primaries = ColorPrimaries.Bt601,
                    transfer = ColorTransfer.Bt601,
                ),
            ),
        )
        assertFalse(
            canRepresentMediaCodecColorExactly(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt2020Cl,
                    primaries = ColorPrimaries.Bt2020,
                    transfer = ColorTransfer.Pq,
                ),
            ),
        )
        assertTrue(
            canRepresentMediaCodecColorExactly(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt709,
                    primaries = ColorPrimaries.Bt709,
                    transfer = ColorTransfer.Bt709,
                    rangeSpecified = false,
                ),
            ),
            "a conventional SDR guess is exact for standard/transfer even when range was absent",
        )
        val sdGuess = ColorSpaceInfo.guessFor(576)
        val hdGuess = ColorSpaceInfo.guessFor(1080)
        assertFalse(sdGuess.rangeSpecified)
        assertFalse(hdGuess.rangeSpecified)
        assertNull(mediaCodecColorHints(sdGuess).range)
        assertNull(mediaCodecColorHints(hdGuess).range)
    }

    @Test
    fun composeHardwareImagesAcceptOnlyExactlyTaggedBt709Primaries() {
        assertTrue(isComposeSrgbSafeColor(ColorSpaceInfo()))
        assertTrue(isComposeSrgbSafeColor(ColorSpaceInfo(transfer = ColorTransfer.Srgb)))
        assertEquals(
            AndroidRgbColorSpace.Bt709,
            androidRgbColorSpace(ColorSpaceInfo(transfer = ColorTransfer.Bt709)),
        )
        assertEquals(
            AndroidRgbColorSpace.Srgb,
            androidRgbColorSpace(ColorSpaceInfo(transfer = ColorTransfer.Srgb)),
        )
        assertFalse(
            isComposeSrgbSafeColor(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt601,
                    primaries = ColorPrimaries.Bt601,
                    transfer = ColorTransfer.Bt601,
                ),
            ),
        )
        assertFalse(
            isComposeSrgbSafeColor(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt2020Ncl,
                    primaries = ColorPrimaries.Bt2020,
                    transfer = ColorTransfer.Bt709,
                ),
            ),
        )
        assertFalse(isComposeSrgbSafeColor(ColorSpaceInfo(transfer = ColorTransfer.Pq)))
    }

    @Test
    fun avcCarriesExactConstrainedProfileAndLevel() {
        val parsed = parseMediaCodecConfiguration("h264", avcc(66, 0xC0, 40))!!

        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, parsed.mime)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, parsed.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel4, parsed.level)
        assertEquals(4, parsed.csd!!.nalLengthSize)
    }

    @Test
    fun avcDistinguishesLevel1bAndConstrainedHigh() {
        val level1b = parseMediaCodecConfiguration("avc1", avcc(66, 0xD0, 11))!!
        val constrainedHigh = parseMediaCodecConfiguration("avc", avcc(100, 0x0C, 52))!!

        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel1b, level1b.level)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh, constrainedHigh.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel52, constrainedHigh.level)
    }

    @Test
    fun avcRefusesAmbiguousOrWideProfileRecords() {
        assertNull(parseMediaCodecConfiguration("h264", null))
        assertNull(parseMediaCodecConfiguration("h264", avcc(66, 0, 40).also { it[11] = 41 }))
        assertNull(parseMediaCodecConfiguration("h264", avcc(66, 0x01, 40)))
        assertNull(parseMediaCodecConfiguration("h264", avcc(66, 0, 9)))
        assertNull(parseMediaCodecConfiguration("h264", avcc(110, 0, 40)))
    }

    @Test
    fun hevcCarriesExactMainTierAndHighTierLevels() {
        val main = parseMediaCodecConfiguration("hvc1", hvcc(profile = 1, level = 120))!!
        val high = parseMediaCodecConfiguration("hevc", hvcc(profile = 1, level = 150, highTier = true))!!

        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, main.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, main.level)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5, high.level)
        assertEquals(4, high.csd!!.nalLengthSize)
    }

    @Test
    fun hevcRefusesReservedBitsWideColorAndInvalidTier() {
        assertNull(
            parseMediaCodecConfiguration(
                "hevc",
                hvcc(profile = 1, level = 120).also { it[16] = 0x3D },
            ),
        )
        assertNull(parseMediaCodecConfiguration("hevc", hvcc(profile = 2, level = 120, bitDepth = 10)))
        assertNull(parseMediaCodecConfiguration("hevc", hvcc(profile = 1, level = 120, bitDepth = 10)))
        assertNull(parseMediaCodecConfiguration("hevc", hvcc(profile = 1, level = 90, highTier = true)))
        assertNull(parseMediaCodecConfiguration("hevc", hvcc(profile = 1, level = 91)))
    }

    @Test
    fun vp9ParsesCompleteWebmCodecPrivate() {
        val private = vp9CodecPrivate(profile = 0, level = 41, bitDepth = 8, chroma = 1)
        val parsed = parseMediaCodecConfiguration("vp9", private)!!

        assertEquals(MediaFormat.MIMETYPE_VIDEO_VP9, parsed.mime)
        assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Profile0, parsed.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Level41, parsed.level)
        assertContentEquals(private, parsed.opaqueCsd)
    }

    @Test
    fun vp9UsesTypedStreamMetadataWhenContainerHasNoCodecPrivate() {
        val typed = Vp9CodecConfiguration(
            profile = Vp9Profile.Profile0,
            level = Vp9Level.Level4_1,
            bitDepth = Vp9BitDepth.Eight,
            chromaSubsampling = Vp9ChromaSubsampling.Yuv420,
        )

        val parsed = parseMediaCodecConfiguration("vp9", null, typed)!!
        assertEquals(MediaFormat.MIMETYPE_VIDEO_VP9, parsed.mime)
        assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Profile0, parsed.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Level41, parsed.level)
        assertNull(parsed.opaqueCsd)
        assertNull(parseMediaCodecConfiguration("vp9", null, typed.copy(level = null)))
        assertNull(
            parseMediaCodecConfiguration("vp9", vp9CodecPrivate(level = 40), typed),
            "typed and container declarations must not silently disagree",
        )
        val colocated420 = parseMediaCodecConfiguration(
            "vp9",
            vp9CodecPrivate(level = 41, chroma = 1),
            typed,
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.VP9Level41,
            colocated420?.level,
            "typed 4:2:0 is compatible with either legal Profile-0 vpcC siting code",
        )

        val partialPrivate = byteArrayOf(1, 1, 0, 2, 1, 41)
        val mergedPartial = parseMediaCodecConfiguration("vp9", partialPrivate, typed)
        assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Level41, mergedPartial?.level)
        assertContentEquals(
            partialPrivate,
            mergedPartial?.opaqueCsd,
            "valid WebM feature subsets must be preserved after typed metadata completes them",
        )
    }

    @Test
    fun vp9ParsesRawAndFullBoxVpcc() {
        val raw = vpcc(profile = 0, level = 31, bitDepth = 8, chroma = 0)
        val fullBox = byteArrayOf(1, 0, 0, 0) + raw

        for (record in listOf(raw, fullBox)) {
            val parsed = parseMediaCodecConfiguration("vp09", record)!!
            assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Profile0, parsed.profile)
            assertEquals(MediaCodecInfo.CodecProfileLevel.VP9Level31, parsed.level)
            assertNull(parsed.opaqueCsd)
            val color = requireNotNull(parsed.declaredColor)
            assertEquals(ColorMatrix.Bt709, color.matrix)
            assertEquals(ColorPrimaries.Bt709, color.primaries)
            assertEquals(ColorTransfer.Bt709, color.transfer)
            assertFalse(color.fullRange)
            assertTrue(color.rangeSpecified)
        }
    }

    @Test
    fun vp9VpccColorIsMergedExactlyAndConflictsAreRefused() {
        val parsed = parseMediaCodecConfiguration(
            "vp9",
            vpcc(primaries = 9, transfer = 14, matrix = 9, fullRange = true),
        )!!
        val declared = requireNotNull(parsed.declaredColor)
        assertEquals(ColorMatrix.Bt2020Ncl, declared.matrix)
        assertEquals(ColorPrimaries.Bt2020, declared.primaries)
        assertEquals(ColorTransfer.Bt2020Ten, declared.transfer)
        assertTrue(declared.fullRange)

        val partial = ColorSpaceInfo(
            matrix = ColorMatrix.Unspecified,
            primaries = ColorPrimaries.Unspecified,
            transfer = ColorTransfer.Unspecified,
            chromaLocation = io.github.yuroyami.kiteplayer.spi.ChromaLocation.Unspecified,
            rangeSpecified = false,
        )
        assertEquals(declared, mergeMediaCodecColor(partial, declared))
        assertNull(
            mergeMediaCodecColor(declared.copy(fullRange = false), declared),
            "an authoritative range conflict must stay off MediaCodec",
        )
        assertNull(
            mergeMediaCodecColor(declared.copy(matrix = ColorMatrix.Bt709), declared),
            "an authoritative matrix conflict must stay off MediaCodec",
        )

        val displayP3 = requireNotNull(
            parseMediaCodecConfiguration(
                "vp9",
                vpcc(primaries = 12, transfer = 13, matrix = 1),
            )?.declaredColor,
        )
        assertEquals(ColorPrimaries.DisplayP3, displayP3.primaries)
        assertEquals(ColorTransfer.Srgb, displayP3.transfer)
        assertFalse(
            canRepresentMediaCodecColorExactly(displayP3),
            "Android MediaFormat has no exact Display-P3 video standard",
        )
        assertNull(
            mergeMediaCodecColor(
                ColorSpaceInfo(
                    matrix = ColorMatrix.Bt709,
                    primaries = ColorPrimaries.Bt709,
                    transfer = ColorTransfer.Srgb,
                ),
                displayP3,
            ),
            "a custom source must not overwrite authoritative vpcC Display-P3 primaries",
        )
    }

    @Test
    fun vp9RefusesIncompleteWideOrHdrDeclarations() {
        assertNull(parseMediaCodecConfiguration("vp9", byteArrayOf()))
        assertNull(parseMediaCodecConfiguration("vp9", vp9CodecPrivate().copyOf(9)))
        assertNull(parseMediaCodecConfiguration("vp9", vp9CodecPrivate(profile = 1)))
        assertNull(parseMediaCodecConfiguration("vp9", vp9CodecPrivate(bitDepth = 10)))
        assertNull(parseMediaCodecConfiguration("vp9", vp9CodecPrivate(chroma = 2)))
        assertNull(parseMediaCodecConfiguration("vp9", vp9CodecPrivate(level = 42)))
        assertEquals(
            ColorTransfer.Pq,
            parseMediaCodecConfiguration("vp9", vpcc(transfer = 16))?.declaredColor?.transfer,
        )
        assertNull(parseMediaCodecConfiguration("vp9", vpcc().also { it[7] = 1 }))
        assertNull(
            parseMediaCodecConfiguration(
                "vp9",
                vp9CodecPrivate() + byteArrayOf(1, 1, 0),
            ),
        )
    }

    @Test
    fun av1ParsesRealMain8ConfigurationRecord() {
        val av1c = byteArrayOf(
            0x81.toByte(), 0x01, 0x0C, 0x00,
            0x0A, 0x0B, 0x00, 0x00, 0x00, 0x0C, 0xC4.toByte(), 0xFF.toByte(),
            0x67, 0x00, 0xBE.toByte(), 0x00, 0x10,
        )
        val parsed = parseMediaCodecConfiguration("av1", av1c)!!

        assertEquals(MediaFormat.MIMETYPE_VIDEO_AV1, parsed.mime)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8, parsed.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level21, parsed.level)
        assertContentEquals(av1c, parsed.opaqueCsd)
    }

    @Test
    fun av1RefusesWideHdrOrMalformedConfiguration() {
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[0] = 1 }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[1] = 0x20 }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[2] = 0x8C.toByte() }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[2] = 0x4C }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[2] = 0x08 }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[3] = 1 }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().also { it[4] = 0x02 }))
        assertNull(parseMediaCodecConfiguration("av01", av1c().copyOf(6)))
    }

    @Test
    fun av1AcceptsAValidRecordWithOptionalConfigObusAbsent() {
        val headerOnly = av1c().copyOf(4)

        val parsed = parseMediaCodecConfiguration("av1", headerOnly)!!

        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8, parsed.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level2, parsed.level)
        assertContentEquals(headerOnly, parsed.opaqueCsd)
    }

    @Test
    fun advertisedAvcVp9AndAv1LevelsMustCoverTheRequirement() {
        assertTrue(
            advertisedProfileLevelSupports(
                mime = MediaFormat.MIMETYPE_VIDEO_AVC,
                advertisedProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                advertisedLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                requiredProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                requiredLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            ),
        )
        assertTrue(
            supports(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel51,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            ),
        )
        assertFalse(
            supports(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            ),
        )
        assertTrue(
            supports(
                MediaFormat.MIMETYPE_VIDEO_VP9,
                MediaCodecInfo.CodecProfileLevel.VP9Profile0,
                MediaCodecInfo.CodecProfileLevel.VP9Level6,
                MediaCodecInfo.CodecProfileLevel.VP9Level41,
            ),
        )
        assertTrue(
            supports(
                MediaFormat.MIMETYPE_VIDEO_AV1,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
                MediaCodecInfo.CodecProfileLevel.AV1Level5,
                MediaCodecInfo.CodecProfileLevel.AV1Level31,
            ),
        )
    }

    @Test
    fun advertisedHevcTierAndProfileMustCoverTheRequirement() {
        val profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        assertTrue(
            supports(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                profile,
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
            ),
        )
        assertFalse(
            supports(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                profile,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4,
            ),
        )
        assertFalse(
            advertisedProfileLevelSupports(
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
                advertisedProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                advertisedLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
                requiredProfile = profile,
                requiredLevel = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
            ),
        )
    }

    @Test
    fun unknownLevelsAndMimeAreRefusedWithoutThrowing() {
        assertFalse(
            supports(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                Int.MAX_VALUE,
            ),
        )
        assertFalse(
            supports(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                Int.MAX_VALUE,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            ),
        )
        assertFalse(
            advertisedProfileLevelSupports(
                mime = "video/unknown",
                advertisedProfile = 1,
                advertisedLevel = 1,
                requiredProfile = 1,
                requiredLevel = 1,
            ),
        )
    }

    private fun supports(mime: String, profile: Int, advertised: Int, required: Int): Boolean =
        advertisedProfileLevelSupports(
            mime = mime,
            advertisedProfile = profile,
            advertisedLevel = advertised,
            requiredProfile = profile,
            requiredLevel = required,
        )

    private fun avcc(profile: Int, compatibility: Int, level: Int): ByteArray = byteArrayOf(
        1, profile.toByte(), compatibility.toByte(), level.toByte(),
        0xFF.toByte(), 0xE1.toByte(), 0, 4,
        0x67, profile.toByte(), compatibility.toByte(), level.toByte(),
        1, 0, 2, 0x68, 0xEE.toByte(),
    )

    private fun hvcc(
        profile: Int,
        level: Int,
        highTier: Boolean = false,
        bitDepth: Int = 8,
    ): ByteArray = byteArrayOf(
        1,
        (profile or if (highTier) 0x20 else 0).toByte(),
        0, 0, 0, 0,
        0, 0, 0, 0, 0, 0,
        level.toByte(),
        0xF0.toByte(), 0,
        0xFC.toByte(), 0xFD.toByte(),
        (0xF8 or (bitDepth - 8)).toByte(),
        (0xF8 or (bitDepth - 8)).toByte(),
        0, 0, 0x0F, 3,
        0xA2.toByte(), 0, 1, 0, 2, 0x44, 1,
        0xA0.toByte(), 0, 1, 0, 2, 0x40, 1,
        0xA1.toByte(), 0, 1, 0, 3, 0x42, 1, 2,
    )

    private fun vp9CodecPrivate(
        profile: Int = 0,
        level: Int = 41,
        bitDepth: Int = 8,
        chroma: Int = 1,
    ): ByteArray = byteArrayOf(
        1, 1, profile.toByte(),
        2, 1, level.toByte(),
        3, 1, bitDepth.toByte(),
        4, 1, chroma.toByte(),
    )

    private fun vpcc(
        profile: Int = 0,
        level: Int = 31,
        bitDepth: Int = 8,
        chroma: Int = 1,
        primaries: Int = 1,
        transfer: Int = 1,
        matrix: Int = 1,
        fullRange: Boolean = false,
    ): ByteArray = byteArrayOf(
        profile.toByte(), level.toByte(),
        ((bitDepth shl 4) or (chroma shl 1) or if (fullRange) 1 else 0).toByte(),
        primaries.toByte(), transfer.toByte(), matrix.toByte(),
        0, 0,
    )

    private fun av1c(): ByteArray = byteArrayOf(
        0x81.toByte(), 0,
        0x0C, 0,
        0x0A, 1, 0,
    )
}
