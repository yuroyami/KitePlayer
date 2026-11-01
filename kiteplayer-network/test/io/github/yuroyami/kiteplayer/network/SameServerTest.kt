package io.github.yuroyami.kiteplayer.network

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which redirects let a request keep the item's headers. */
class SameServerTest {

    private fun same(from: String, to: String) = sameServer(Url(from), Url(to))

    @Test
    fun oneSchemeHostAndPortAreOneServer() {
        assertTrue(same("https://cdn.kite.test/a", "https://cdn.kite.test/b?c=d"))
        assertTrue(same("https://cdn.kite.test:8443/a", "https://CDN.kite.test:8443/b"))
        assertTrue(same("https://cdn.kite.test/a", "https://cdn.kite.test:443/b"))
    }

    @Test
    fun anotherHostOrPortIsAnotherServer() {
        assertFalse(same("https://cdn.kite.test/a", "https://other.kite.test/a"))
        assertFalse(same("https://cdn.kite.test/a", "https://evil.test/cdn.kite.test/a"))
        assertFalse(same("https://kite.test/a", "https://www.kite.test/a"))
        assertFalse(same("https://cdn.kite.test/a", "https://cdn.kite.test:8443/a"))
    }

    /** The most common redirect there is, and the headers still reach the same host, now encrypted. */
    @Test
    fun anUpgradeToHttpsOnTheDefaultPortsIsOneServer() {
        assertTrue(same("http://cdn.kite.test/a", "https://cdn.kite.test/a"))
        assertTrue(same("http://cdn.kite.test:80/a", "https://CDN.kite.test:443/b"))
    }

    /** A dotless i equals an i when case is ignored, but it names another domain. */
    @Test
    fun anUpgradeToAHostThatOnlyFoldsOntoTheSameLettersIsAnotherServer() {
        assertFalse(same("http://kite.test/a", "https://kıte.test/a"))
    }

    @Test
    fun anythingElseThatChangesTheSchemeIsAnotherServer() {
        assertFalse(same("https://cdn.kite.test/a", "http://cdn.kite.test/a"), "a downgrade sends the headers in clear")
        assertFalse(same("http://cdn.kite.test:8080/a", "https://cdn.kite.test/a"))
        assertFalse(same("http://cdn.kite.test/a", "https://cdn.kite.test:8443/a"))
        assertFalse(same("http://cdn.kite.test/a", "https://other.kite.test/a"))
    }
}
