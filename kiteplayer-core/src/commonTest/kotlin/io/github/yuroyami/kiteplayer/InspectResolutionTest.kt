package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** inspect reaches the media the way open does, so an item that plays can be inspected (#202). */
class InspectResolutionTest {

    @Test
    fun inspectAsksTheConfiguredResolverAsOpenDoes() = runTest {
        var asked = 0
        val resolver = MediaIoResolver { _ -> asked++; null }
        val harness = CoreHarness(this, config = PlayerConfig(network = NetworkConfig(ioResolver = resolver)))
        val seen = harness.core.inspect(MediaItem("scripted://media"))
        assertEquals(1, asked, "the resolver must be asked once, as open asks it")
        assertTrue(seen.tracks.all.isNotEmpty())
        harness.close()
    }
}
