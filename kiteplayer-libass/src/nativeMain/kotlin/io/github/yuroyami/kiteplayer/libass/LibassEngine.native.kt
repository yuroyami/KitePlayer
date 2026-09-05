@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import libass.ass_library_version
import libass.kite_ass
import libass.kite_ass_add_event
import libass.kite_ass_add_font
import libass.kite_ass_clear_events
import libass.kite_ass_close
import libass.kite_ass_open
import libass.kite_ass_open_document
import libass.kite_ass_open_track
import libass.kite_ass_render
import libass.kite_ass_set_frame

/** The cinterop half: the shared C driver reached directly, with the chain linked into the binary. */
internal actual class LibassEngine private constructor(
    private val self: CPointer<kite_ass>,
) : AutoCloseable {

    actual fun openTrack(header: ByteArray): Boolean {
        if (header.isEmpty()) return kite_ass_open_track(self, null, 0) != 0
        return header.usePinned { pinned -> kite_ass_open_track(self, pinned.addressOf(0), header.size) != 0 }
    }

    actual fun openDocument(script: ByteArray): Boolean {
        if (script.isEmpty()) return false
        return script.usePinned { pinned ->
            kite_ass_open_document(self, pinned.addressOf(0), script.size.toULong()) != 0
        }
    }

    actual fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long) {
        if (payload.isEmpty()) return
        payload.usePinned { pinned ->
            kite_ass_add_event(self, pinned.addressOf(0), payload.size, startMillis, durationMillis)
        }
    }

    actual fun clearEvents() = kite_ass_clear_events(self)

    actual fun addFont(name: String, data: ByteArray) {
        if (data.isEmpty()) return
        memScoped {
            data.usePinned { pinned -> kite_ass_add_font(self, name.cstr.ptr, pinned.addressOf(0), data.size) }
        }
    }

    actual fun setFrame(frame: TypesetFrame) = kite_ass_set_frame(
        self,
        frame.width, frame.height, frame.videoWidth, frame.videoHeight,
        frame.marginTop, frame.marginBottom, frame.marginLeft, frame.marginRight,
        frame.fontScale.toDouble(), frame.linePosition.toDouble(),
    )

    actual fun render(timeMillis: Long): ByteArray? = memScoped {
        val out = alloc<CPointerVar<UByteVar>>()
        val size = alloc<IntVar>()
        when (kite_ass_render(self, timeMillis, out.ptr, size.ptr)) {
            0 -> null
            1 -> out.value?.readBytes(size.value) ?: ByteArray(0)
            else -> ByteArray(0)
        }
    }

    actual override fun close() = kite_ass_close(self)

    actual companion object {
        actual fun open(): LibassEngine? = kite_ass_open()?.let(::LibassEngine)

        actual fun libraryVersion(): Int = ass_library_version()
    }
}
