package io.github.yuroyami.kiteplayer.output

import android.media.MediaCodecInfo
import android.media.MediaFormat
import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.Vp9ChromaSubsampling
import io.github.yuroyami.kiteplayer.spi.Vp9CodecConfiguration

/** A container declaration translated to the exact profile and level MediaCodec must support. */
internal data class ParsedMediaCodecConfiguration(
    val mime: String,
    val profile: Int,
    val level: Int,
    /** Maximum coded component depth proved by the container configuration record. */
    val bitDepth: Int,
    /** AV1's seq_tier_0 bit. Other codecs encode tier in their Android level constant. */
    val highTier: Boolean = false,
    val csd: CodecSpecificData? = null,
    val opaqueCsd: ByteArray? = null,
    val declaredColor: ColorSpaceInfo? = null,
)

/**
 * Parses only self-describing container records. A missing or ambiguous declaration stays on the
 * software decoder because selecting a hardware profile by guesswork makes runtime fallback likely.
 */
internal fun parseMediaCodecConfiguration(
    codecName: String,
    extradata: ByteArray?,
    vp9: Vp9CodecConfiguration? = null,
): ParsedMediaCodecConfiguration? {
    return when (codecName.lowercase()) {
        "h264", "avc", "avc1" -> parseAvcConfiguration(extradata ?: return null)
        "hevc", "h265", "hev1", "hvc1" -> parseHevcConfiguration(extradata ?: return null)
        "vp9", "vp09" -> parseVp9Configuration(extradata, vp9)
        "av1", "av01" -> parseAv1Configuration(extradata ?: return null)
        else -> null
    }
}

private fun parseAvcConfiguration(record: ByteArray): ParsedMediaCodecConfiguration? {
    if (record.size < 12 || record[0].u8() != 1) return null
    val profileIdc = record[1].u8()
    val compatibility = record[2].u8()
    val levelIdc = record[3].u8()
    if (compatibility and AVC_RESERVED_COMPATIBILITY_MASK != 0) return null
    if (!avcHeaderMatchesFirstSps(record, profileIdc, compatibility, levelIdc)) return null

    val profile = when (profileIdc) {
        66 -> if (compatibility and AVC_CONSTRAINT_SET1 != 0) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
        77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        88 -> MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
        100 -> if (compatibility and AVC_CONSTRAINED_HIGH_MASK == AVC_CONSTRAINED_HIGH_MASK) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        }
        110 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        // Android has profile constants for 4:2:2/4:4:4, but avcC's four-byte header does not
        // prove their coded depth/chroma. Keep those records on the software decoder until the SPS
        // parser exposes the exact format instead of guessing from a permissive profile envelope.
        else -> return null
    }
    val level = androidAvcLevel(levelIdc, compatibility) ?: return null
    return ParsedMediaCodecConfiguration(
        mime = MediaFormat.MIMETYPE_VIDEO_AVC,
        profile = profile,
        level = level,
        bitDepth = if (profileIdc == 110) 10 else 8,
        csd = AnnexB.parseAvcC(record) ?: return null,
    )
}

private fun avcHeaderMatchesFirstSps(
    record: ByteArray,
    profileIdc: Int,
    compatibility: Int,
    levelIdc: Int,
): Boolean {
    val spsCount = record[5].u8() and 0x1F
    if (spsCount == 0) return false
    val firstLength = record.readU16(6) ?: return false
    if (firstLength < 4 || firstLength > record.size - 8) return false
    val firstSps = 8
    return record[firstSps].u8() and 0x1F == 7 &&
        record[firstSps + 1].u8() == profileIdc &&
        record[firstSps + 2].u8() == compatibility &&
        record[firstSps + 3].u8() == levelIdc
}

private fun androidAvcLevel(levelIdc: Int, compatibility: Int): Int? = when (levelIdc) {
    10 -> MediaCodecInfo.CodecProfileLevel.AVCLevel1
    11 -> if (compatibility and AVC_CONSTRAINT_SET3 != 0) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b
    } else {
        MediaCodecInfo.CodecProfileLevel.AVCLevel11
    }
    12 -> MediaCodecInfo.CodecProfileLevel.AVCLevel12
    13 -> MediaCodecInfo.CodecProfileLevel.AVCLevel13
    20 -> MediaCodecInfo.CodecProfileLevel.AVCLevel2
    21 -> MediaCodecInfo.CodecProfileLevel.AVCLevel21
    22 -> MediaCodecInfo.CodecProfileLevel.AVCLevel22
    30 -> MediaCodecInfo.CodecProfileLevel.AVCLevel3
    31 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
    32 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
    40 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
    41 -> MediaCodecInfo.CodecProfileLevel.AVCLevel41
    42 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
    50 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
    51 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
    52 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
    60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel6
    61 -> MediaCodecInfo.CodecProfileLevel.AVCLevel61
    62 -> MediaCodecInfo.CodecProfileLevel.AVCLevel62
    else -> null
}

private fun parseHevcConfiguration(record: ByteArray): ParsedMediaCodecConfiguration? {
    if (record.size < 23 || record[0].u8() != 1) return null
    if (record[13].u8() and 0xF0 != 0xF0) return null
    if (record[15].u8() and 0xFC != 0xFC) return null
    if (record[16].u8() and 0xFC != 0xFC) return null
    if (record[17].u8() and 0xF8 != 0xF8) return null
    if (record[18].u8() and 0xF8 != 0xF8) return null

    val profileTier = record[1].u8()
    val profileSpace = profileTier ushr 6
    val highTier = profileTier and 0x20 != 0
    val profileIdc = profileTier and 0x1F
    val chromaFormat = record[16].u8() and 0x03
    val lumaDepth = 8 + (record[17].u8() and 0x07)
    val chromaDepth = 8 + (record[18].u8() and 0x07)
    if (profileSpace != 0 || chromaFormat !in 0..1 || lumaDepth != chromaDepth) return null
    val profile = when (profileIdc) {
        1 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain.takeIf { lumaDepth == 8 }
        2 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10.takeIf { lumaDepth in 8..10 }
        else -> null
    } ?: return null

    val level = androidHevcLevel(record[12].u8(), highTier) ?: return null
    return ParsedMediaCodecConfiguration(
        mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
        profile = profile,
        level = level,
        bitDepth = lumaDepth,
        csd = AnnexB.parseHvcC(record) ?: return null,
    )
}

private fun androidHevcLevel(levelIdc: Int, highTier: Boolean): Int? {
    if (highTier && levelIdc < 120) return null
    val main = when (levelIdc) {
        30 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1
        60 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2
        63 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21
        90 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3
        93 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31
        120 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
        123 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
        150 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5
        153 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
        156 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52
        180 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6
        183 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61
        186 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62
        else -> return null
    }
    if (!highTier) return main
    return when (levelIdc) {
        30 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1
        60 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2
        63 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21
        90 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3
        93 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31
        120 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4
        123 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41
        150 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5
        153 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51
        156 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52
        180 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6
        183 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61
        186 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62
        else -> null
    }
}

private data class Vp9Metadata(
    val profile: Int?,
    val level: Int?,
    val bitDepth: Int?,
    val chromaSubsampling: Int?,
    val codecPrivate: ByteArray?,
    val color: ColorSpaceInfo? = null,
)

private fun parseVp9Configuration(
    record: ByteArray?,
    typed: Vp9CodecConfiguration?,
): ParsedMediaCodecConfiguration? {
    if (typed?.chromaSubsampling == Vp9ChromaSubsampling.Monochrome) return null
    val fromRecord = record?.let { parseVp9CodecPrivate(it) ?: parseVp9Vpcc(it) }
    if (record != null && fromRecord == null) return null
    val metadata = mergeVp9Metadata(typed?.toMediaCodecMetadata(), fromRecord) ?: return null
    val profile = metadata.profile ?: return null
    val level = metadata.level ?: return null
    val bitDepth = metadata.bitDepth ?: return null
    val chromaSubsampling = metadata.chromaSubsampling ?: return null
    if (metadata.color?.isHdr == true && bitDepth < 10) return null
    val androidProfile = when (profile) {
        0 -> MediaCodecInfo.CodecProfileLevel.VP9Profile0
            .takeIf { bitDepth == 8 && chromaSubsampling in 0..1 }
        1 -> MediaCodecInfo.CodecProfileLevel.VP9Profile1
            .takeIf { bitDepth == 8 && chromaSubsampling in 2..3 }
        2 -> MediaCodecInfo.CodecProfileLevel.VP9Profile2
            .takeIf { bitDepth == 10 && chromaSubsampling in 0..1 }
        3 -> MediaCodecInfo.CodecProfileLevel.VP9Profile3
            .takeIf { bitDepth == 10 && chromaSubsampling in 2..3 }
        else -> null
    } ?: return null
    return ParsedMediaCodecConfiguration(
        mime = MediaFormat.MIMETYPE_VIDEO_VP9,
        profile = androidProfile,
        level = androidVp9Level(level) ?: return null,
        bitDepth = bitDepth,
        opaqueCsd = metadata.codecPrivate,
        declaredColor = metadata.color,
    )
}

private fun Vp9CodecConfiguration.toMediaCodecMetadata(): Vp9Metadata = Vp9Metadata(
    profile = profile?.number,
    level = level?.code,
    bitDepth = bitDepth?.bits,
    chromaSubsampling = when (chromaSubsampling) {
        Vp9ChromaSubsampling.Yuv420 -> 0
        Vp9ChromaSubsampling.Yuv422 -> 2
        Vp9ChromaSubsampling.Yuv444 -> 3
        Vp9ChromaSubsampling.Monochrome -> null
        null -> null
    },
    codecPrivate = null,
    color = null,
)

private fun mergeVp9Metadata(typed: Vp9Metadata?, record: Vp9Metadata?): Vp9Metadata? {
    if (typed == null) return record
    if (record == null) return typed
    fun merge(left: Int?, right: Int?): Int? {
        if (left != null && right != null && left != right) return null
        return left ?: right
    }
    val profile = merge(typed.profile, record.profile) ?: return null
    val level = merge(typed.level, record.level) ?: return null
    val bitDepth = merge(typed.bitDepth, record.bitDepth) ?: return null
    // AVCodecParameters' pixel format proves 4:2:0 but cannot distinguish vpcC codes 0 and 1,
    // which differ only in sample siting. Preserve the record's exact code when it has one.
    val chroma = when {
        typed.chromaSubsampling in 0..1 && record.chromaSubsampling in 0..1 ->
            record.chromaSubsampling
        else -> merge(typed.chromaSubsampling, record.chromaSubsampling)
    } ?: return null
    return Vp9Metadata(profile, level, bitDepth, chroma, record.codecPrivate, record.color)
}

private fun parseVp9CodecPrivate(record: ByteArray): Vp9Metadata? {
    if (record.isEmpty()) return null
    val features = mutableMapOf<Int, Int>()
    var at = 0
    while (at < record.size) {
        if (record.size - at < 3) return null
        val idByte = record[at].u8()
        val length = record[at + 1].u8()
        if (idByte and 0x80 != 0 || idByte !in 1..4 || length != 1) return null
        if (features.put(idByte, record[at + 2].u8()) != null) return null
        at += 3
    }
    return Vp9Metadata(
        profile = features[1],
        level = features[2],
        bitDepth = features[3],
        chromaSubsampling = features[4],
        codecPrivate = record.copyOf(),
        color = null,
    )
}

private fun parseVp9Vpcc(record: ByteArray): Vp9Metadata? {
    val offset = when {
        record.size >= 12 && record[0].u8() == 1 &&
            record[1].u8() == 0 && record[2].u8() == 0 && record[3].u8() == 0 -> 4
        record.size >= 8 -> 0
        else -> return null
    }
    val initializationSize = record.readU16(offset + 6) ?: return null
    if (initializationSize != 0 || record.size != offset + 8) return null
    val packed = record[offset + 2].u8()
    val primaries = vp9ColorPrimaries(record[offset + 3].u8()) ?: return null
    val transfer = vp9ColorTransfer(record[offset + 4].u8()) ?: return null
    val matrix = vp9ColorMatrix(record[offset + 5].u8()) ?: return null
    return Vp9Metadata(
        profile = record[offset].u8(),
        level = record[offset + 1].u8(),
        bitDepth = packed ushr 4,
        chromaSubsampling = (packed ushr 1) and 0x07,
        codecPrivate = null,
        color = ColorSpaceInfo(
            matrix = matrix,
            primaries = primaries,
            transfer = transfer,
            fullRange = packed and 1 != 0,
            chromaLocation = ChromaLocation.Unspecified,
            rangeSpecified = true,
        ),
    )
}

private fun vp9ColorMatrix(value: Int): ColorMatrix? = when (value) {
    0 -> ColorMatrix.Identity
    1 -> ColorMatrix.Bt709
    2 -> ColorMatrix.Unspecified
    4 -> ColorMatrix.Fcc
    5 -> ColorMatrix.Bt470bg
    6 -> ColorMatrix.Smpte170m
    7 -> ColorMatrix.Smpte240m
    8 -> ColorMatrix.YCgCo
    9 -> ColorMatrix.Bt2020Ncl
    10 -> ColorMatrix.Bt2020Cl
    14 -> ColorMatrix.ICtCp
    else -> null
}

private fun vp9ColorPrimaries(value: Int): ColorPrimaries? = when (value) {
    1 -> ColorPrimaries.Bt709
    2 -> ColorPrimaries.Unspecified
    4 -> ColorPrimaries.Bt470m
    5 -> ColorPrimaries.Bt470bg
    6 -> ColorPrimaries.Smpte170m
    7 -> ColorPrimaries.Smpte240m
    8 -> ColorPrimaries.Film
    9 -> ColorPrimaries.Bt2020
    10 -> ColorPrimaries.SmpteSt428
    11 -> ColorPrimaries.DciP3
    12 -> ColorPrimaries.DisplayP3
    else -> null
}

private fun vp9ColorTransfer(value: Int): ColorTransfer? = when (value) {
    1 -> ColorTransfer.Bt709
    2 -> ColorTransfer.Unspecified
    4 -> ColorTransfer.Gamma22
    5 -> ColorTransfer.Gamma28
    6 -> ColorTransfer.Bt601
    7 -> ColorTransfer.Smpte240m
    8 -> ColorTransfer.Linear
    9 -> ColorTransfer.Log
    10 -> ColorTransfer.LogSqrt
    11 -> ColorTransfer.Iec6196624
    12 -> ColorTransfer.Bt1361Ecg
    13 -> ColorTransfer.Srgb
    14 -> ColorTransfer.Bt2020Ten
    15 -> ColorTransfer.Bt2020Twelve
    16 -> ColorTransfer.Pq
    17 -> ColorTransfer.SmpteSt428
    18 -> ColorTransfer.Hlg
    else -> null
}

/** Merges two authoritative declarations, filling unspecified fields and refusing conflicts. */
internal fun mergeMediaCodecColor(
    stream: ColorSpaceInfo?,
    record: ColorSpaceInfo?,
): ColorSpaceInfo? {
    if (record == null) return stream
    if (stream == null) return record

    fun <T> merge(left: T, right: T, unspecified: T): T? = when {
        left == unspecified -> right
        right == unspecified -> left
        left == right -> left
        else -> null
    }

    val matrix = merge(stream.matrix, record.matrix, ColorMatrix.Unspecified) ?: return null
    val primaries = merge(stream.primaries, record.primaries, ColorPrimaries.Unspecified) ?: return null
    val transfer = merge(stream.transfer, record.transfer, ColorTransfer.Unspecified) ?: return null
    val chroma = merge(stream.chromaLocation, record.chromaLocation, ChromaLocation.Unspecified) ?: return null
    if (stream.rangeSpecified && record.rangeSpecified && stream.fullRange != record.fullRange) return null
    val rangeSpecified = stream.rangeSpecified || record.rangeSpecified
    val fullRange = if (stream.rangeSpecified) stream.fullRange else record.fullRange
    return ColorSpaceInfo(matrix, primaries, transfer, fullRange, chroma, rangeSpecified)
}

private fun androidVp9Level(level: Int): Int? = when (level) {
    10 -> MediaCodecInfo.CodecProfileLevel.VP9Level1
    11 -> MediaCodecInfo.CodecProfileLevel.VP9Level11
    20 -> MediaCodecInfo.CodecProfileLevel.VP9Level2
    21 -> MediaCodecInfo.CodecProfileLevel.VP9Level21
    30 -> MediaCodecInfo.CodecProfileLevel.VP9Level3
    31 -> MediaCodecInfo.CodecProfileLevel.VP9Level31
    40 -> MediaCodecInfo.CodecProfileLevel.VP9Level4
    41 -> MediaCodecInfo.CodecProfileLevel.VP9Level41
    50 -> MediaCodecInfo.CodecProfileLevel.VP9Level5
    51 -> MediaCodecInfo.CodecProfileLevel.VP9Level51
    52 -> MediaCodecInfo.CodecProfileLevel.VP9Level52
    60 -> MediaCodecInfo.CodecProfileLevel.VP9Level6
    61 -> MediaCodecInfo.CodecProfileLevel.VP9Level61
    62 -> MediaCodecInfo.CodecProfileLevel.VP9Level62
    else -> null
}

private fun parseAv1Configuration(record: ByteArray): ParsedMediaCodecConfiguration? {
    if (record.size < 4 || record[0].u8() != 0x81) return null
    val profileAndLevel = record[1].u8()
    val profile = profileAndLevel ushr 5
    val levelIndex = profileAndLevel and 0x1F
    val flags = record[2].u8()
    val highTier = flags and 0x80 != 0
    val highBitDepth = flags and 0x40 != 0
    val twelveBit = flags and 0x20 != 0
    val subsamplingX = flags and 0x08 != 0
    val subsamplingY = flags and 0x04 != 0
    val chromaSamplePosition = flags and 0x03
    val delay = record[3].u8()
    val delayPresent = delay and 0x10 != 0
    if (delay and 0xE0 != 0 || !delayPresent && delay and 0x0F != 0) return null
    val monochrome = flags and 0x10 != 0
    if (
        profile != 0 ||
        twelveBit ||
        highTier && levelIndex < AV1_HIGH_TIER_MIN_LEVEL_INDEX
    ) return null
    if ((!subsamplingX || !subsamplingY) && !monochrome || chromaSamplePosition == 3) return null
    if (!hasWellFramedAv1ConfigObus(record)) return null

    return ParsedMediaCodecConfiguration(
        mime = MediaFormat.MIMETYPE_VIDEO_AV1,
        profile = if (highBitDepth) {
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
        } else {
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
        },
        level = androidAv1Level(levelIndex) ?: return null,
        bitDepth = if (highBitDepth) 10 else 8,
        highTier = highTier,
        opaqueCsd = record.copyOf(),
    )
}

/** Checks OBU framing and ordering only; it deliberately does not parse sequence-header semantics. */
private fun hasWellFramedAv1ConfigObus(record: ByteArray): Boolean {
    val hasConfigObus = record.size > AV1_CONFIG_RECORD_HEADER_SIZE
    var at = AV1_CONFIG_RECORD_HEADER_SIZE
    var sawSequenceHeader = false
    var firstObu = true
    while (at < record.size) {
        val header = record[at++].u8()
        val obuType = (header ushr 3) and 0x0F
        val extensionPresent = header and 0x04 != 0
        val sizePresent = header and 0x02 != 0
        if (header and 0x81 != 0 || !sizePresent) return false
        if (extensionPresent) {
            if (at >= record.size || record[at++].u8() and 0x07 != 0) return false
        }
        val size = record.readLeb128(at) ?: return false
        at = size.next
        if (size.value <= 0L || size.value > record.size.toLong() - at.toLong()) return false
        when (obuType) {
            AV1_OBU_SEQUENCE_HEADER -> {
                if (sawSequenceHeader || !firstObu) return false
                sawSequenceHeader = true
            }
            AV1_OBU_METADATA -> Unit
            else -> return false
        }
        at += size.value.toInt()
        firstObu = false
    }
    return !hasConfigObus || sawSequenceHeader
}

private data class Leb128(val value: Long, val next: Int)

private fun ByteArray.readLeb128(start: Int): Leb128? {
    var at = start
    var value = 0L
    repeat(8) { index ->
        if (at >= size) return null
        val byte = this[at++].u8()
        if (index == 7 && byte and 0xFE != 0) return null
        value = value or ((byte and 0x7F).toLong() shl (index * 7))
        if (byte and 0x80 == 0) return Leb128(value, at)
    }
    return null
}

private fun androidAv1Level(index: Int): Int? = when (index) {
    0 -> MediaCodecInfo.CodecProfileLevel.AV1Level2
    1 -> MediaCodecInfo.CodecProfileLevel.AV1Level21
    2 -> MediaCodecInfo.CodecProfileLevel.AV1Level22
    3 -> MediaCodecInfo.CodecProfileLevel.AV1Level23
    4 -> MediaCodecInfo.CodecProfileLevel.AV1Level3
    5 -> MediaCodecInfo.CodecProfileLevel.AV1Level31
    6 -> MediaCodecInfo.CodecProfileLevel.AV1Level32
    7 -> MediaCodecInfo.CodecProfileLevel.AV1Level33
    8 -> MediaCodecInfo.CodecProfileLevel.AV1Level4
    9 -> MediaCodecInfo.CodecProfileLevel.AV1Level41
    10 -> MediaCodecInfo.CodecProfileLevel.AV1Level42
    11 -> MediaCodecInfo.CodecProfileLevel.AV1Level43
    12 -> MediaCodecInfo.CodecProfileLevel.AV1Level5
    13 -> MediaCodecInfo.CodecProfileLevel.AV1Level51
    14 -> MediaCodecInfo.CodecProfileLevel.AV1Level52
    15 -> MediaCodecInfo.CodecProfileLevel.AV1Level53
    16 -> MediaCodecInfo.CodecProfileLevel.AV1Level6
    17 -> MediaCodecInfo.CodecProfileLevel.AV1Level61
    18 -> MediaCodecInfo.CodecProfileLevel.AV1Level62
    19 -> MediaCodecInfo.CodecProfileLevel.AV1Level63
    20 -> MediaCodecInfo.CodecProfileLevel.AV1Level7
    21 -> MediaCodecInfo.CodecProfileLevel.AV1Level71
    22 -> MediaCodecInfo.CodecProfileLevel.AV1Level72
    23 -> MediaCodecInfo.CodecProfileLevel.AV1Level73
    else -> null
}

/** True only when an advertised maximum covers the exact requested profile and tier. */
internal fun advertisedProfileLevelSupports(
    mime: String,
    advertisedProfile: Int,
    advertisedLevel: Int,
    requiredProfile: Int,
    requiredLevel: Int,
): Boolean {
    if (!advertisedProfileSupports(mime, advertisedProfile, requiredProfile)) return false
    return when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> orderedLevelSupports(advertisedLevel, requiredLevel, AVC_LEVELS)
        MediaFormat.MIMETYPE_VIDEO_HEVC -> hevcLevelSupports(advertisedLevel, requiredLevel)
        MediaFormat.MIMETYPE_VIDEO_VP9 -> orderedLevelSupports(advertisedLevel, requiredLevel, VP9_LEVELS)
        MediaFormat.MIMETYPE_VIDEO_AV1 -> orderedLevelSupports(advertisedLevel, requiredLevel, AV1_LEVELS)
        else -> false
    }
}

private fun advertisedProfileSupports(mime: String, advertised: Int, required: Int): Boolean {
    if (advertised == required) return true
    return when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> when (required) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline ->
                advertised == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh ->
                advertised == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            else -> false
        }
        MediaFormat.MIMETYPE_VIDEO_HEVC -> when (required) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ->
                advertised == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    advertised == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ->
                advertised == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            else -> false
        }
        MediaFormat.MIMETYPE_VIDEO_VP9 -> when (required) {
            MediaCodecInfo.CodecProfileLevel.VP9Profile2 ->
                advertised == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                    advertised == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ->
                advertised == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus
            MediaCodecInfo.CodecProfileLevel.VP9Profile3 ->
                advertised == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                    advertised == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ->
                advertised == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
            else -> false
        }
        MediaFormat.MIMETYPE_VIDEO_AV1 -> when (required) {
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ->
                advertised == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                    advertised == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ->
                advertised == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            else -> false
        }
        else -> false
    }
}

private fun orderedLevelSupports(advertised: Int, required: Int, levels: IntArray): Boolean {
    val advertisedRank = levelRank(advertised, levels)
    val requiredRank = levelRank(required, levels)
    return advertisedRank >= 0 && requiredRank >= 0 && advertisedRank >= requiredRank
}

private fun hevcLevelSupports(advertised: Int, required: Int): Boolean {
    val advertisedTier = hevcTierAndRank(advertised) ?: return false
    val requiredTier = hevcTierAndRank(required) ?: return false
    if (requiredTier.first && !advertisedTier.first) return false
    return advertisedTier.second >= requiredTier.second
}

private fun hevcTierAndRank(level: Int): Pair<Boolean, Int>? {
    val mainRank = HEVC_MAIN_LEVELS.indexOf(level)
    if (mainRank >= 0) return false to mainRank
    val highRank = HEVC_HIGH_LEVELS.indexOf(level)
    return if (highRank >= 0) true to highRank else null
}

private fun levelRank(level: Int, levels: IntArray): Int = levels.indexOf(level)

private fun Byte.u8(): Int = toInt() and 0xFF

private fun ByteArray.readU16(at: Int): Int? {
    if (at < 0 || size - at < 2) return null
    return (this[at].u8() shl 8) or this[at + 1].u8()
}

private const val AVC_RESERVED_COMPATIBILITY_MASK: Int = 0x03
private const val AVC_CONSTRAINT_SET1: Int = 0x40
private const val AVC_CONSTRAINT_SET3: Int = 0x10
private const val AVC_CONSTRAINED_HIGH_MASK: Int = 0x0C
private const val AV1_CONFIG_RECORD_HEADER_SIZE: Int = 4
private const val AV1_HIGH_TIER_MIN_LEVEL_INDEX: Int = 8
private const val AV1_OBU_SEQUENCE_HEADER: Int = 1
private const val AV1_OBU_METADATA: Int = 5
private val AVC_LEVELS = intArrayOf(
    MediaCodecInfo.CodecProfileLevel.AVCLevel1,
    MediaCodecInfo.CodecProfileLevel.AVCLevel1b,
    MediaCodecInfo.CodecProfileLevel.AVCLevel11,
    MediaCodecInfo.CodecProfileLevel.AVCLevel12,
    MediaCodecInfo.CodecProfileLevel.AVCLevel13,
    MediaCodecInfo.CodecProfileLevel.AVCLevel2,
    MediaCodecInfo.CodecProfileLevel.AVCLevel21,
    MediaCodecInfo.CodecProfileLevel.AVCLevel22,
    MediaCodecInfo.CodecProfileLevel.AVCLevel3,
    MediaCodecInfo.CodecProfileLevel.AVCLevel31,
    MediaCodecInfo.CodecProfileLevel.AVCLevel32,
    MediaCodecInfo.CodecProfileLevel.AVCLevel4,
    MediaCodecInfo.CodecProfileLevel.AVCLevel41,
    MediaCodecInfo.CodecProfileLevel.AVCLevel42,
    MediaCodecInfo.CodecProfileLevel.AVCLevel5,
    MediaCodecInfo.CodecProfileLevel.AVCLevel51,
    MediaCodecInfo.CodecProfileLevel.AVCLevel52,
    MediaCodecInfo.CodecProfileLevel.AVCLevel6,
    MediaCodecInfo.CodecProfileLevel.AVCLevel61,
    MediaCodecInfo.CodecProfileLevel.AVCLevel62,
)

private val HEVC_MAIN_LEVELS = intArrayOf(
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61,
    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62,
)

private val HEVC_HIGH_LEVELS = intArrayOf(
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61,
    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62,
)

private val VP9_LEVELS = intArrayOf(
    MediaCodecInfo.CodecProfileLevel.VP9Level1,
    MediaCodecInfo.CodecProfileLevel.VP9Level11,
    MediaCodecInfo.CodecProfileLevel.VP9Level2,
    MediaCodecInfo.CodecProfileLevel.VP9Level21,
    MediaCodecInfo.CodecProfileLevel.VP9Level3,
    MediaCodecInfo.CodecProfileLevel.VP9Level31,
    MediaCodecInfo.CodecProfileLevel.VP9Level4,
    MediaCodecInfo.CodecProfileLevel.VP9Level41,
    MediaCodecInfo.CodecProfileLevel.VP9Level5,
    MediaCodecInfo.CodecProfileLevel.VP9Level51,
    MediaCodecInfo.CodecProfileLevel.VP9Level52,
    MediaCodecInfo.CodecProfileLevel.VP9Level6,
    MediaCodecInfo.CodecProfileLevel.VP9Level61,
    MediaCodecInfo.CodecProfileLevel.VP9Level62,
)

private val AV1_LEVELS = intArrayOf(
    MediaCodecInfo.CodecProfileLevel.AV1Level2,
    MediaCodecInfo.CodecProfileLevel.AV1Level21,
    MediaCodecInfo.CodecProfileLevel.AV1Level22,
    MediaCodecInfo.CodecProfileLevel.AV1Level23,
    MediaCodecInfo.CodecProfileLevel.AV1Level3,
    MediaCodecInfo.CodecProfileLevel.AV1Level31,
    MediaCodecInfo.CodecProfileLevel.AV1Level32,
    MediaCodecInfo.CodecProfileLevel.AV1Level33,
    MediaCodecInfo.CodecProfileLevel.AV1Level4,
    MediaCodecInfo.CodecProfileLevel.AV1Level41,
    MediaCodecInfo.CodecProfileLevel.AV1Level42,
    MediaCodecInfo.CodecProfileLevel.AV1Level43,
    MediaCodecInfo.CodecProfileLevel.AV1Level5,
    MediaCodecInfo.CodecProfileLevel.AV1Level51,
    MediaCodecInfo.CodecProfileLevel.AV1Level52,
    MediaCodecInfo.CodecProfileLevel.AV1Level53,
    MediaCodecInfo.CodecProfileLevel.AV1Level6,
    MediaCodecInfo.CodecProfileLevel.AV1Level61,
    MediaCodecInfo.CodecProfileLevel.AV1Level62,
    MediaCodecInfo.CodecProfileLevel.AV1Level63,
    MediaCodecInfo.CodecProfileLevel.AV1Level7,
    MediaCodecInfo.CodecProfileLevel.AV1Level71,
    MediaCodecInfo.CodecProfileLevel.AV1Level72,
    MediaCodecInfo.CodecProfileLevel.AV1Level73,
)
