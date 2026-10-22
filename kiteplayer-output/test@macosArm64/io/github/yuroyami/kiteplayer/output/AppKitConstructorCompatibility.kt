@file:Suppress("unused")

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4

/**
 * Compile-only consumer calls: no test window is opened and no native renderer is constructed.
 * The trailing tone-map lambda must bind to the original constructor; both old defaults and the
 * explicit wake option must also remain resolvable. Runtime rendering is covered separately.
 */
private fun appKitConstructorCompatibility(
    window: AppKitWindow,
    convert: (VideoFrame) -> ByteArray,
): List<AppKitVideoRenderer> = listOf(
    AppKitVideoRenderer(window, convert),
    AppKitVideoRenderer(window, convert) { frame -> frame.colorSpace.isHdr },
    AppKitVideoRenderer(window = window, convert = convert, toneMapped = { false }),
    AppKitVideoRenderer(window, convert, { false }, false),
    AppKitVideoRenderer(window, convert, keepDisplayAwake = false),
)

/** Exact public constructor references; taking either reference never creates a window or renderer. */
private fun appKitConstructorReferenceCompatibility(): List<Any> {
    val original: KFunction3<
        AppKitWindow, (VideoFrame) -> ByteArray, (VideoFrame) -> Boolean, AppKitVideoRenderer,
    > = ::AppKitVideoRenderer
    val withWakeOption: KFunction4<
        AppKitWindow, (VideoFrame) -> ByteArray, (VideoFrame) -> Boolean, Boolean, AppKitVideoRenderer,
    > = ::AppKitVideoRenderer
    return listOf(original, withWakeOption)
}
