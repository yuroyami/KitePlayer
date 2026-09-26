package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.GradleException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The libass chain download retries a transient failure instead of failing the build (#270). */
class DownloadRetriesTest {

    @Test
    fun `a timeout is retried after a growing wait`() {
        val waits = mutableListOf<Long>()
        val result = withDownloadRetries(DOWNLOAD_RETRY_WAITS_MS, sleep = { waits += it }) { attempt ->
            if (attempt < 3) throw SocketTimeoutException("connect timed out")
            "bytes"
        }
        assertEquals("bytes", result)
        assertEquals(listOf(2_000L, 4_000L), waits)
    }

    @Test
    fun `the last failure ends it with the number of attempts`() {
        var attempts = 0
        val failure = assertFailsWith<GradleException> {
            withDownloadRetries(DOWNLOAD_RETRY_WAITS_MS, sleep = {}) {
                attempts++
                throw SocketTimeoutException("connect timed out")
            }
        }
        assertEquals(4, attempts)
        assertTrue("4 attempts" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a refusal that is not transient is not retried`() {
        var attempts = 0
        assertFailsWith<GradleException> {
            withDownloadRetries(DOWNLOAD_RETRY_WAITS_MS, sleep = {}) {
                attempts++
                throw GradleException("GET answered HTTP 404")
            }
        }
        assertEquals(1, attempts)
    }
}
