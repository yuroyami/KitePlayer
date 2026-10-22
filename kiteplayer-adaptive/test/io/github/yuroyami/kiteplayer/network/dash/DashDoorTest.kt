package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DASH door with a transport that is not Ktor's (#546): any reader that answers with a manifest
 * gets the reader of the presentation, and every request goes through the functions it was given.
 */
class DashDoorTest {

    private val manifest = """<?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT8S" minBufferTime="PT2S">
          <Period id="0" start="PT0S">
            <AdaptationSet id="0" contentType="video">
              <Representation id="v" mimeType="video/mp4" codecs="avc1.42c00d" bandwidth="300000" width="320" height="180">
                <SegmentTemplate timescale="1000" duration="2000" initialization="v-init.m4s" media="v-${'$'}Number${'$'}.m4s" startNumber="1"/>
              </Representation>
            </AdaptationSet>
            <AdaptationSet id="1" contentType="audio" lang="en">
              <Representation id="a" mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="96000" audioSamplingRate="48000">
                <SegmentTemplate timescale="1000" duration="2000" initialization="a-init.m4s" media="a-${'$'}Number${'$'}.m4s" startNumber="1"/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent().encodeToByteArray()

    @Test
    fun aManifestAnsweredByAnyReaderPlaysThroughTheDoor() = runTest {
        val found = Found(manifest, "https://cdn.test/show/manifest.mpd", contentType = null)
        val asked = mutableListOf<String>()
        val reader = assertNotNull(door(found, asked))

        val master = readAll(reader).decodeToString()
        assertTrue(master.startsWith("#EXTM3U"), "the reader stands in for an HLS master playlist:\n$master")
        assertTrue("TYPE=AUDIO" in master, "the sound of the second set is a rendition")

        val variant = master.lines().first { it.isNotBlank() && !it.startsWith("#") }
        val playlist = readAll(assertNotNull(reader.openRelated(variant))).decodeToString()
        assertTrue("https://cdn.test/show/v-init.m4s" in playlist, "the initialization is named at its own address:\n$playlist")
        assertTrue("https://cdn.test/show/v-4.m4s" in playlist, "the template's last segment is listed:\n$playlist")
        assertEquals(emptyList(), asked, "a playlist written from a template asks the network for nothing")

        assertEquals("v1", readAll(assertNotNull(reader.openRelated("https://cdn.test/show/v-1.m4s"))).decodeToString())
        assertEquals(listOf("https://cdn.test/show/v-1.m4s"), asked, "a segment opens through the transport")

        assertEquals(false, found.closed)
        reader.close()
        assertTrue(found.closed, "the reader of the presentation closes the reader the manifest came from")
    }

    @Test
    fun aManifestIsRecognisedByItsFirstBytesWhenItsTypeLeavesRoomForOne() = runTest {
        val found = Found(manifest, "https://cdn.test/play?id=7", contentType = "text/xml; charset=utf-8")
        assertNotNull(door(found))
        assertEquals(1, found.peeks, "the root element was read once, with the peek")
    }

    @Test
    fun aReaderThatDoesNotAnswerWithAManifestIsLeftAlone() = runTest {
        val media = Found(ByteArray(64) { it.toByte() }, "https://cdn.test/film.mp4", contentType = "video/mp4")
        assertNull(door(media))
        assertEquals(0, media.peeks, "a response that says it is media is not looked at")
        assertEquals(0, media.reads)

        val page = Found("<html><body>no</body></html>".encodeToByteArray(), "https://cdn.test/page", contentType = "text/html")
        assertNull(door(page))
        assertEquals(0, page.reads, "a reader that is left alone still has every byte to give")
    }

    @Test
    fun anAddressTheTransportDoesNotTakeIsRefused() = runTest {
        val found = Found(manifest, "https://cdn.test/show/manifest.mpd", contentType = "application/dash+xml")
        val reader = assertNotNull(
            DashDoor.readerIfManifest(io = found, peek = found::peek, open = { null }, dateOf = { null }),
        )
        assertFailsWith<DashUrlRefusedException> { reader.openRelated("https://cdn.test/show/v-1.m4s") }
    }

    /**
     * The player in a Web Worker has no thread to wait on, so its backend refuses a reader that
     * suspends. Its requests are synchronous, and the door must then finish in the caller's own
     * frame: the open, the master playlist, a media playlist and a segment.
     */
    @Test
    fun theDoorFinishesWithoutSuspendingWhenItsTransportDoes() {
        val found = Found(manifest, "https://cdn.test/show/manifest.mpd", contentType = null)
        val work: suspend () -> String = {
            val reader = assertNotNull(door(found))
            val master = readAll(reader).decodeToString()
            val variant = master.lines().first { it.isNotBlank() && !it.startsWith("#") }
            readAll(assertNotNull(reader.openRelated(variant)))
            readAll(assertNotNull(reader.openRelated("https://cdn.test/show/a-2.m4s"))).decodeToString()
        }
        var failure: Throwable? = null
        val answer = work.startCoroutineUninterceptedOrReturn(
            object : Continuation<String> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<String>) {
                    failure = result.exceptionOrNull()
                }
            },
        )
        assertNull(failure)
        assertTrue(answer !== COROUTINE_SUSPENDED, "the door suspended over a transport that never does")
        assertEquals("a2", answer)
    }

    @Test
    fun aRedirectIsJudgedByThePolicyOfTheManifest() {
        fun redirect(policy: DashUrlPolicy, manifest: String, to: String) = DashDoor.requireRedirectAllowed(
            policy = policy,
            shown = "https://cdn.test/v-1.m4s",
            manifestScheme = manifest.substringBefore("://"),
            manifestOrigin = manifest,
            toScheme = to.substringBefore(':'),
            toOrigin = to,
        )
        redirect(DashUrlPolicy.Default, "https://cdn.test:443", "https://other.test:443")
        redirect(DashUrlPolicy.Default, "http://cdn.test:80", "https://cdn.test:443")
        redirect(DashUrlPolicy.SameOrigin, "https://cdn.test:443", "https://cdn.test:443")
        redirect(DashUrlPolicy(allowSchemeDowngrade = true), "https://cdn.test:443", "http://cdn.test:80")

        val downgrade = assertFailsWith<DashUrlRefusedException> { redirect(DashUrlPolicy.Default, "https://cdn.test:443", "http://cdn.test:80") }
        assertTrue("from an https manifest" in downgrade.message.orEmpty(), downgrade.message)
        val scheme = assertFailsWith<DashUrlRefusedException> { redirect(DashUrlPolicy.Default, "https://cdn.test:443", "file:") }
        assertTrue("scheme 'file'" in scheme.message.orEmpty(), scheme.message)
        val origin = assertFailsWith<DashUrlRefusedException> { redirect(DashUrlPolicy.SameOrigin, "https://cdn.test:443", "https://other.test:443") }
        assertTrue("sameOriginOnly" in origin.message.orEmpty(), origin.message)
    }

    /** The door over [found], with every address a segment reader is opened for added to [asked]. */
    private suspend fun door(found: Found, asked: MutableList<String> = mutableListOf()): MediaIo? = DashDoor.readerIfManifest(
        io = found,
        peek = found::peek,
        open = { url ->
            asked += url
            // Each segment answers with its own name, "v1" for v-1.m4s.
            Found(url.substringAfterLast('/').substringBefore('.').replace("-", "").encodeToByteArray(), url, contentType = "video/mp4")
        },
        dateOf = { null },
    )

    private suspend fun readAll(io: MediaIo): ByteArray {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(4096)
        while (true) {
            val count = io.read(buffer, 0, buffer.size)
            if (count < 0) break
            for (i in 0 until count) out += buffer[i]
        }
        return out.toByteArray()
    }

    /** A reader with an address and a type, as a transport hands the door one, that counts what is asked of it. */
    private class Found(private val bytes: ByteArray, override val location: String, override val contentType: String?) : MediaIo {
        private var position = 0
        var peeks = 0
        var reads = 0
        var closed = false
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        fun peek(count: Int): ByteArray {
            peeks++
            return bytes.copyOf(minOf(count, bytes.size))
        }

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() {
            closed = true
        }
    }
}
