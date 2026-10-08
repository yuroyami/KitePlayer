@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.kCGColorSpaceITUR_709
import platform.CoreVideo.CVBufferSetAttachment
import platform.CoreVideo.CVImageBufferCreateColorSpaceFromAttachments
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.kCVAttachmentMode_ShouldPropagate
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_DCI_P3
import platform.CoreVideo.kCVImageBufferColorPrimaries_EBU_3213
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_2020
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferColorPrimaries_P3_D65
import platform.CoreVideo.kCVImageBufferColorPrimaries_SMPTE_C
import platform.CoreVideo.kCVImageBufferGammaLevelKey
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_2100_HLG
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunction_Linear
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_240M_1995
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreVideo.kCVImageBufferTransferFunction_UseGamma
import platform.CoreVideo.kCVImageBufferTransferFunction_sRGB
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_2020
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_601_4
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_SMPTE_240M_1995

/**
 * The colour a picture is in, in Core Video's words (#489): what the system needs to show it right
 * on a display whose colours are wider than the picture's, such as the P3 screen of every recent
 * Mac. [gamma] is set only with the use-gamma transfer.
 *
 * The tags are the ones FFmpeg's VideoToolbox path gives a hardware frame, so a software frame and
 * a hardware frame of one stream are shown alike. What the stream states wins. A field it leaves
 * out follows the picture's size: 709 for high definition, and for standard definition EBU
 * primaries at 576 lines and SMPTE C at any other height, both with the 601 matrix.
 */
internal class ColorTags(
    val primaries: CFStringRef?,
    val transfer: CFStringRef?,
    val matrix: CFStringRef?,
    val gamma: Double? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ColorTags && primaries == other.primaries && transfer == other.transfer &&
            matrix == other.matrix && gamma == other.gamma

    override fun hashCode(): Int =
        ((primaries.hashCode() * 31 + transfer.hashCode()) * 31 + matrix.hashCode()) * 31 + gamma.hashCode()
}

/** The tags of a picture of [width] by [height] in [colorSpace], as it is stored. */
internal fun colorTagsOf(colorSpace: ColorSpaceInfo, width: Int, height: Int): ColorTags {
    val highDefinition = width >= 1280 || height > 576
    val primaries = when (colorSpace.primaries) {
        ColorPrimaries.Bt709 -> kCVImageBufferColorPrimaries_ITU_R_709_2
        ColorPrimaries.Bt470bg -> kCVImageBufferColorPrimaries_EBU_3213
        ColorPrimaries.Smpte170m, ColorPrimaries.Smpte240m -> kCVImageBufferColorPrimaries_SMPTE_C
        ColorPrimaries.Bt2020 -> kCVImageBufferColorPrimaries_ITU_R_2020
        ColorPrimaries.DciP3 -> kCVImageBufferColorPrimaries_DCI_P3
        ColorPrimaries.DisplayP3 -> kCVImageBufferColorPrimaries_P3_D65
        else -> when {
            highDefinition -> kCVImageBufferColorPrimaries_ITU_R_709_2
            height == 576 || height == 288 -> kCVImageBufferColorPrimaries_EBU_3213
            else -> kCVImageBufferColorPrimaries_SMPTE_C
        }
    }
    val transfer = when (colorSpace.transfer) {
        ColorTransfer.Srgb -> kCVImageBufferTransferFunction_sRGB
        ColorTransfer.Linear -> kCVImageBufferTransferFunction_Linear
        ColorTransfer.Smpte240m -> kCVImageBufferTransferFunction_SMPTE_240M_1995
        ColorTransfer.Pq -> kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
        ColorTransfer.Hlg -> kCVImageBufferTransferFunction_ITU_R_2100_HLG
        ColorTransfer.Gamma22, ColorTransfer.Gamma28 -> kCVImageBufferTransferFunction_UseGamma
        // BT.601, BT.709 and both BT.2020 curves are one curve under four names.
        else -> kCVImageBufferTransferFunction_ITU_R_709_2
    }
    val gamma = when (colorSpace.transfer) {
        ColorTransfer.Gamma22 -> 2.2
        ColorTransfer.Gamma28 -> 2.8
        else -> null
    }
    val matrix = when (colorSpace.matrix) {
        ColorMatrix.Bt709 -> kCVImageBufferYCbCrMatrix_ITU_R_709_2
        ColorMatrix.Bt601, ColorMatrix.Bt470bg, ColorMatrix.Smpte170m, ColorMatrix.Fcc -> kCVImageBufferYCbCrMatrix_ITU_R_601_4
        ColorMatrix.Smpte240m -> kCVImageBufferYCbCrMatrix_SMPTE_240M_1995
        ColorMatrix.Bt2020Ncl, ColorMatrix.Bt2020Cl -> kCVImageBufferYCbCrMatrix_ITU_R_2020
        ColorMatrix.Unspecified ->
            if (highDefinition) kCVImageBufferYCbCrMatrix_ITU_R_709_2 else kCVImageBufferYCbCrMatrix_ITU_R_601_4
        // Core Video has no word for the rest, and a wrong word is worse than none.
        else -> null
    }
    return ColorTags(primaries, transfer, matrix, gamma)
}

/**
 * The tags of what the Metal shader writes for HDR it tone maps to standard range: BT.709
 * primaries with a gamma of 2.2. See `kp_tone_map`.
 */
internal val toneMappedColorTags: ColorTags = ColorTags(
    primaries = kCVImageBufferColorPrimaries_ITU_R_709_2,
    transfer = kCVImageBufferTransferFunction_UseGamma,
    matrix = kCVImageBufferYCbCrMatrix_ITU_R_709_2,
    gamma = 2.2,
)

/** Writes [tags] onto [buffer], to travel with it to whatever shows it. */
internal fun tagPixelBuffer(buffer: CVPixelBufferRef, tags: ColorTags, withMatrix: Boolean) {
    tags.primaries?.let { CVBufferSetAttachment(buffer, kCVImageBufferColorPrimariesKey, it, kCVAttachmentMode_ShouldPropagate) }
    tags.transfer?.let { CVBufferSetAttachment(buffer, kCVImageBufferTransferFunctionKey, it, kCVAttachmentMode_ShouldPropagate) }
    if (withMatrix) {
        tags.matrix?.let { CVBufferSetAttachment(buffer, kCVImageBufferYCbCrMatrixKey, it, kCVAttachmentMode_ShouldPropagate) }
    }
    tags.gamma?.let { level ->
        memScoped {
            val value = alloc<DoubleVar>().apply { value = level }
            val number = CFNumberCreate(null, kCFNumberDoubleType, value.ptr)
            CVBufferSetAttachment(buffer, kCVImageBufferGammaLevelKey, number, kCVAttachmentMode_ShouldPropagate)
            CFRelease(number)
        }
    }
}

/**
 * The colour space the system shows a picture with [tags] in, made by Core Video as it makes one
 * for a tagged pixel buffer, so a Metal layer and a sample buffer layer agree. ITU-R 709 when Core
 * Video has none for the tags. The caller releases it.
 */
internal fun createColorSpace(tags: ColorTags): CGColorSpaceRef? = memScoped {
    val attachments = CFDictionaryCreateMutable(
        null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    )
    tags.primaries?.let { CFDictionarySetValue(attachments, kCVImageBufferColorPrimariesKey, it) }
    tags.transfer?.let { CFDictionarySetValue(attachments, kCVImageBufferTransferFunctionKey, it) }
    tags.matrix?.let { CFDictionarySetValue(attachments, kCVImageBufferYCbCrMatrixKey, it) }
    tags.gamma?.let { level ->
        val value = alloc<DoubleVar>().apply { value = level }
        val number = CFNumberCreate(null, kCFNumberDoubleType, value.ptr)
        CFDictionarySetValue(attachments, kCVImageBufferGammaLevelKey, number)
        CFRelease(number)
    }
    val made = CVImageBufferCreateColorSpaceFromAttachments(attachments)
    CFRelease(attachments)
    made ?: CGColorSpaceCreateWithName(kCGColorSpaceITUR_709)
}
