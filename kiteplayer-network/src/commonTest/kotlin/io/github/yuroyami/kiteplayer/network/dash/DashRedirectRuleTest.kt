package io.github.yuroyami.kiteplayer.network.dash

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DashUrlPolicy] as it judges one redirect, with each address parsed the way Ktor requests it. */
class DashRedirectRuleTest {

    private val manifest = "https://cdn.kite.test/show/movie.mpd"
    private val from = Url("https://cdn.kite.test/show/seg-1.m4s")

    private fun refused(policy: DashUrlPolicy, to: String): String =
        assertFailsWith<DashUrlRefusedException>(to) { DashRedirectRule(policy, manifest).check(from, Url(to)) }
            .message.orEmpty()

    private fun followed(policy: DashUrlPolicy, to: String) = DashRedirectRule(policy, manifest).check(from, Url(to))

    @Test
    fun sameOriginFollowsARedirectThatStaysOnTheManifestsSchemeHostAndPort() {
        followed(DashUrlPolicy.SameOrigin, "https://cdn.kite.test/other/seg-1.m4s")
        followed(DashUrlPolicy.SameOrigin, "https://CDN.Kite.TEST:443/seg-1.m4s?token=abc")
    }

    @Test
    fun sameOriginRefusesARedirectToAnotherSchemeHostOrPort() {
        for (to in listOf(
            "https://other.kite.test/seg-1.m4s",
            "https://cdn.kite.test:8443/seg-1.m4s",
            // Ktor reads the host after the at sign, so this is other.kite.test.
            "https://cdn.kite.test@other.kite.test/seg-1.m4s",
        )) {
            assertTrue("sameOriginOnly" in refused(DashUrlPolicy.SameOrigin, to), to)
        }
    }

    @Test
    fun theDefaultPolicyFollowsAnotherHostButRefusesADowngradeAndAnotherScheme() {
        followed(DashUrlPolicy.Default, "https://other.kite.test/seg-1.m4s")
        assertTrue("allowSchemeDowngrade" in refused(DashUrlPolicy.Default, "http://cdn.kite.test/seg-1.m4s"))
        assertTrue("'ftp'" in refused(DashUrlPolicy.Default, "ftp://cdn.kite.test/seg-1.m4s"))
        // A caller that asked for downgrades gets them.
        followed(DashUrlPolicy(allowSchemeDowngrade = true), "http://cdn.kite.test/seg-1.m4s")
    }

    @Test
    fun aRefusalNamesNoQuery() {
        val message = refused(DashUrlPolicy.SameOrigin, "https://other.kite.test/seg-1.m4s?token=secret")
        assertFalse("secret" in message, message)
    }

    @Test
    fun onlySameOriginRefusesARedirectThatABrowserHides() {
        assertTrue(DashRedirectRule(DashUrlPolicy.SameOrigin, manifest).refusesHiddenRedirects)
        assertFalse(DashRedirectRule(DashUrlPolicy.Default, manifest).refusesHiddenRedirects)
    }

    @Test
    fun onlyTheRulesOwnRefusalStopsAReconnect() {
        val rule = DashRedirectRule(DashUrlPolicy.SameOrigin, manifest)
        assertTrue(rule.isRefusal(DashUrlRefusedException("refused")))
        assertFalse(rule.isRefusal(IllegalStateException("dropped")))
        assertEquals(DashUrlRefusedException::class, rule.refusal("stopped")::class)
    }
}
