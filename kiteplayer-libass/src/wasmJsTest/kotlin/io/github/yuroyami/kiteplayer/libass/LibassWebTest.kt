@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web half against the real module: `kiteass.mjs` is imported from beside the compiled test,
 * the corpus is streamed and rendered whole, and the two must match byte for byte, the same
 * comparison the hosts make. Under node a system font is read through `node:fs` so pixels appear;
 * in a browser there is no filesystem and no system font, so the comparison there proves the
 * streaming path and the module plumbing, and the visible-pixel check is skipped with a note.
 */
class LibassWebTest {

    private val frame = TypesetFrame(width = 1280, height = 720, videoWidth = 640, videoHeight = 360)

    /**
     * Under node the module sits beside the compiled test, as a resource of this module; under
     * karma the test bundle lives in a temporary directory and the module is served from the
     * root through the proxies in karma.config.d, so the second spelling is tried when the first
     * cannot be fetched.
     */
    private suspend fun loadModule() {
        if (!KiteLibassWeb.isLoaded) {
            runCatching { KiteLibassWeb.load("./kiteass.mjs") }.onFailure { KiteLibassWeb.load("/kiteass.mjs") }
        }
        assertTrue(KiteLibassWeb.isLoaded, "kiteass.mjs did not load")
    }

    /** A sans-serif TrueType file from the host, or null where there is no filesystem. */
    private suspend fun hostFont(): ByteArray? {
        val candidates = listOf(
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        )
        for (path in candidates) {
            val packed = runCatching { webReadFileLatin1(path).await<JsAny?>() }.getOrNull() ?: continue
            val text = packed.toString()
            if (text.isEmpty()) continue
            return ByteArray(text.length) { text[it].code.toByte() }
        }
        return null
    }

    private fun visible(images: List<OverlayImage>): Long {
        var count = 0L
        images.forEach { image ->
            val px = image.bitmap.pixels
            var at = 3
            while (at < px.size) {
                if ((px[at].toInt() and 0xFF) > 32) count++
                at += 4
            }
        }
        return count
    }

    private fun assertSameImages(name: String, at: Long, a: List<OverlayImage>, b: List<OverlayImage>) {
        assertEquals(a.size, b.size, "$name at $at ms: region counts differ")
        a.zip(b).forEachIndexed { index, (x, y) ->
            assertEquals(x.x to x.y, y.x to y.y, "$name at $at ms: region $index sits elsewhere")
            assertTrue(x.bitmap.pixels.contentEquals(y.bitmap.pixels), "$name at $at ms: region $index pixels differ")
        }
    }

    @Test
    fun theModuleRegistersItsProviderByBeingPresent() {
        assertTrue(KiteLibass.PROVIDER_ID in SubtitleTypesetters.installed(), "installed: ${SubtitleTypesetters.installed()}")
    }

    @Test
    fun theWebModuleLoadsAndReportsItsVersion() = runTest {
        loadModule()
        assertTrue(KiteLibass.libraryVersion() >= 0x01700000, "libass ${KiteLibass.libraryVersion().toString(16)}")
        assertTrue(KiteLibass.isAvailable())
    }

    @Test
    fun everyCorpusScriptStreamsToTheBytesItsWholeDocumentRenders() = runTest {
        loadModule()
        val font = hostFont()
        if (font == null) println("LibassWebTest: no host font readable here; comparing pictures without one")
        var visibleSomewhere = false
        TypesetCorpus.scripts.forEach { (name, lines) ->
            val document = LibassTypesetter()
            val streamed = LibassTypesetter()
            try {
                font?.let { document.addFont("HostSans.ttf", it); streamed.addFont("HostSans.ttf", it) }
                document.openDocument((TypesetCorpus.header + lines.joinToString("\n") + "\n").encodeToByteArray())
                streamed.openTrack(TypesetCorpus.header.encodeToByteArray())
                TypesetCorpus.chunks(lines).forEach { chunk ->
                    streamed.addEvent(chunk.payload.encodeToByteArray(), chunk.startMillis, chunk.durationMillis)
                }
                var lastDocument: List<OverlayImage> = emptyList()
                var lastStreamed: List<OverlayImage> = emptyList()
                listOf(500L, 1_500L, 2_200L, 3_000L, 4_450L, 6_000L).forEach { at ->
                    val a = document.render(at, frame)
                    val b = streamed.render(at, frame)
                    assertEquals(a == null, b == null, "$name at $at ms: one path changed and the other did not")
                    a?.let { lastDocument = it }
                    b?.let { lastStreamed = it }
                    assertSameImages(name, at, lastDocument, lastStreamed)
                    if (visible(lastDocument) > 0) visibleSomewhere = true
                }
            } finally {
                document.close()
                streamed.close()
            }
        }
        if (font != null) assertTrue(visibleSomewhere, "a font was loaded and nothing ever drew a visible pixel")
    }

    @Test
    fun anEnginePendingTheModuleReplaysWhatItWasToldOnceTheModuleLands() = runTest {
        // Whatever the order of the tests, the module is loaded by now; a fresh engine is not
        // pending. The pending path is exercised by the corpus test's first LibassTypesetter when
        // this test class runs first, and by the browser page every time. Here the contract that
        // matters after loading is checked: clear empties, re-feed restores, unchanged is null.
        loadModule()
        val font = hostFont()
        LibassTypesetter().use { typesetter ->
            font?.let { typesetter.addFont("HostSans.ttf", it) }
            typesetter.openTrack(TypesetCorpus.header.encodeToByteArray())
            val chunks = TypesetCorpus.chunks(TypesetCorpus.scripts.getValue("moving-sign"))
            chunks.forEach { typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis) }
            val first = assertNotNull(typesetter.render(2_000, frame), "the first render must answer")
            if (font != null) assertTrue(first.isNotEmpty(), "a font was loaded and the sign drew nothing")
            if (first.isNotEmpty()) {
                typesetter.clearEvents()
                assertEquals(emptyList(), typesetter.render(2_000, frame), "a clear must empty the picture")
                chunks.forEach { typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis) }
                val again = assertNotNull(typesetter.render(2_000, frame), "re-fed events must redraw")
                assertSameImages("moving-sign after clear", 2_000, first, again)
            } else {
                // No font anywhere (a browser): the picture is empty before and after a clear. A clear
                // forces one redraw by design, so the answer is an empty picture or "unchanged", never
                // a region; re-fed events with no font draw nothing new either.
                typesetter.clearEvents()
                val cleared = typesetter.render(2_000, frame)
                assertTrue(cleared == null || cleared.isEmpty(), "a clear with no font drew $cleared")
                chunks.forEach { typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis) }
                val refed = typesetter.render(2_000, frame)
                assertTrue(refed == null || refed.isEmpty(), "re-fed events with no font drew $refed")
            }
            assertNull(typesetter.render(2_000, frame), "the same instant again is not a change")
        }
    }
}

// Node only: the promise rejects in a browser, which the caller treats as "no font here".
@JsFun("(p) => import(/* webpackIgnore: true */ 'node:fs').then(fs => fs.existsSync(p) ? fs.readFileSync(p, 'latin1') : null)")
private external fun webReadFileLatin1(path: String): Promise<JsAny?>
