@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * S4.e landing four: the support bundle redacts paths, the dump echoes open options and the
 * queue, and the unimplemented tables refuse typed with the ledger sentence.
 */
class FacadeTruthTest {

    private fun player(harness: CoreHarness): KitePlayer = KitePlayer(harness.core)

    @Test
    fun `the support bundle names the platform and trims every path to its basename`() = runTest {
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(MediaItem("scripted://secret/home/folder/movie.mp4"))
        harness.run(100.milliseconds)

        val bundle = player(harness).supportBundle()
        assertTrue(bundle.startsWith("KitePlayer support bundle"), bundle.lineSequence().first())
        assertTrue("platform    jvm" in bundle, "the platform block names the runtime")
        assertTrue("movie.mp4" in bundle, "the media stays identifiable by its basename")
        assertFalse("secret/home/folder" in bundle, "the directory part must not leak")
        harness.close()
    }

    @Test
    fun `the bundle keeps no query string and no option value`() = runTest {
        // SEC-3. The redaction was `substringAfterLast('/')`, so the token rode out in the one
        // artifact a user is most likely to paste in public, and `headers` carried the rest.
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://host/folder/movie.mp4?token=SECRET&sig=ALSOSECRET",
                openOptions = mapOf("headers" to "Authorization: Bearer SECRET"),
            ),
        )
        harness.run(100.milliseconds)

        val bundle = player(harness).supportBundle()
        assertFalse("SECRET" in bundle, "no credential may survive in any line of the bundle")
        assertFalse("ALSOSECRET" in bundle, "a second query parameter must go the same way")
        assertFalse("token=" in bundle, "the query string itself must not appear")
        assertTrue("movie.mp4" in bundle, "the media stays identifiable by its basename")
        assertTrue("headers" in bundle, "which options were set is still reportable")
        harness.close()
    }

    @Test
    fun `the dump echoes open options and the queue`() = runTest {
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.openQueue(
            listOf(
                MediaItem("scripted://first", openOptions = mapOf("probesize" to "1000000")),
                MediaItem("scripted://second"),
            ),
            startIndex = 0,
        )
        harness.run(100.milliseconds)

        val dump = harness.core.diagnosticsDump()
        assertTrue("openOptions {probesize=1000000}" in dump, "the option pairs echo as configured")
        assertTrue("queue       1 of 2" in dump, "the queue and its cursor echo")
        harness.close()
    }
}
