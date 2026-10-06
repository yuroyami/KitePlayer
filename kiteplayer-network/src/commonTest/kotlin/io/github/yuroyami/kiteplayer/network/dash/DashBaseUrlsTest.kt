package io.github.yuroyami.kiteplayer.network.dash

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A manifest's alternative locations (#440): the parser keeps every `BaseURL` of a level, each
 * resolved against every location above it, in DVB's order, and [DashFailover] moves a failing
 * address to the next one and stays there.
 */
class DashBaseUrlsTest {

    private fun mpd(top: String, representation: String = "") = """
        <MPD type="static" mediaPresentationDuration="PT2S" xmlns:dvb="urn:dvb:dash:dash-extensions:2014-1">
            $top
            <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <Representation id="v" bandwidth="1">
                        $representation
                        <SegmentList><SegmentURL media="a.m4s"/></SegmentList>
                    </Representation>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun everyBaseUrlOfALevelIsKeptInTheManifestsOrder() {
        val manifest = DashManifestParser.parse(
            mpd(
                """
                <BaseURL serviceLocation="a" dvb:priority="2">https://one.test/v/</BaseURL>
                <BaseURL serviceLocation="b" dvb:priority="1" dvb:weight="1">https://two.test/v/</BaseURL>
                <BaseURL serviceLocation="c" dvb:priority="1" dvb:weight="3">https://three.test/v/</BaseURL>
                """,
            ),
            "https://origin.test/movie.mpd",
        )
        val urls = manifest.alternativeBaseUrls.single()
        assertEquals("https://three.test/v/", urls.primary)
        assertEquals(listOf("https://three.test/v/", "https://two.test/v/", "https://one.test/v/"), urls.locations.map { it.url })
        assertEquals(listOf("c", "b", "a"), urls.locations.map { it.serviceLocation })
        assertEquals(listOf(1, 1, 2), urls.locations.map { it.priority })
        assertEquals(listOf(3, 1, null), urls.locations.map { it.weight })
        // The addresses below are built from the first.
        assertEquals("https://three.test/v/a.m4s", manifest.periods[0].adaptationSets[0].representations[0].segmentUrls.single())
    }

    @Test
    fun aRelativeBaseUrlBelowSeveralResolvesAgainstEachOfThem() {
        val manifest = DashManifestParser.parse(
            mpd("<BaseURL>https://one.test/</BaseURL><BaseURL>https://two.test/</BaseURL>", "<BaseURL>hd/</BaseURL>"),
            "https://origin.test/movie.mpd",
        )
        val representation = manifest.alternativeBaseUrls.first { it.primary == "https://one.test/hd/" }
        assertEquals(listOf("https://one.test/hd/", "https://two.test/hd/"), representation.locations.map { it.url })
        assertEquals("https://one.test/hd/a.m4s", manifest.periods[0].adaptationSets[0].representations[0].segmentUrls.single())
    }

    @Test
    fun oneBaseUrlNamesNoAlternative() {
        val manifest = DashManifestParser.parse(mpd("<BaseURL>https://one.test/</BaseURL>"), "https://origin.test/movie.mpd")
        assertEquals(emptyList(), manifest.alternativeBaseUrls)
    }

    @Test
    fun aLocationThePolicyRefusesIsLeftOutAndOnlyAllRefusedFails() {
        val manifest = DashManifestParser.parse(
            mpd("<BaseURL>ftp://one.test/</BaseURL><BaseURL>https://two.test/</BaseURL>"),
            "https://origin.test/movie.mpd",
        )
        assertEquals(emptyList(), manifest.alternativeBaseUrls)
        assertEquals("https://two.test/", manifest.baseUrl)
        assertFailsWith<DashUrlRefusedException> {
            DashManifestParser.parse(mpd("<BaseURL>ftp://one.test/</BaseURL>"), "https://origin.test/movie.mpd")
        }
    }

    private val set = DashBaseUrls(
        "https://one.test/v/",
        listOf(
            DashBaseUrl("https://one.test/v/", serviceLocation = "a"),
            DashBaseUrl("https://one-mirror.test/v/", serviceLocation = "a"),
            DashBaseUrl("https://two.test/v/", serviceLocation = "b"),
        ),
    )

    @Test
    fun aFailingLocationMovesToTheNextNetworkAndStaysThere() = runTest {
        val failover = DashFailover(listOf(set), Random(1))
        val asked = ArrayList<String>()
        suspend fun read(url: String) = failover.open(url) { target ->
            asked += target
            if (!target.startsWith("https://two.test/")) throw IllegalStateException("503 from $target")
            target
        }
        assertEquals("https://two.test/v/a.m4s?t=1", read("https://one.test/v/a.m4s?t=1"))
        // The mirror on the network that failed is left for last, so it is never asked.
        assertEquals(listOf("https://one.test/v/a.m4s?t=1", "https://two.test/v/a.m4s?t=1"), asked)
        asked.clear()
        assertEquals("https://two.test/v/b.m4s", read("https://one.test/v/b.m4s"))
        assertEquals(listOf("https://two.test/v/b.m4s"), asked, "the next segment went back to the failed location")
        // An address under no such base opens as it is.
        asked.clear()
        failover.open("https://elsewhere.test/x") { asked += it }
        assertEquals(listOf("https://elsewhere.test/x"), asked)
    }

    @Test
    fun whenEveryLocationFailsTheFirstFailureIsThrown() = runTest {
        val failover = DashFailover(listOf(set), Random(1))
        val failure = assertFailsWith<IllegalStateException> {
            failover.open<String>("https://one.test/v/a.m4s") { target -> throw IllegalStateException("503 from $target") }
        }
        assertTrue("one.test" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun theFirstLocationIsDrawnByWeightAmongTheLowestPriority() = runTest {
        val weighted = DashBaseUrls(
            "https://one.test/",
            listOf(
                DashBaseUrl("https://one.test/", weight = 1),
                DashBaseUrl("https://two.test/", weight = 3),
                DashBaseUrl("https://backup.test/", priority = 2, weight = 100),
            ),
        )
        val firsts = HashMap<String, Int>()
        val random = Random(7)
        repeat(400) {
            val failover = DashFailover(listOf(weighted), random)
            failover.open("https://one.test/a") { target -> firsts[target.substringBefore("/a")] = (firsts[target.substringBefore("/a")] ?: 0) + 1 }
        }
        assertEquals(null, firsts["https://backup.test"], "a location of a higher priority was drawn first")
        val share = (firsts["https://two.test"] ?: 0) / 400.0
        assertTrue(share in 0.65..0.85, "the location of weight 3 was drawn first ${share * 100} percent of the time")
    }

    @Test
    fun anAddressMovesByItsPathBelowTheBase() {
        assertEquals("https://b.test/x/seg.m4s", DashFailover.moved("https://a.test/v/seg.m4s", "https://a.test/v/", "https://b.test/x/"))
        assertEquals("https://b.test/x/seg.m4s", DashFailover.moved("https://a.test/v/seg.m4s", "https://a.test/v/index?k=1", "https://b.test/x/"))
        assertEquals("https://b.test/file.mp4", DashFailover.moved("https://a.test/file.mp4", "https://a.test/file.mp4", "https://b.test/file.mp4"))
        assertEquals("https://b.test/seg", DashFailover.moved("https://a.test/seg", "https://a.test", "https://b.test"))
        assertEquals(null, DashFailover.moved("https://c.test/v/seg.m4s", "https://a.test/v/", "https://b.test/x/"))
    }
}
