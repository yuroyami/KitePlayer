package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A signed address that expires during playback (#453). When the item's reader reports that a
 * server refused an address with 403, the engine opens the item again through its `io` factory, at
 * the position it reached, and says so. A refusal right after a renewal is not renewed again, so a
 * factory that hands out the dead address ends as before; one after playback moved on is.
 */
class AddressRenewalTest {

    private class Reader : MediaIo {
        override val size: Long = 4096
        override val seekable: Boolean = true
        var refusal: SourceRefusal? = null

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1

        override suspend fun seek(position: Long) = Unit

        override fun takeRefusal(): SourceRefusal? = refusal.also { refusal = null }

        override fun close() {}
    }

    private val made = ArrayList<Reader>()

    private fun CoreHarness.renewals() =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.AddressRenewed>()

    private suspend fun CoreHarness.start() {
        openThroughIo { Reader().also { made += it } }
        core.play()
        run(3.seconds)
    }

    @Test
    fun aRefusedAddressOpensTheItemAgainAtItsPosition() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        harness.start()
        val before = harness.core.position()
        made.last().refusal = SourceRefusal("https://cdn.test/seg-3.ts?token=old", 403)
        harness.run(1.seconds)
        assertEquals(2, made.size, "the item was not opened again for a fresh address")
        // Named by its file alone, so the token of the signed address never shows.
        assertEquals(listOf(PlaybackWarning.AddressRenewed("seg-3.ts", 403)), harness.renewals().toList())
        harness.run(1.seconds)
        val after = harness.core.position()
        assertTrue(after >= before && after - before < 2.5.seconds, "the renewal went from $before to $after")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aRefusalRightAfterARenewalIsNotRenewedAgainButOneLaterIs() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        harness.start()
        made.last().refusal = SourceRefusal("https://cdn.test/seg-3.ts", 403)
        harness.run(500.milliseconds)
        assertEquals(2, made.size)
        // The fresh address is refused at once: a resolver that keeps handing out the dead one.
        made.last().refusal = SourceRefusal("https://cdn.test/seg-3.ts", 403)
        harness.run(500.milliseconds)
        assertEquals(2, made.size, "the item was opened again for a refusal right after its renewal")
        assertEquals(1, harness.renewals().size)
        // Once playback has moved on, a new refusal is a new expiry.
        harness.run(3.seconds)
        made.last().refusal = SourceRefusal("https://cdn.test/seg-9.ts", 401)
        harness.run(500.milliseconds)
        assertEquals(3, made.size, "a later expiry was not renewed")
        assertEquals(listOf(403, 401), harness.renewals().map { it.status })
        harness.close()
    }
}
