@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlin.js.JsAny

/**
 * The web half: the shared C driver inside `kiteass.mjs`, reached through its export table.
 *
 * The module arrives asynchronously and a typesetter is asked for synchronously, so an engine may
 * start PENDING: it keeps what it was told as current state and hands that to the module the
 * moment it lands, and [render] answers "unchanged" until then. A load that failed, a load that
 * outlived [KiteLibassWeb]'s deadline, or waiting data over [PENDING_BUDGET_BYTES] surfaces on
 * the next call as an exception, which the engine's lane turns into the fallback to the built-in
 * styling with a warning.
 *
 * Bytes cross as Latin-1 strings in bulk, both ways: Kotlin/Wasm and the module are separate
 * memories with no typed-array bridge, and a per-byte crossing is the path the web spike measured
 * to death. The tight loops live in JavaScript over `HEAPU8`.
 */
internal actual class LibassEngine private constructor(
    private var module: JsAny?,
) : AutoCloseable {

    private var self: Int = 0
    private var closed = false

    private class PendingOpener(val document: Boolean, val bytes: ByteArray)

    private class PendingEvent(val payload: ByteArray, val startMillis: Long, val durationMillis: Long)

    // What the engine was told while the module loaded, kept as current state and not as a log of
    // calls. A render per video frame and a clear per seek grew that log without end (#291).
    private var pendingOpener: PendingOpener? = null
    private var pendingClearAfterOpener = false
    private val pendingFonts = LinkedHashMap<String, ByteArray>()
    private val pendingEvents = ArrayList<PendingEvent>()
    private var pendingFrame: TypesetFrame? = null

    /** The bytes held for the module, which [PENDING_BUDGET_BYTES] bounds. */
    internal var pendingBytes: Long = 0
        private set

    private var overflow: IllegalStateException? = null

    private fun ready(): Boolean {
        if (closed) return false
        overflow?.let { throw it }
        if (module == null) {
            KiteLibassWeb.pendingFailure()?.let { throw IllegalStateException(it.message ?: "kiteass.mjs failed to load", it) }
            val landed = KiteLibassWeb.module ?: return false
            module = landed
        }
        if (self == 0) {
            self = kassOpen(module!!)
            check(self != 0) { "libass refused a library instance in the web module" }
            replayPending()
        }
        return true
    }

    /** Hands the module the state it missed, in the order a loaded engine would have seen it. */
    private fun replayPending() {
        val fonts = pendingFonts.toList()
        val opener = pendingOpener
        val cleared = pendingClearAfterOpener
        val events = pendingEvents.toList()
        val frame = pendingFrame
        dropPending()
        fonts.forEach { (name, data) -> addFont(name, data) }
        if (opener != null) {
            val opened = if (opener.document) openDocument(opener.bytes) else openTrack(opener.bytes)
            check(opened) { "libass refused the track it was given while the web module loaded" }
            if (cleared) clearEvents()
        }
        events.forEach { addEvent(it.payload, it.startMillis, it.durationMillis) }
        frame?.let(::setFrame)
    }

    private fun dropPending() {
        pendingOpener = null
        pendingClearAfterOpener = false
        pendingFonts.clear()
        pendingEvents.clear()
        pendingFrame = null
        pendingBytes = 0
    }

    private fun dropPendingEvents() {
        pendingEvents.forEach { pendingBytes -= it.payload.size }
        pendingEvents.clear()
    }

    private fun holdPending(bytes: Int) {
        pendingBytes += bytes
        if (pendingBytes <= PENDING_BUDGET_BYTES) return
        dropPending()
        val refused = IllegalStateException(
            "the libass web module has not loaded, and the subtitle data waiting for it passed $PENDING_BUDGET_BYTES bytes",
        )
        overflow = refused
        throw refused
    }

    private fun pendOpener(opener: PendingOpener) {
        // A new track replaces the old one, and with it every event that was waiting for it.
        pendingOpener?.let { pendingBytes -= it.bytes.size }
        dropPendingEvents()
        pendingClearAfterOpener = false
        pendingOpener = opener
        holdPending(opener.bytes.size)
    }

    private inline fun <T> withBytes(module: JsAny, bytes: ByteArray, block: (pointer: Int, size: Int) -> T): T {
        if (bytes.isEmpty()) return block(0, 0)
        val pointer = kassAlloc(module, bytes.size)
        check(pointer != 0) { "the libass web module is out of memory (${bytes.size} bytes)" }
        try {
            writeBytes(module, pointer, bytes)
            return block(pointer, bytes.size)
        } finally {
            kassFree(module, pointer)
        }
    }

    actual fun openTrack(header: ByteArray): Boolean {
        if (!ready()) { pendOpener(PendingOpener(document = false, header.copyOf())); return true }
        return withBytes(module!!, header) { p, n -> kassOpenTrack(module!!, self, p, n) != 0 }
    }

    actual fun openDocument(script: ByteArray): Boolean {
        if (!ready()) { pendOpener(PendingOpener(document = true, script.copyOf())); return true }
        return withBytes(module!!, script) { p, n -> kassOpenDocument(module!!, self, p, n) != 0 }
    }

    actual fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long) {
        if (!ready()) {
            pendingEvents += PendingEvent(payload.copyOf(), startMillis, durationMillis)
            holdPending(payload.size)
            return
        }
        withBytes(module!!, payload) { p, n -> kassAddEvent(module!!, self, p, n, startMillis.toDouble(), durationMillis.toDouble()) }
    }

    actual fun clearEvents() {
        if (!ready()) {
            // The waiting events are dropped outright. A clear still matters to the opener: it
            // flushes a whole document's own events too.
            dropPendingEvents()
            if (pendingOpener != null) pendingClearAfterOpener = true
            return
        }
        kassClearEvents(module!!, self)
    }

    actual fun addFont(name: String, data: ByteArray) {
        if (!ready()) {
            pendingFonts.put(name, data.copyOf())?.let { replaced -> pendingBytes -= replaced.size }
            holdPending(data.size)
            return
        }
        val nameBytes = name.encodeToByteArray() + 0
        withBytes(module!!, nameBytes) { namePointer, _ ->
            withBytes(module!!, data) { p, n -> kassAddFont(module!!, self, namePointer, p, n) }
        }
    }

    actual fun setFrame(frame: TypesetFrame) {
        if (!ready()) { pendingFrame = frame; return }
        kassSetFrame(
            module!!, self,
            frame.width, frame.height, frame.videoWidth, frame.videoHeight,
            frame.marginTop, frame.marginBottom, frame.marginLeft, frame.marginRight,
            frame.fontScale.toDouble(), frame.linePosition.toDouble(),
        )
    }

    actual fun render(timeMillis: Long): ByteArray? {
        if (!ready()) return null
        return when (kassRender(module!!, self, timeMillis.toDouble())) {
            0 -> null
            1 -> readBytes(module!!, kassPackedPtr(module!!, self), kassPackedSize(module!!, self))
            else -> ByteArray(0)
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        dropPending()
        val m = module
        if (m != null && self != 0) kassClose(m, self)
        self = 0
    }

    actual companion object {
        /** Always an engine on the web: pending until the module arrives, and it starts arriving now. */
        actual fun open(): LibassEngine? {
            val module = KiteLibassWeb.module
            if (module == null) {
                if (KiteLibassWeb.loadFailure != null) return null
                KiteLibassWeb.loadInBackground()
            }
            return LibassEngine(module)
        }

        actual fun libraryVersion(): Int = KiteLibassWeb.module?.let(::kassLibraryVersion) ?: 0

        /**
         * The most a pending engine holds for the module, fonts included. A page that loads the
         * module before it plays never gets near it.
         */
        const val PENDING_BUDGET_BYTES: Long = 64L * 1024 * 1024
    }
}

internal actual fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>> = emptyList()

internal actual fun defaultFontDirectories(): List<String> = emptyList()

internal actual val platformNeedsSystemFontFiles: Boolean = false

/** Kotlin bytes into module memory: one Latin-1 string across, one JS loop into HEAPU8. */
private fun writeBytes(module: JsAny, pointer: Int, bytes: ByteArray) {
    val packed = StringBuilder(bytes.size)
    for (b in bytes) packed.append((b.toInt() and 0xFF).toChar())
    webWriteChunk(module, pointer, packed.toString())
}

/** Module memory into Kotlin bytes: one JS loop out of HEAPU8, one Latin-1 string back. */
private fun readBytes(module: JsAny, pointer: Int, size: Int): ByteArray {
    if (size <= 0 || pointer == 0) return ByteArray(0)
    val packed = webReadChunk(module, pointer, size)
    return ByteArray(packed.length) { packed[it].code.toByte() }
}

@JsFun("(m, p, s) => { const n = s.length; const h = m.HEAPU8; for (let i = 0; i < n; i++) h[p + i] = s.charCodeAt(i); }")
private external fun webWriteChunk(module: JsAny, pointer: Int, packed: String)

// Chunked so a large picture never hands apply() more arguments than an engine accepts.
@JsFun("(m, p, n) => { const h = m.HEAPU8; let s = ''; for (let i = 0; i < n; i += 8192) { s += String.fromCharCode.apply(null, h.subarray(p + i, p + Math.min(n, i + 8192))); } return s; }")
private external fun webReadChunk(module: JsAny, pointer: Int, size: Int): String

@JsFun("(m) => m._kass_library_version()")
private external fun kassLibraryVersion(module: JsAny): Int

@JsFun("(m) => m._kass_open()")
private external fun kassOpen(module: JsAny): Int

@JsFun("(m, s) => m._kass_close(s)")
private external fun kassClose(module: JsAny, self: Int)

@JsFun("(m, n) => m._kass_alloc(n)")
private external fun kassAlloc(module: JsAny, size: Int): Int

@JsFun("(m, p) => m._kass_free(p)")
private external fun kassFree(module: JsAny, pointer: Int)

@JsFun("(m, s, p, n) => m._kass_open_track(s, p, n)")
private external fun kassOpenTrack(module: JsAny, self: Int, pointer: Int, size: Int): Int

@JsFun("(m, s, p, n) => m._kass_open_document(s, p, n)")
private external fun kassOpenDocument(module: JsAny, self: Int, pointer: Int, size: Int): Int

@JsFun("(m, s, p, n, start, duration) => m._kass_add_event(s, p, n, start, duration)")
private external fun kassAddEvent(module: JsAny, self: Int, pointer: Int, size: Int, startMillis: Double, durationMillis: Double)

@JsFun("(m, s) => m._kass_clear_events(s)")
private external fun kassClearEvents(module: JsAny, self: Int)

@JsFun("(m, s, name, p, n) => m._kass_add_font(s, name, p, n)")
private external fun kassAddFont(module: JsAny, self: Int, namePointer: Int, pointer: Int, size: Int)

@JsFun("(m, s, fw, fh, sw, sh, mt, mb, ml, mr, scale, line) => m._kass_set_frame(s, fw, fh, sw, sh, mt, mb, ml, mr, scale, line)")
private external fun kassSetFrame(
    module: JsAny, self: Int,
    frameWidth: Int, frameHeight: Int, storageWidth: Int, storageHeight: Int,
    marginTop: Int, marginBottom: Int, marginLeft: Int, marginRight: Int,
    fontScale: Double, linePosition: Double,
)

@JsFun("(m, s, now) => m._kass_render(s, now)")
private external fun kassRender(module: JsAny, self: Int, nowMillis: Double): Int

@JsFun("(m, s) => m._kass_packed_ptr(s)")
private external fun kassPackedPtr(module: JsAny, self: Int): Int

@JsFun("(m, s) => m._kass_packed_size(s)")
private external fun kassPackedSize(module: JsAny, self: Int): Int
