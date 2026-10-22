package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetter
import io.github.yuroyami.kiteplayer.spi.TypesetFrame

/**
 * libass as the engine's [SubtitleTypesetter]: a container header or a whole script goes in, one
 * event per packet follows, and every render answers positioned premultiplied RGBA images in the
 * output surface's own pixels.
 *
 * The same pixels on every platform, because the conversion lives in one C header the three
 * bindings include: Kotlin/Native reaches it through cinterop, Android and the desktop JVM through
 * a JNI adapter, the web through an emscripten export table. This class is the Kotlin half they
 * share: it forwards calls to the [LibassEngine] of its platform and unpacks the one buffer a
 * render returns.
 *
 * Fonts: Apple and Windows builds find system fonts on their own (CoreText, DirectWrite). Android
 * and Linux have no font provider in this chain, so the first font or track that arrives loads a
 * bounded set of the system's font files first (see [KiteLibass.fontDirectories]), and then
 * whatever the container attached and the application configured. libass takes its default family,
 * the one a style naming a missing font falls back to, from the first font it is given, so there it
 * is the platform's Latin sans face, Roboto on Android. The web has no system fonts to read: the
 * first font the application or the file brings is the default family, and a page that wants East
 * Asian text in typeset subtitles hands the typesetter a font that has it. Fonts embedded in the
 * script's own `[Fonts]` section are read by libass itself.
 *
 * Every member is called from the engine's raster lane and never concurrently, as the interface
 * promises; the constructor is the one call that happens elsewhere, and it does no I/O.
 */
public class LibassTypesetter internal constructor(
    private val engine: LibassEngine,
) : SubtitleTypesetter {

    /** Opens the engine, or throws when this platform build cannot load libass. */
    public constructor() : this(LibassEngine.open() ?: error("libass refused a library instance"))

    private var closed = false
    private var systemFontsLoaded = false

    override fun openTrack(header: ByteArray) {
        checkOpen()
        loadSystemFontsOnce()
        check(engine.openTrack(header)) { "libass refused a new track" }
    }

    override fun openDocument(script: ByteArray) {
        checkOpen()
        loadSystemFontsOnce()
        check(engine.openDocument(script)) { "libass could not parse the script" }
    }

    override fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long) {
        checkOpen()
        if (payload.isEmpty()) return
        engine.addEvent(payload, startMillis, durationMillis)
    }

    override fun clearEvents() {
        checkOpen()
        engine.clearEvents()
    }

    override fun addFont(name: String, data: ByteArray) {
        checkOpen()
        if (data.isEmpty()) return
        // libass takes its default family from the first font it is given, and the engine hands
        // over application fonts and attachments before the track opens, so the system's own go
        // first or one of those would become the face every missing font falls back to (#507).
        loadSystemFontsOnce()
        engine.addFont(name, data)
    }

    override fun render(timeMillis: Long, frame: TypesetFrame): List<OverlayImage>? {
        checkOpen()
        engine.setFrame(frame)
        val packed = engine.render(timeMillis) ?: return null
        check(packed.isNotEmpty()) { "libass could not build the frame's packed buffer" }
        return unpackOverlay(packed)
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.close()
    }

    private fun checkOpen() = check(!closed) { "LibassTypesetter is closed" }

    /**
     * The platform's own font files, loaded once per typesetter and only where libass has no
     * provider of its own. Here rather than in the constructor because reading font files is I/O,
     * and the first track opens on the raster lane, which is where I/O belongs.
     */
    private fun loadSystemFontsOnce() {
        if (systemFontsLoaded) return
        systemFontsLoaded = true
        if (!KiteLibass.needsSystemFontFiles) return
        readFontFiles(KiteLibass.fontDirectories, KiteLibass.systemFontBudgetBytes).forEach { (name, bytes) ->
            engine.addFont(name, bytes)
        }
    }

    public companion object {
        /** The typesetter, or null when this platform build carries no loadable libass. */
        public fun createOrNull(): LibassTypesetter? = LibassEngine.open()?.let(::LibassTypesetter)
    }
}

/**
 * The raw libass boundary of one platform. Every method mirrors one `kite_ass_*` function in
 * `native/src/kite_ass.h`, and that header owns the semantics; this is marshalling only.
 */
internal expect class LibassEngine : AutoCloseable {
    fun openTrack(header: ByteArray): Boolean
    fun openDocument(script: ByteArray): Boolean
    fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long)
    fun clearEvents()
    fun addFont(name: String, data: ByteArray)
    fun setFrame(frame: TypesetFrame)

    /** The packed picture when it changed, null when unchanged, empty when it could not be built. */
    fun render(timeMillis: Long): ByteArray?
    override fun close()

    companion object {
        /** A fresh engine, or null when libass is not loadable here. */
        fun open(): LibassEngine?

        /** libass' own version word, `0x01705000` for 0.17.5, or 0 when nothing is loadable. */
        fun libraryVersion(): Int
    }
}

/**
 * Reads font files out of [directories] in the order [pickFontFiles] gives them, the platform's
 * Latin sans face first, until [budgetBytes] is spent.
 * Empty on platforms whose libass finds fonts itself, and on ones with no filesystem.
 */
internal expect fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>>
