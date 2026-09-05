package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame

/** The JNI half: identical pixels to the cinterop one, reached through the packaged shared library. */
internal actual class LibassEngine private constructor(
    private var handle: Long,
) : AutoCloseable {

    private fun live(): Long {
        check(handle != 0L) { "the libass engine is closed" }
        return handle
    }

    actual fun openTrack(header: ByteArray): Boolean = LibassNative.openTrack(live(), header)

    actual fun openDocument(script: ByteArray): Boolean = LibassNative.openDocument(live(), script)

    actual fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long) =
        LibassNative.addEvent(live(), payload, startMillis, durationMillis)

    actual fun clearEvents() = LibassNative.clearEvents(live())

    actual fun addFont(name: String, data: ByteArray) = LibassNative.addFont(live(), name, data)

    actual fun setFrame(frame: TypesetFrame) = LibassNative.setFrame(
        live(),
        frame.width, frame.height, frame.videoWidth, frame.videoHeight,
        frame.marginTop, frame.marginBottom, frame.marginLeft, frame.marginRight,
        frame.fontScale.toDouble(), frame.linePosition.toDouble(),
    )

    actual fun render(timeMillis: Long): ByteArray? = LibassNative.render(live(), timeMillis)

    actual override fun close() {
        val open = handle
        if (open == 0L) return
        handle = 0L
        LibassNative.close(open)
    }

    actual companion object {
        actual fun open(): LibassEngine? {
            if (!LibassNative.isLoaded) return null
            val handle = LibassNative.open()
            return if (handle == 0L) null else LibassEngine(handle)
        }

        actual fun libraryVersion(): Int = if (LibassNative.isLoaded) LibassNative.libraryVersion() else 0
    }
}
