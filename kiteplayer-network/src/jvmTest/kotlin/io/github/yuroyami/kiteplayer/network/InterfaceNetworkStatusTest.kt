package io.github.yuroyami.kiteplayer.network

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** The JVM's network status (#461): a look at the interfaces, reported when it changes. */
class InterfaceNetworkStatusTest {

    @Test
    fun theStatusReportsTheNetworkNowAndAtEachChangeUntilItCloses() {
        val up = AtomicBoolean(true)
        val status = InterfaceNetworkStatus(interval = 10.milliseconds, online = { up.get() })
        val seen = LinkedBlockingQueue<Boolean>()
        val watch = status.watch { seen.put(it) }
        assertEquals(true, seen.poll(2, TimeUnit.SECONDS), "the network now")
        up.set(false)
        assertEquals(false, seen.poll(2, TimeUnit.SECONDS), "a network that went")
        up.set(true)
        assertEquals(true, seen.poll(2, TimeUnit.SECONDS), "and came back")
        assertNull(seen.poll(100, TimeUnit.MILLISECONDS), "nothing while nothing changes")
        watch.close()
        up.set(false)
        assertNull(seen.poll(200, TimeUnit.MILLISECONDS), "and nothing once closed")
    }

    @Test
    fun aLookThatFailsSaysThereIsANetwork() {
        val status = InterfaceNetworkStatus(interval = 10.milliseconds, online = { error("no interfaces to list") })
        val seen = LinkedBlockingQueue<Boolean>()
        status.watch { seen.put(it) }.use {
            assertEquals(true, seen.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun theTransportGivesTheJvmStatus() {
        assertIs<InterfaceNetworkStatus>(KtorMediaIoResolverProvider().networkStatus())
    }
}
