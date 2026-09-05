@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The typesetter against the real libass on this host.
 *
 * The reference comparison: every script in [TypesetCorpus] is rendered twice, once opened whole
 * and once streamed event by event the way the engine streams a container track, at several
 * instants inside every event. The two must be identical to the byte. The threshold is zero
 * differing bytes, and the reasoning is short: same library, same fonts, same machine, same
 * document, so any difference is a defect in the streaming path and not a rendering opinion. A
 * looser threshold would hide exactly the class of bug this test exists for, an event that arrives
 * with the wrong timing or a header that lost a style.
 */
class LibassTypesetterTest {

    private val frame = TypesetFrame(width = 1280, height = 720, videoWidth = 640, videoHeight = 360)

    private fun sample(images: List<OverlayImage>): Pair<Int, Long> {
        var visible = 0L
        images.forEach { image ->
            val px = image.bitmap.pixels
            var at = 3
            while (at < px.size) {
                if ((px[at].toInt() and 0xFF) > 32) visible++
                at += 4
            }
        }
        return images.size to visible
    }

    private fun assertSameImages(name: String, at: Long, document: List<OverlayImage>, streamed: List<OverlayImage>) {
        assertEquals(document.size, streamed.size, "$name at $at ms: region counts differ")
        document.zip(streamed).forEachIndexed { index, (a, b) ->
            assertEquals(a.x to a.y, b.x to b.y, "$name at $at ms: region $index sits elsewhere")
            assertEquals(a.bitmap.width to a.bitmap.height, b.bitmap.width to b.bitmap.height, "$name at $at ms: region $index size")
            assertTrue(a.bitmap.pixels.contentEquals(b.bitmap.pixels), "$name at $at ms: region $index pixels differ")
        }
    }

    @Test
    fun theModuleRegistersItsProviderByBeingPresent() {
        assertTrue(
            KiteLibass.PROVIDER_ID in SubtitleTypesetters.installed(),
            "the libass provider is not installed; discovery found ${SubtitleTypesetters.installed()}",
        )
        assertTrue(KiteLibass.isAvailable(), "libass is not loadable on this host")
        assertTrue(KiteLibass.libraryVersion() >= 0x01700000, "libass ${KiteLibass.libraryVersion().toString(16)} is older than 0.17")
    }

    @Test
    fun everyCorpusScriptStreamsToTheBytesItsWholeDocumentRenders() {
        TypesetCorpus.scripts.forEach { (name, lines) ->
            val document = LibassTypesetter()
            val streamed = LibassTypesetter()
            try {
                document.openDocument((TypesetCorpus.header + lines.joinToString("\n") + "\n").encodeToByteArray())
                streamed.openTrack(TypesetCorpus.header.encodeToByteArray())
                TypesetCorpus.chunks(lines).forEach { chunk ->
                    streamed.addEvent(chunk.payload.encodeToByteArray(), chunk.startMillis, chunk.durationMillis)
                }
                var visibleSomewhere = false
                // Null means "what it was", so each path carries its last picture forward, the way
                // the engine's lane does. Inside every event, at the fade-in, mid-animation, and
                // the fade-out, plus before and after everything.
                var lastDocument: List<OverlayImage> = emptyList()
                var lastStreamed: List<OverlayImage> = emptyList()
                listOf(500L, 1_050L, 1_500L, 2_200L, 3_000L, 3_700L, 4_450L, 4_900L, 6_000L).forEach { at ->
                    val a = document.render(at, frame)
                    val b = streamed.render(at, frame)
                    assertEquals(a == null, b == null, "$name at $at ms: one path changed and the other did not")
                    a?.let { lastDocument = it }
                    b?.let { lastStreamed = it }
                    val (regions, visible) = sample(lastDocument)
                    if (visible > 0) visibleSomewhere = true
                    assertSameImages(name, at, lastDocument, lastStreamed)
                    if (at in 1_100..4_900) assertTrue(regions > 0, "$name at $at ms drew nothing")
                }
                assertTrue(visibleSomewhere, "$name never drew a visible pixel; the font system found no face")
            } finally {
                document.close()
                streamed.close()
            }
        }
    }

    @Test
    fun anAnimatedSignChangesBetweenTwoInstantsAndAStaticLineDoesNot() {
        LibassTypesetter().use { typesetter ->
            typesetter.openTrack(TypesetCorpus.header.encodeToByteArray())
            TypesetCorpus.chunks(TypesetCorpus.scripts.getValue("moving-sign")).forEach {
                typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis)
            }
            val first = assertNotNull(typesetter.render(2_000, frame), "the first render must answer")
            val second = assertNotNull(typesetter.render(2_500, frame), "a moving sign half a second later is a changed picture")
            assertTrue(first.isNotEmpty() && second.isNotEmpty())
            val signBefore = first.minByOrNull { it.y }!!
            val signAfter = second.minByOrNull { it.y }!!
            assertTrue(signAfter.x > signBefore.x, "the sign did not move right: ${signBefore.x} then ${signAfter.x}")
            // Both events end at five seconds: the picture empties once, then stays unchanged.
            assertEquals(emptyList(), typesetter.render(6_000, frame), "after the last event the picture empties")
            assertNull(typesetter.render(6_040, frame), "an empty picture stays unchanged")
        }
        LibassTypesetter().use { typesetter ->
            // A static line alone: the first frame answers, the next one forty milliseconds on does not.
            typesetter.openTrack(TypesetCorpus.header.encodeToByteArray())
            val still = TypesetCorpus.chunks(TypesetCorpus.scripts.getValue("moving-sign"))[1]
            typesetter.addEvent(still.payload.encodeToByteArray(), still.startMillis, still.durationMillis)
            assertTrue(assertNotNull(typesetter.render(2_000, frame)).isNotEmpty())
            assertNull(typesetter.render(2_040, frame), "a static line 40 ms later is not a changed picture")
            assertNull(typesetter.render(4_000, frame), "a static line two seconds later is not a changed picture")
        }
    }

    @Test
    fun clearingEventsEmptiesThePictureAndRefeedingRestoresIt() {
        LibassTypesetter().use { typesetter ->
            typesetter.openTrack(TypesetCorpus.header.encodeToByteArray())
            val chunks = TypesetCorpus.chunks(TypesetCorpus.scripts.getValue("karaoke-fill"))
            chunks.forEach { typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis) }
            val shown = assertNotNull(typesetter.render(2_000, frame))
            assertTrue(shown.isNotEmpty())
            typesetter.clearEvents()
            assertEquals(emptyList(), typesetter.render(2_000, frame), "a clear must empty the picture")
            chunks.forEach { typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis) }
            val again = assertNotNull(typesetter.render(2_000, frame), "re-fed events must redraw")
            assertSameImages("karaoke-fill after clear", 2_000, shown, again)
        }
    }

    @Test
    fun theFrameGeometryPlacesTheLineInsideTheFittedPicture() {
        LibassTypesetter().use { typesetter ->
            typesetter.openDocument((TypesetCorpus.header + TypesetCorpus.scripts.getValue("moving-sign")[1] + "\n").encodeToByteArray())
            // A 16:9 picture on a 4:3-ish surface: bars top and bottom, 90 px each.
            val letterboxed = TypesetFrame(width = 1280, height = 900, videoWidth = 1280, videoHeight = 720, marginTop = 90, marginBottom = 90)
            val images = assertNotNull(typesetter.render(2_000, letterboxed))
            assertTrue(images.isNotEmpty())
            val bottom = images.maxOf { it.y + it.bitmap.height }
            assertTrue(bottom <= 900 - 90 + 2, "text reached y=$bottom, into the bottom bar")
            assertTrue(bottom > 450, "text sat at y=$bottom, above the middle of a bottom-aligned line")
            val scaled = assertNotNull(typesetter.render(2_000, letterboxed.copy(fontScale = 2f)), "a scale change is a changed picture")
            assertTrue(scaled.sumOf { it.bitmap.width * it.bitmap.height } > images.sumOf { it.bitmap.width * it.bitmap.height })
        }
    }

    @Test
    fun aFontFromMemoryIsAcceptedWithoutAChangeOfPicture() {
        LibassTypesetter().use { typesetter ->
            typesetter.addFont("Bogus.ttf", "not a font".encodeToByteArray())
            typesetter.openTrack(TypesetCorpus.header.encodeToByteArray())
            TypesetCorpus.chunks(TypesetCorpus.scripts.getValue("moving-sign")).forEach {
                typesetter.addEvent(it.payload.encodeToByteArray(), it.startMillis, it.durationMillis)
            }
            assertTrue(assertNotNull(typesetter.render(2_000, frame)).isNotEmpty(), "a rejected font left nothing to draw with")
        }
    }
}
