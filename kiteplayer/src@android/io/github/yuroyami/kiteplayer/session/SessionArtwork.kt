package io.github.yuroyami.kiteplayer.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The longest side, in pixels, a picture for the media session and the notification is held to
 * (#425): the size the system itself scales a session's art to, `config_mediaMetadataBitmapMaxSize`,
 * 320 dp, which the large media controls of recent Android versions draw from. A picture larger than
 * that is carried for nothing, and a 3,000 pixel cover is 36 MB, far past what one binder transaction
 * holds, as Media3 found. Smaller than that would blur where it is drawn largest, as Media3 found too.
 */
internal fun mediaArtworkSidePx(context: Context): Int =
    (MEDIA_ARTWORK_SIDE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(MEDIA_ARTWORK_SIDE_DP)

internal const val MEDIA_ARTWORK_SIDE_DP: Int = 320

/**
 * The power of two a picture of [width] by [height] is decoded at for a longest side of [side]: the
 * largest that still leaves its longest side at [side] or more, so the decode costs little and the
 * picture is never smaller than it is drawn.
 */
internal fun coverSampleSize(width: Int, height: Int, side: Int): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / (sample * 2) >= side) sample *= 2
    return sample
}

/** The size a picture of [width] by [height] is held to for a longest side of [side], its shape kept, never grown. */
internal fun fittedSize(width: Int, height: Int, side: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= side || longest <= 0) return width to height
    val scale = side.toDouble() / longest
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** [bitmap] held to a longest side of [side], or itself when it is no larger. */
internal fun fittedArtwork(bitmap: Bitmap, side: Int): Bitmap {
    val (width, height) = fittedSize(bitmap.width, bitmap.height, side)
    if (width == bitmap.width && height == bitmap.height) return bitmap
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

/** An encoded cover decoded for a longest side of [side], or null when the platform cannot read it. */
internal fun decodeCover(bytes: ByteArray, side: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, side) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return fittedArtwork(decoded, side)
}
