package io.github.yuroyami.kiteplayer.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The header reading and the freshness arithmetic of the segment store (#547), with no server. */
class SegmentFreshnessTest {

    private val date = "Sun, 06 Nov 1994 08:49:37 GMT"
    private val dateSeconds = 784_111_777L

    private fun record(facts: ResponseFacts, now: Long = dateSeconds, request: Long = now): StoredResponse? =
        StoredResponse.of(facts, "http://host/seg", 100, ranged = true, contentType = "video/mp4", requestSeconds = request, nowSeconds = now)

    @Test
    fun allThreeHttpDateFormsAreRead() {
        assertEquals(dateSeconds, httpDateSeconds(date))
        assertEquals(dateSeconds, httpDateSeconds("Sunday, 06-Nov-94 08:49:37 GMT"))
        assertEquals(dateSeconds, httpDateSeconds("Sun Nov  6 08:49:37 1994"))
        assertEquals(0L, httpDateSeconds("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(951_782_400L, httpDateSeconds("Tue, 29 Feb 2000 00:00:00 GMT"))
        assertEquals(1_709_251_199L, httpDateSeconds("Thu, 29 Feb 2024 23:59:59 GMT"))
        assertEquals(2_000_000_000L, httpDateSeconds("Wed, 18 May 2033 03:33:20 GMT"))
    }

    @Test
    fun whatIsNotADateIsNotRead() {
        assertNull(httpDateSeconds(null))
        assertNull(httpDateSeconds(""))
        assertNull(httpDateSeconds("0"))
        assertNull(httpDateSeconds("-1"))
        assertNull(httpDateSeconds("Sun, 06 Foo 1994 08:49:37 GMT"))
        assertNull(httpDateSeconds("Sun, 06 Nov 1994 25:49:37 GMT"))
        assertNull(httpDateSeconds("Sun, 06 Nov 1994 08:49 GMT"))
    }

    @Test
    fun cacheControlDirectivesAreRead() {
        val plain = cacheDirectives(listOf("public, max-age=3600"))
        assertFalse(plain.noStore)
        assertFalse(plain.noCache)
        assertEquals(3600L, plain.maxAgeSeconds)

        assertTrue(cacheDirectives(listOf("No-Store")).noStore)
        assertTrue(cacheDirectives(listOf("private", "no-cache")).noCache)
        assertNull(cacheDirectives(listOf("private")).maxAgeSeconds)
        // A shared cache's lifetime is not this store's.
        assertNull(cacheDirectives(listOf("s-maxage=600")).maxAgeSeconds)
        assertEquals(60L, cacheDirectives(listOf("max-age=\"60\"")).maxAgeSeconds)
        // A lifetime that cannot be read is no lifetime at all, and the shortest of several wins.
        assertEquals(0L, cacheDirectives(listOf("max-age=soon")).maxAgeSeconds)
        assertEquals(10L, cacheDirectives(listOf("max-age=100", "max-age=10")).maxAgeSeconds)
        // A comma inside a quoted value does not start a directive.
        assertFalse(cacheDirectives(listOf("no-cache=\"a, no-store\"")).noStore)
        assertTrue(cacheDirectives(listOf("no-cache=\"set-cookie\"")).noCache)
    }

    @Test
    fun varyNamesAreReadAndAStarMatchesNothing() {
        assertEquals(emptyList(), varyNames(emptyList()))
        assertEquals(listOf("accept-encoding", "origin"), varyNames(listOf("Origin, Accept-Encoding", "origin")))
        assertNull(varyNames(listOf("Origin, *")))
    }

    @Test
    fun aResponseWithNothingToJudgeItByIsNotStored() {
        assertNull(record(ResponseFacts(date = date)))
        assertNull(record(ResponseFacts(entityTag = "W/\"weak\"", date = date)), "a weak tag proves nothing about a range")
        assertNull(record(ResponseFacts(lastModified = "yesterday", date = date)))
        assertNotNull(record(ResponseFacts(entityTag = "\"v1\"")))
        assertNotNull(record(ResponseFacts(lastModified = date)))
        assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=1"))))
        assertNotNull(record(ResponseFacts(expires = date)))
    }

    @Test
    fun noStoreAndVaryStarAreNeverStoredAndPrivateIs() {
        assertNull(record(ResponseFacts(cacheControl = listOf("no-store"), entityTag = "\"v1\"")))
        assertNull(record(ResponseFacts(vary = listOf("*"), entityTag = "\"v1\"")))
        assertNotNull(record(ResponseFacts(cacheControl = listOf("private, max-age=60"))))
    }

    @Test
    fun aStatedLifetimeDecidesFreshness() {
        val stored = assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=60"), date = date)))
        assertEquals(60L, stored.lifetimeSeconds())
        assertTrue(stored.isFresh(dateSeconds))
        assertTrue(stored.isFresh(dateSeconds + 59))
        assertFalse(stored.isFresh(dateSeconds + 60))
        // A clock that went back says nothing, so the server is asked.
        assertFalse(stored.isFresh(dateSeconds - 1))
    }

    @Test
    fun maxAgeWinsOverExpiresAndExpiresCountsFromTheDate() {
        val expires = "Sun, 06 Nov 1994 08:59:37 GMT"
        assertEquals(600L, assertNotNull(record(ResponseFacts(expires = expires, date = date))).lifetimeSeconds())
        assertEquals(5L, assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=5"), expires = expires, date = date))).lifetimeSeconds())
        // An Expires that is no date has already passed.
        assertEquals(0L, assertNotNull(record(ResponseFacts(expires = "0", date = date))).lifetimeSeconds())
    }

    @Test
    fun theAgeTheResponseArrivedWithCounts() {
        // The response was 50 s old by its Age header and took 2 s to arrive.
        val aged = assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=60"), date = date, age = "50"), request = dateSeconds - 2))
        assertEquals(52L, aged.initialAgeSeconds)
        assertTrue(aged.isFresh(dateSeconds + 7))
        assertFalse(aged.isFresh(dateSeconds + 8))
        // A Date in the past is an age too.
        val late = assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=60"), date = date), now = dateSeconds + 30))
        assertEquals(30L, late.initialAgeSeconds)
        assertFalse(late.isFresh(dateSeconds + 60))
    }

    @Test
    fun withoutAStatedLifetimeATenthOfTheTimeSinceTheLastChangeIsFresh() {
        val modified = "Sun, 06 Nov 1994 08:32:57 GMT" // 1000 s before the date
        val stored = assertNotNull(record(ResponseFacts(lastModified = modified, date = date)))
        assertEquals(100L, stored.lifetimeSeconds())
        assertTrue(stored.isFresh(dateSeconds + 99))
        assertFalse(stored.isFresh(dateSeconds + 100))
    }

    @Test
    fun theHeuristicLifetimeIsAtMostOneDay() {
        val stored = assertNotNull(record(ResponseFacts(lastModified = "Thu, 01 Jan 1970 00:00:00 GMT", date = date)))
        assertEquals(86_400L, stored.lifetimeSeconds())
    }

    @Test
    fun anEntityTagAloneIsNeverFresh() {
        val stored = assertNotNull(record(ResponseFacts(entityTag = "\"v1\"", date = date)))
        assertEquals(0L, stored.lifetimeSeconds())
        assertFalse(stored.isFresh(dateSeconds))
        assertEquals("If-None-Match" to "\"v1\"", stored.condition())
    }

    @Test
    fun noCacheIsStoredAndNeverFresh() {
        val stored = assertNotNull(record(ResponseFacts(cacheControl = listOf("no-cache, max-age=600"), entityTag = "\"v1\"")))
        assertFalse(stored.isFresh(dateSeconds))
    }

    @Test
    fun theConditionIsTheTagOrElseTheDateOfChange() {
        assertEquals("If-None-Match" to "\"v1\"", assertNotNull(record(ResponseFacts(entityTag = "\"v1\"", lastModified = date))).condition())
        assertEquals("If-Modified-Since" to date, assertNotNull(record(ResponseFacts(lastModified = date))).condition())
        assertNull(assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=1")))).condition())
    }

    @Test
    fun a304MakesTheRecordFreshAgainAndKeepsWhatItDoesNotName() {
        val stored = assertNotNull(record(ResponseFacts(cacheControl = listOf("max-age=60"), entityTag = "\"v1\"", date = date)))
        val later = dateSeconds + 500
        assertFalse(stored.isFresh(later))
        val renewed = stored.revalidated(ResponseFacts(), requestSeconds = later, nowSeconds = later)
        assertTrue(renewed.isFresh(later + 59))
        assertFalse(renewed.isFresh(later + 60))
        assertEquals("\"v1\"", renewed.entityTag)
        assertEquals(stored.size, renewed.size)
        // A 304 that names a lifetime replaces the old one.
        assertEquals(5L, stored.revalidated(ResponseFacts(cacheControl = listOf("max-age=5")), later, later).lifetimeSeconds())
    }

    @Test
    fun theRecordSurvivesItsEncoding() {
        val stored = assertNotNull(
            record(ResponseFacts(cacheControl = listOf("max-age=60"), entityTag = "\"v=1\"", lastModified = date, date = date, age = "3")),
        )
        val decoded = assertNotNull(StoredResponse.decode(stored.encode()))
        assertEquals(stored.location, decoded.location)
        assertEquals(stored.size, decoded.size)
        assertEquals(stored.ranged, decoded.ranged)
        assertEquals(stored.contentType, decoded.contentType)
        assertEquals("\"v=1\"", decoded.entityTag)
        assertEquals(stored.lastModified, decoded.lastModified)
        assertEquals(stored.date, decoded.date)
        assertEquals(stored.responseSeconds, decoded.responseSeconds)
        assertEquals(stored.initialAgeSeconds, decoded.initialAgeSeconds)
        assertEquals(stored.statedLifetimeSeconds, decoded.statedLifetimeSeconds)
        assertEquals(stored.noCache, decoded.noCache)
        assertNull(StoredResponse.decode("something else".encodeToByteArray()))
        assertNull(StoredResponse.decode(ByteArray(0)))
    }

    @Test
    fun theDigestIsSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }))
    }

    @Test
    fun aReferenceResolvesAsABrowserResolvesIt() {
        val base = "https://host/a/b/list.m3u8?token=1#frag"
        assertEquals(listOf("https://host/a/b/seg.ts"), resolveReference(base, "seg.ts"))
        assertEquals(listOf("https://host/a/b/c/seg.ts?x=1"), resolveReference(base, "c/seg.ts?x=1"))
        assertEquals(listOf("https://host/seg.ts"), resolveReference(base, "/seg.ts"))
        assertEquals(listOf("https://other/seg.ts"), resolveReference(base, "//other/seg.ts"))
        assertEquals(listOf("http://other/seg.ts"), resolveReference(base, "http://other/seg.ts"))
        assertEquals(listOf("https://host/a/b/list.m3u8?v=2"), resolveReference(base, "?v=2"))
        // Both spellings of an address with dot levels, because resolvers differ on an absolute one.
        assertEquals(listOf("https://host/a/seg.ts", "https://host/a/b/../seg.ts"), resolveReference(base, "../seg.ts"))
        assertEquals(listOf("https://host/seg.ts", "https://host/a/b/../../../seg.ts"), resolveReference(base, "../../../seg.ts"))
        assertEquals(listOf("https://host/a/b/seg.ts", "https://host/a/b/./seg.ts"), resolveReference(base, "./seg.ts"))
        assertEquals(listOf("https://host/seg.ts"), resolveReference("https://host", "seg.ts"))
    }

    private fun named(playlist: String, chunk: Int = 7): SegmentReuse {
        val reuse = SegmentReuse(directorySegmentStore(MemoryStoreFiles(), "/cache", 1_000)!!)
        val names = PlaylistNames(reuse, listOf("http://host/v/list.m3u8"))
        val bytes = playlist.encodeToByteArray()
        var at = 0
        while (at < bytes.size) {
            val count = minOf(chunk, bytes.size - at)
            names.feed(at.toLong(), bytes, at, count)
            at += count
        }
        names.end()
        return reuse
    }

    @Test
    fun aPlaylistThatHasEndedNamesItsSegmentsAndItsInitialization() {
        val reuse = named(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"700@0\"\r\n" +
                "#EXTINF:4,\nseg1.m4s\n#EXTINF:4,\nhttp://cdn/seg2.m4s?t=1\n#EXT-X-ENDLIST",
        )
        assertTrue(reuse.isSegment("http://host/v/init.mp4"))
        assertTrue(reuse.isSegment("http://host/v/seg1.m4s"))
        assertTrue(reuse.isSegment("http://cdn/seg2.m4s?t=1"))
        assertFalse(reuse.isSegment("http://host/v/list.m3u8"), "the playlist itself was named")
        assertFalse(reuse.isSegment("http://host/v/other.m4s"), "an address no playlist named was taken")
    }

    @Test
    fun aLivePlaylistNamesNothing() {
        val reuse = named("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nseg1.m4s\n#EXTINF:4,\nseg2.m4s\n")
        assertFalse(reuse.isSegment("http://host/v/seg1.m4s"))
        assertFalse(reuse.isSegment("http://host/v/init.mp4"))
    }

    @Test
    fun aMasterPlaylistNamesNothing() {
        val reuse = named("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/list.m3u8\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n")
        assertFalse(reuse.isSegment("http://host/v/low/list.m3u8"))
        assertFalse(reuse.isSegment("http://host/v/audio.m3u8"))
    }

    @Test
    fun aKeyIsNeverASegmentWhateverElseNamesIt() {
        val reuse = named(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n#EXTINF:4,\nseg1.ts\n" +
                "#EXT-X-SESSION-KEY:METHOD=AES-128,KEYFORMATURI=\"x\",URI=\"session.key\"\n#EXTINF:4,\nkey.bin\n#EXT-X-ENDLIST\n",
        )
        assertTrue(reuse.isKey("http://host/v/key.bin"))
        assertTrue(reuse.isKey("http://host/v/session.key"))
        assertFalse(reuse.isSegment("http://host/v/key.bin"), "a key was taken for a segment")
        assertTrue(reuse.isSegment("http://host/v/seg1.ts"))
    }

    @Test
    fun aKeyOfALivePlaylistIsKnownAtOnce() {
        assertTrue(named("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nseg1.ts\n").isKey("http://host/v/key.bin"))
    }

    @Test
    fun bytesThatAreNoPlaylistNameNothing() {
        val media = "\u0000\u0000\u0000\u0018ftypmp42\nseg1.ts\n#EXT-X-ENDLIST\n"
        assertFalse(named(media).isSegment("http://host/v/seg1.ts"))
    }

    @Test
    fun anAddressWithAVariableIsLeftOut() {
        val reuse = named("#EXTM3U\n#EXT-X-DEFINE:NAME=\"p\",VALUE=\"x\"\n#EXTINF:4,\n{\$p}/seg1.ts\n#EXT-X-ENDLIST\n")
        assertFalse(reuse.isSegment("http://host/v/{\$p}/seg1.ts"))
    }

    @Test
    fun theNameOfAnEntrySeparatesNamespacesLoginsAndVariedHeaders() {
        fun reuse(namespace: String) = SegmentReuse(directorySegmentStore(MemoryStoreFiles(), "/cache", 1_000, namespace)!!)
        val one = reuse("one")
        val uri = "http://host/seg.ts"
        val plain = one.entryName(uri, emptyMap(), emptyList())
        assertEquals(plain, one.entryName(uri, mapOf("X-Other" to "1"), emptyList()), "a header the server does not vary by changed the name")
        assertTrue(plain != reuse("two").entryName(uri, emptyMap(), emptyList()), "two namespaces share a name")
        assertTrue(plain != one.entryName("http://host/seg2.ts", emptyMap(), emptyList()))
        assertTrue(plain != one.entryName(uri, mapOf("authorization" to "Bearer a"), emptyList()), "a login did not change the name")
        assertTrue(
            one.entryName(uri, mapOf("Authorization" to "Bearer a"), emptyList()) != one.entryName(uri, mapOf("Authorization" to "Bearer b"), emptyList()),
            "two logins share a name",
        )
        assertTrue(plain != one.entryName(uri, mapOf("Cookie" to "s=1"), emptyList()))
        val varied = one.entryName(uri, mapOf("Origin" to "a"), listOf("origin"))
        assertTrue(varied != one.entryName(uri, mapOf("Origin" to "b"), listOf("origin")), "two values of a varied header share a name")
        assertEquals(varied, one.entryName(uri, mapOf("origin" to "a"), listOf("origin")))
        assertEquals(64, plain.length)
    }
}
