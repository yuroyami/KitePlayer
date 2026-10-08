package io.github.yuroyami.kiteplayer.network.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An MPD is attacker-supplied input, and the player once fetched whatever it named with the
 * CALLER'S HttpClient, cookie jar included.
 *
 * The old rule was one line: `reference.contains("://") -> reference`. Anything with those three
 * characters anywhere in it was accepted as an absolute URL and fetched. These rows are the shapes
 * that mattered.
 */
class DashUrlPolicyTest {

    private val manifest = "http://cdn.test/vod/movie.mpd"
    private val secureManifest = "https://cdn.test/vod/movie.mpd"

    private fun resolve(base: String, reference: String, policy: DashUrlPolicy = DashUrlPolicy.Default) =
        DashManifestParser.resolveUrl(base, reference, policy)

    @Test
    fun `a manifest cannot make the player read the local disk`() {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            resolve(manifest, "file:///etc/passwd")
        }
        assertTrue("file" in refusal.message!!, refusal.message!!)
        // Single-slash form: this one used to be treated as a RELATIVE path, so it was not even
        // recognised as a scheme, and it joined the manifest directory instead of being refused.
        assertFailsWith<DashUrlRefusedException> { resolve(manifest, "file:/etc/passwd") }
    }

    @Test
    fun `no scheme outside the allowlist is fetched`() {
        assertFailsWith<DashUrlRefusedException> { resolve(manifest, "data:text/plain,hello") }
        assertFailsWith<DashUrlRefusedException> { resolve(manifest, "jar:http://host/a.jar!/b") }
        assertFailsWith<DashUrlRefusedException> { resolve(manifest, "ftp://host/a.ts") }
        assertFailsWith<DashUrlRefusedException> { resolve(manifest, "gopher://host/1") }
    }

    @Test
    fun `an https manifest cannot be talked down to http`() {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            resolve(secureManifest, "http://cdn.test/vod/seg-1.ts")
        }
        assertTrue("allowSchemeDowngrade" in refusal.message!!, refusal.message!!)
        assertEquals(
            "http://cdn.test/vod/seg-1.ts",
            resolve(
                secureManifest,
                "http://cdn.test/vod/seg-1.ts",
                DashUrlPolicy(allowSchemeDowngrade = true),
            ),
        )
    }

    @Test
    fun `a different CDN host is ordinary DASH and still resolves`() {
        // Deliberate: BaseURL pointing at another host is correct, common DASH. Refusing it by
        // default would break real manifests, so the default policy allows it.
        assertEquals(
            "http://mirror.test/content/a.mp4",
            resolve(manifest, "http://mirror.test/content/a.mp4"),
        )
    }

    @Test
    fun `SameOrigin refuses the host swap that carries the cookies away`() {
        val refusal = assertFailsWith<DashUrlRefusedException> {
            resolve(manifest, "http://evil.test/collect", DashUrlPolicy.SameOrigin)
        }
        assertTrue("sameOriginOnly" in refusal.message!!, refusal.message!!)
        // Same host, same scheme, same port: allowed even under the strict policy.
        assertEquals(
            "http://cdn.test/vod/seg-1.ts",
            resolve(manifest, "seg-1.ts", DashUrlPolicy.SameOrigin),
        )
        // A port change is an origin change.
        assertFailsWith<DashUrlRefusedException> {
            resolve(manifest, "http://cdn.test:8080/seg-1.ts", DashUrlPolicy.SameOrigin)
        }
    }

    @Test
    fun `a scheme-relative reference inherits the manifest's scheme and is then judged`() {
        assertEquals("http://other.test/a.ts", resolve(manifest, "//other.test/a.ts"))
        assertFailsWith<DashUrlRefusedException> {
            resolve(manifest, "//other.test/a.ts", DashUrlPolicy.SameOrigin)
        }
    }

    @Test
    fun `ordinary relative resolution is untouched`() {
        assertEquals("http://cdn.test/vod/seg-1.ts", resolve(manifest, "seg-1.ts"))
        assertEquals("http://cdn.test/other/seg-1.ts", resolve(manifest, "/other/seg-1.ts"))
        assertEquals("http://cdn.test/vod/sub/seg-1.ts", resolve(manifest, "sub/seg-1.ts"))
    }

    @Test
    fun `the manifest URL itself is judged before anything is fetched`() {
        assertFailsWith<DashUrlRefusedException> {
            DashManifestParser.requireAllowedScheme("file:///etc/passwd", DashUrlPolicy.Default)
        }
        DashManifestParser.requireAllowedScheme(manifest, DashUrlPolicy.Default)
    }

    @Test
    fun `a hostile BaseURL is refused while the manifest is being parsed`() {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">
                <BaseURL>file:///etc/</BaseURL>
                <Period duration="PT4S">
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
        assertFailsWith<DashUrlRefusedException> { DashManifestParser.parse(mpd, manifest) }
    }

    // RFC 3986, section 5.4.1, against its own base URI.
    @Test
    fun `the RFC 3986 examples resolve as the standard says`() {
        val base = "http://a/b/c/d;p?q"
        val expected = listOf(
            "g" to "http://a/b/c/g",
            "./g" to "http://a/b/c/g",
            "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g",
            "//g" to "http://g",
            "?y" to "http://a/b/c/d;p?y",
            "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s",
            "g#s" to "http://a/b/c/g#s",
            "" to "http://a/b/c/d;p?q",
            "." to "http://a/b/c/",
            ".." to "http://a/b/",
            "../g" to "http://a/b/g",
            "../.." to "http://a/",
            "../../g" to "http://a/g",
            "../../../g" to "http://a/g",
            "g/./h/../i" to "http://a/b/c/g/i",
        )
        for ((reference, resolved) in expected) assertEquals(resolved, resolve(base, reference), "reference '$reference'")
    }

    @Test
    fun `a slash in the manifest query or fragment is not a directory`() {
        val signed = "https://cdn.test/vod/movie.mpd?token=a/b"
        assertEquals("https://cdn.test/vod/s1.m4s", resolve(signed, "s1.m4s"))
        assertEquals("https://cdn.test/vod/s1.m4s?sig=c/d", resolve(signed, "s1.m4s?sig=c/d"))
        assertEquals("https://cdn.test/vod/movie.mpd?x=1", resolve(signed, "?x=1"))
        assertEquals("https://cdn.test/vod/movie.mpd?token=a/b#t", resolve(signed, "#t"))
        assertEquals("https://cdn.test/vod/s1.m4s", resolve("https://cdn.test/vod/movie.mpd#part/two", "s1.m4s"))
    }

    @Test
    fun `a manifest fetched with a slash in its query plans segments beside it`() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video">
                        <Representation id="v" bandwidth="1">
                            <SegmentList>
                                <SegmentURL media="s1.m4s"/>
                            </SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashManifestParser.parse(mpd, "https://cdn.test/vod/movie.mpd?token=a/b")
        val period = manifest.periods.single()
        val plan = DashManifestParser.segmentPlan(manifest, period, period.adaptationSets.single().representations.single())
        assertEquals(listOf("https://cdn.test/vod/s1.m4s"), plan.mediaUrls)
    }
}
