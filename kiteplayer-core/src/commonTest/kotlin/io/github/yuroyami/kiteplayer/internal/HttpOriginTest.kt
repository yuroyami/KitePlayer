package io.github.yuroyami.kiteplayer.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The origin rule that decides whether an external subtitle gets the item's request headers. */
class HttpOriginTest {

    @Test
    fun aPlainAddressGivesItsSchemeHostAndPort() {
        assertEquals(HttpOrigin("https", "cdn.kite.test", 443), httpOriginOf("https://cdn.kite.test/show/ep1.mkv"))
        assertEquals(HttpOrigin("http", "cdn.kite.test", 80), httpOriginOf("http://cdn.kite.test"))
        assertEquals(HttpOrigin("http", "cdn.kite.test", 8080), httpOriginOf("HTTP://CDN.Kite.Test:8080?a=b"))
        assertEquals(HttpOrigin("https", "[2001:db8::1]", 8443), httpOriginOf("https://[2001:DB8::1]:8443/v.mp4"))
        assertEquals(HttpOrigin("https", "a_b-c.d", 1), httpOriginOf("https://a_b-c.d:1#x"))
        assertEquals(HttpOrigin("https", "a", 65_535), httpOriginOf("https://a:65535/"))
    }

    @Test
    fun theSameServerSpelledTheSameWayMatches() {
        val item = "https://cdn.kite.test/show/ep1.mkv"
        for (subtitle in listOf(
            "https://cdn.kite.test/show/ep1.srt",
            "https://CDN.Kite.TEST/ep1.srt",
            "HTTPS://cdn.kite.test/ep1.srt",
            "https://cdn.kite.test:443/ep1.srt",
            "https://cdn.kite.test?name=ep1.srt",
            "https://cdn.kite.test#ep1.srt",
        )) {
            assertTrue(sameHttpOrigin(subtitle, item), subtitle)
        }
    }

    @Test
    fun anotherSchemeHostOrPortDoesNotMatch() {
        val item = "https://cdn.kite.test/show/ep1.mkv"
        for (subtitle in listOf(
            "https://subs.kite.test/ep1.srt",
            "https://cdn.kite.test.other.test/ep1.srt",
            "https://cdn.kite.test:8443/ep1.srt",
            "http://cdn.kite.test/ep1.srt",
            "http://cdn.kite.test:443/ep1.srt",
        )) {
            assertFalse(sameHttpOrigin(subtitle, item), subtitle)
        }
    }

    @Test
    fun anAuthorityThatParsersReadDifferentlyHasNoOrigin() {
        for (uri in listOf(
            "https://cdn.kite.test@other.test/ep1.srt",
            "https://user:pass@cdn.kite.test/ep1.srt",
            "https://other.test\\@cdn.kite.test/ep1.srt",
            "https://other.test\\.cdn.kite.test/ep1.srt",
            "https://cdn%2Ekite.test/ep1.srt",
            "https://cdn.kite.test%2F@other.test/ep1.srt",
            "https://cdn.kite.test /ep1.srt",
            "https://cdn.kite.test\t/ep1.srt",
            "https://cdn.kite.test\u0000.other.test/ep1.srt",
            // A Kelvin sign folds to a k in Unicode lower case, and nowhere here.
            "https://cdn.Kite.test/ep1.srt",
            "https://cdn.kite。test/ep1.srt",
            "https:///cdn.kite.test/ep1.srt",
            "https:cdn.kite.test/ep1.srt",
            "https:/cdn.kite.test/ep1.srt",
            " https://cdn.kite.test/ep1.srt",
            "https://",
            "https://:443/ep1.srt",
        )) {
            assertNull(httpOriginOf(uri), uri)
        }
    }

    @Test
    fun anUnusualPortHasNoOrigin() {
        for (uri in listOf(
            "https://cdn.kite.test:/ep1.srt",
            "https://cdn.kite.test:+443/ep1.srt",
            "https://cdn.kite.test:-1/ep1.srt",
            "https://cdn.kite.test:0/ep1.srt",
            "https://cdn.kite.test:65536/ep1.srt",
            "https://cdn.kite.test:000443/ep1.srt",
            "https://cdn.kite.test:44x/ep1.srt",
            "https://cdn.kite.test:443:443/ep1.srt",
        )) {
            assertNull(httpOriginOf(uri), uri)
        }
    }

    @Test
    fun anIpv6LiteralMatchesOnlyInTheSameSpelling() {
        val item = "http://[2001:db8::1]:8080/v.mp4"
        assertTrue(sameHttpOrigin("http://[2001:DB8::1]:8080/s.srt", item))
        assertFalse(sameHttpOrigin("http://[2001:db8::1]/s.srt", item))
        assertFalse(sameHttpOrigin("http://[2001:0db8::1]:8080/s.srt", item))
        for (uri in listOf(
            "http://[2001:db8::1%25en0]:8080/s.srt",
            "http://[]:8080/s.srt",
            "http://[2001:db8::1:8080/s.srt",
            "http://[2001:db8::1]]:8080/s.srt",
            "http://[2001:db8::g]:8080/s.srt",
        )) {
            assertNull(httpOriginOf(uri), uri)
        }
    }

    @Test
    fun anAddressThatIsNotHttpNeverMatches() {
        for (uri in listOf("scripted://one", "/media/ep1.mkv", "file:///media/ep1.mkv", "ftp://cdn.kite.test/a", "")) {
            assertNull(httpOriginOf(uri), uri)
            assertFalse(sameHttpOrigin(uri, uri), uri)
        }
        // A trailing dot names the same server, and still compares as another origin.
        assertFalse(sameHttpOrigin("https://cdn.kite.test./ep1.srt", "https://cdn.kite.test/ep1.mkv"))
    }
}
