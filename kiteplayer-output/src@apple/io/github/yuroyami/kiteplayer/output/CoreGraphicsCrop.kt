@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.PictureCrop
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake

/**
 * Takes the part [crop] leaves of [image], a [width] by [height] stored picture, and gives [image]
 * up (#497). The part shares the stored pixels and is the caller's to release. With no crop, or one
 * that does not fit, the result is [image] itself. Core Graphics measures this rectangle from the
 * picture's top left, the same corner the crop counts from.
 */
internal fun cropStoredImage(image: CGImageRef, width: Int, height: Int, crop: PictureCrop?): CGImageRef? {
    val applied = crop?.takeIf { !it.isEmpty && it.fits(width, height) } ?: return image
    val part = CGImageCreateWithImageInRect(
        image,
        CGRectMake(
            applied.left.toDouble(),
            applied.top.toDouble(),
            (width - applied.left - applied.right).toDouble(),
            (height - applied.top - applied.bottom).toDouble(),
        ),
    )
    CGImageRelease(image)
    return part
}
