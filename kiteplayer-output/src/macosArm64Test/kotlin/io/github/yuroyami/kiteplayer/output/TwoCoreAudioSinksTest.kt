@file:OptIn(io.github.yuroyami.kiteplayer.spi.RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Two live audio devices in one process (S9): both open, both start, and the shared session
 * lease counts two while they live and zero when they are gone. This is the macOS half of the
 * two-players proof; the JVM half plays two real files through two whole players.
 */
class TwoCoreAudioSinksTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun `two sinks share the session lease and release it cleanly`() = runBlocking {
        val manager = AppleAudioSessionLeaseManager(platformAppleAudioSessionController())
        val first = CoreAudioSink(AppleAudioSessionPolicy.ManagedPlayback, manager)
        val second = CoreAudioSink(AppleAudioSessionPolicy.ManagedPlayback, manager)
        try {
            first.openWithRing(format) { 4_800 }
            second.openWithRing(format) { 4_800 }
            assertEquals(2, manager.activeLeaseCount, "both open sinks must hold the shared lease")

            first.start()
            second.start()
            // Long enough for both devices to run callbacks; the ring hands them silence.
            delay(150.milliseconds)
        } finally {
            first.close()
            second.close()
        }
        assertEquals(0, manager.activeLeaseCount, "the last close must return the lease count to zero")
    }
}
