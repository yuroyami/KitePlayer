@file:OptIn(KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.fittedMargins
import io.github.yuroyami.kiteplayer.spi.MediaAttachment
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetter
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetterProvider
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleFont
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The typesetting lane in virtual time, against a recording fake of the engine: an ASS track is
 * routed to the installed typesetter and the snapshot says so, the Kotlin tier stays in charge when
 * typesetting is off or the engine cannot start, an animated cue re-renders as time advances, a
 * seek clears and re-feeds, fonts arrive before the header, and deselecting withdraws the images.
 */
class SubtitleTypesettingTest {

    /** Records every call and answers a render whose pixels change every [animationStepMillis]. */
    private class FakeTypesetter(private val animationStepMillis: Long = Long.MAX_VALUE) : SubtitleTypesetter {
        val headers = mutableListOf<ByteArray>()
        val documents = mutableListOf<ByteArray>()
        val events = mutableListOf<String>()
        val fonts = mutableListOf<String>()
        val order = mutableListOf<String>()
        var clears = 0
        var renders = 0
        var closed = false
        private var lastKey: Long = Long.MIN_VALUE

        override fun openTrack(header: ByteArray) {
            headers += header
            order += "header"
        }

        override fun openDocument(script: ByteArray) {
            documents += script
            order += "document"
        }

        override fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long) {
            events += "${payload.decodeToString()}@$startMillis+$durationMillis"
        }

        override fun clearEvents() {
            clears++
            lastKey = Long.MIN_VALUE
        }

        override fun addFont(name: String, data: ByteArray) {
            fonts += name
            order += "font:$name"
        }

        override fun render(timeMillis: Long, frame: TypesetFrame): List<OverlayImage>? {
            renders++
            // Visible from 1 s to 3 s of media, like the scripted cue; the key changes with time
            // only when animating, so a static line answers "unchanged" after its first frame.
            val visible = timeMillis in 1_000 until 3_000
            val key = if (!visible) -1L else if (animationStepMillis == Long.MAX_VALUE) 1L else timeMillis / animationStepMillis
            if (key == lastKey) return null
            lastKey = key
            if (!visible) return emptyList()
            val pixels = ByteArray(2 * 2 * 4) { (key and 0x7F).toByte() }
            return listOf(OverlayImage(x = frame.marginLeft, y = frame.height - frame.marginBottom - 2, bitmap = RgbaBitmap(2, 2, pixels)))
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeProvider(
        override val id: String = "test.typesetter",
        private val factory: () -> SubtitleTypesetter?,
    ) : SubtitleTypesetterProvider {
        var created = 0
        val instances = mutableListOf<SubtitleTypesetter>()
        override fun create(): SubtitleTypesetter? {
            created++
            return factory()?.also { instances += it }
        }
    }

    private fun cue(startMs: Long, endMs: Long, text: String) = SubtitleCue.Text(
        startMicros = startMs * 1000,
        endMicros = endMs * 1000,
        spans = listOf(StyledSpan(text)),
    )

    private val header = "[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\n".encodeToByteArray()

    private fun assScript(
        cues: List<SubtitleCue> = listOf(cue(1_000, 3_000, "typeset me")),
        attachments: List<MediaAttachment> = emptyList(),
    ) = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = cues,
        subtitleCodec = "ass",
        subtitleHeader = header,
        attachments = attachments,
    )

    private fun config(typesetting: Boolean = true, fonts: List<SubtitleFont> = emptyList()) = PlayerConfig(
        subtitles = SubtitleConfig(preferredLanguages = listOf("eng"), typesetting = typesetting, fonts = fonts),
        progressInterval = 50.milliseconds,
    )

    @BeforeTest
    fun clearRegistry() = SubtitleTypesetters.resetForTesting()

    @AfterTest
    fun clearRegistryAfter() = SubtitleTypesetters.resetForTesting()

    @Test
    fun anAssTrackIsTypesetAndTheSnapshotNamesTheEngine() = runTest {
        val fake = FakeTypesetter()
        SubtitleTypesetters.register(FakeProvider { fake })
        val harness = CoreHarness(this, script = assScript(), config = config())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)

        assertEquals("test.typesetter", harness.core.snapshots.value.subtitleTypesetter)
        assertEquals(1, fake.headers.size, "the track header never reached the typesetter")
        assertContentEquals(header, fake.headers.single())
        assertEquals(listOf("0,0,Default,,0,0,0,,typeset me@1000+2000"), fake.events)
        val shown = harness.renderer!!.overlays.filterNotNull().filter { it.images.isNotEmpty() }
        assertTrue(shown.isNotEmpty(), "the typeset image never reached the renderer")
        assertEquals(2, shown.first().images.single().bitmap.width, "the image on the glass is not the typesetter's")
        assertTrue(harness.output.rasterizedCueTexts.isEmpty(), "the rasterizer drew a track the typesetter owns")
        assertTrue(harness.core.snapshots.value.tracks.selectedSubtitle != null)

        harness.run(2.seconds)
        val last = harness.renderer!!.overlays.filterNotNull().last()
        assertTrue(last.images.isEmpty(), "the typeset image did not clear after its cue ended")
        harness.close()
        assertTrue(fake.closed, "closing the player did not close the typesetter")
    }

    @Test
    fun typesettingOffKeepsTheKotlinTier() = runTest {
        val provider = FakeProvider { FakeTypesetter() }
        SubtitleTypesetters.register(provider)
        val harness = CoreHarness(this, script = assScript(), config = config(typesetting = false))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertNull(harness.core.snapshots.value.subtitleTypesetter)
        assertEquals(0, provider.created, "typesetting is off and the provider was still asked")
        assertTrue(
            harness.output.rasterizedCueTexts.any { it.contains("typeset me") },
            "the Kotlin tier did not draw the cue: ${harness.output.rasterizedCueTexts}",
        )
        harness.close()
    }

    @Test
    fun aNonAssTrackNeverMeetsTheTypesetter() = runTest {
        val provider = FakeProvider { FakeTypesetter() }
        SubtitleTypesetters.register(provider)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 6_000_000, subtitleCues = listOf(cue(1_000, 3_000, "plain"))),
            config = config(),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertNull(harness.core.snapshots.value.subtitleTypesetter)
        assertEquals(0, provider.created)
        assertTrue(harness.output.rasterizedCueTexts.any { it.contains("plain") })
        harness.close()
    }

    @Test
    fun anAnimatedCueRerendersWhileTimeAdvancesAndAStaticOneDoesNot() = runTest {
        val animated = FakeTypesetter(animationStepMillis = 200)
        SubtitleTypesetters.register(FakeProvider { animated })
        val harness = CoreHarness(this, script = assScript(), config = config())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2900.milliseconds)
        val shown = harness.renderer!!.overlays.filterNotNull().filter { it.images.isNotEmpty() }
        val distinct = shown.map { it.images.single().bitmap.pixels[0] }.distinct()
        // Two seconds of a cue stepping every 200 ms is ten pictures. Nine allows the sampling edges.
        assertTrue(distinct.size >= 9, "an animated cue published ${distinct.size} distinct pictures, expected about ten")
        // The cadence is bounded: one render per 40 ms video frame over about 2.9 s, plus slack for
        // the cue edges and the geometry change of the first pass.
        assertTrue(animated.renders <= 2900 / 40 + 20, "rendered ${animated.renders} times in 2.9 s")
        assertTrue(animated.renders >= 2000 / 40 / 2, "rendered only ${animated.renders} times in 2.9 s")
        harness.close()

        SubtitleTypesetters.resetForTesting()
        val still = FakeTypesetter()
        SubtitleTypesetters.register(FakeProvider { still })
        val second = CoreHarness(this, script = assScript(), config = config())
        second.openWithRenderer()
        second.core.play()
        second.run(2900.milliseconds)
        val published = second.renderer!!.overlays.filterNotNull().filter { it.images.isNotEmpty() }
        // One appearance, and no republication while the picture is unchanged: contentHash would
        // differ on every publish, so counting publications counts republications.
        assertEquals(1, published.size, "a static cue was republished ${published.size} times")
        second.close()
    }

    @Test
    fun aSeekClearsTheEventsAndTheDemuxerRefeedsThem() = runTest {
        val fake = FakeTypesetter()
        SubtitleTypesetters.register(FakeProvider { fake })
        val harness = CoreHarness(this, script = assScript(), config = config())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertEquals(1, fake.events.size)
        harness.core.seek(Pts(200_000), SeekMode.Precise)
        harness.run(1500.milliseconds)
        assertEquals(1, fake.clears, "the seek did not clear the typesetter's events")
        assertEquals(2, fake.events.size, "the seek did not re-feed the cue: ${fake.events}")
        assertTrue(fake.events.all { it == "0,0,Default,,0,0,0,,typeset me@1000+2000" })
        harness.close()
    }

    @Test
    fun deselectingTheTrackWithdrawsTheTypesetImagesAndClosesTheEngine() = runTest {
        val fake = FakeTypesetter()
        SubtitleTypesetters.register(FakeProvider { fake })
        val harness = CoreHarness(this, script = assScript(), config = config())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertTrue(harness.renderer!!.overlays.filterNotNull().any { it.images.isNotEmpty() })

        val change = harness.core.selectTrack(TrackKind.Subtitle, null)
        assertIs<TrackChange.Applied>(change)
        harness.run(300.milliseconds)
        assertTrue(fake.closed, "the typesetter outlived its track")
        assertNull(harness.core.snapshots.value.subtitleTypesetter)
        val after = harness.renderer!!.overlays.filterNotNull().last()
        assertTrue(after.images.isEmpty(), "the typeset image stayed on the glass after the track was deselected")
        harness.close()
    }

    @Test
    fun aProviderThatCannotStartWarnsOnceAndTheKotlinTierDraws() = runTest {
        val provider = FakeProvider { null }
        SubtitleTypesetters.register(provider)
        val harness = CoreHarness(this, script = assScript(), config = config())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        val warnings = harness.events.filterIsInstance<PlayerEvent.Warning>().map { it.warning }
            .filterIsInstance<PlaybackWarning.TypesetterUnavailable>()
        assertEquals(1, warnings.size, "expected one typesetter warning, got $warnings")
        assertEquals("test.typesetter", warnings.single().provider)
        assertNull(harness.core.snapshots.value.subtitleTypesetter)
        assertTrue(harness.output.rasterizedCueTexts.any { it.contains("typeset me") }, "the fallback did not draw")
        // Reselecting the same track must not ask the broken provider again.
        val id = assertNotNull(harness.core.snapshots.value.tracks.selectedSubtitle)
        harness.core.selectTrack(TrackKind.Subtitle, null)
        harness.run(100.milliseconds)
        harness.core.selectTrack(TrackKind.Subtitle, id)
        harness.run(500.milliseconds)
        assertEquals(1, provider.created, "a refused provider was asked again")
        harness.close()
    }

    @Test
    fun configuredAndAttachedFontsReachTheTypesetterBeforeTheHeader() = runTest {
        val fake = FakeTypesetter()
        SubtitleTypesetters.register(FakeProvider { fake })
        val harness = CoreHarness(
            this,
            script = assScript(
                attachments = listOf(
                    MediaAttachment("Sign.otf", "application/vnd.ms-opentype", ByteArray(8)),
                    MediaAttachment("cover.jpg", "image/jpeg", ByteArray(8)),
                    MediaAttachment("Body.ttf", null, ByteArray(8)),
                ),
            ),
            config = config(fonts = listOf(SubtitleFont("App.ttf", ByteArray(4)))),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1200.milliseconds)
        assertEquals(listOf("font:App.ttf", "font:Sign.otf", "font:Body.ttf", "header"), fake.order)
        harness.close()
    }

    @Test
    fun theSecondaryLaneStillDrawsThroughTheRasterizerBesideTheTypesetImages() = runTest {
        val fake = FakeTypesetter()
        SubtitleTypesetters.register(FakeProvider { fake })
        val second = ScriptedSubtitleTrack(index = 7, cues = listOf(cue(1_000, 3_000, "second lane")), language = "fra")
        val script = MediaScript(
            durationUs = 6_000_000,
            subtitleCues = listOf(cue(1_000, 3_000, "typeset me")),
            subtitleCodec = "ass",
            subtitleHeader = header,
            additionalSubtitleTracks = listOf(second),
        )
        val harness = CoreHarness(this, script = script, config = config())
        harness.openWithRenderer()
        harness.core.selectSecondarySubtitle(TrackId(7))
        harness.core.play()
        harness.run(1500.milliseconds)
        val shown = harness.renderer!!.overlays.filterNotNull().filter { it.images.isNotEmpty() }
        assertTrue(shown.isNotEmpty())
        assertEquals(2, shown.last().images.size, "expected the typeset image plus the rasterized secondary cue")
        assertTrue(harness.output.rasterizedCueTexts.flatten().contains("second lane"))
        assertTrue(harness.output.rasterizedCueTexts.flatten().none { it == "typeset me" })
        harness.close()
    }

    @Test
    fun fittedMarginsFollowTheScaleMode() {
        // A 4:3 picture on a 16:9 surface sits between two pillars.
        assertContentEquals(intArrayOf(0, 0, 160, 160), fittedMargins(1280, 720, 640, 480, VideoScale.Fit))
        // A 2.39:1 picture on 16:9 sits between two bars.
        assertContentEquals(intArrayOf(138, 139, 0, 0), fittedMargins(1920, 1080, 1920, 803, VideoScale.Fit))
        // Same shape, no bars; and an odd remainder goes to the far edge.
        assertContentEquals(intArrayOf(0, 0, 0, 0), fittedMargins(1280, 720, 1920, 1080, VideoScale.Fit))
        assertContentEquals(intArrayOf(0, 0, 0, 1), fittedMargins(641, 480, 640, 480, VideoScale.Fit))
        // Fill and Stretch cover the surface.
        assertContentEquals(intArrayOf(0, 0, 0, 0), fittedMargins(1280, 720, 640, 480, VideoScale.Fill))
        assertContentEquals(intArrayOf(0, 0, 0, 0), fittedMargins(1280, 720, 640, 480, VideoScale.Stretch))
    }
}
