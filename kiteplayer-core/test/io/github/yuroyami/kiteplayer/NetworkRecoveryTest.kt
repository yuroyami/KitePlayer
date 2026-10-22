package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Waiting for a lost network and opening the item again (#461), with a scripted source whose reads
 * and opens fail while a fake network is down. See `docs/cancellation-and-bounded-waits.md`.
 */
class NetworkRecoveryTest {

    private val address = "https://cdn.test/film.mkv"

    /** A network the test switches, which the player watches through [NetworkStatus]. */
    private class FakeNetwork : NetworkStatus {
        private val watchers = mutableListOf<(Boolean) -> Unit>()
        var online = true
            private set
        val watching: Int get() = watchers.size

        override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
            watchers += onChange
            onChange(online)
            return AutoCloseable { watchers -= onChange }
        }

        fun set(value: Boolean) {
            online = value
            watchers.toList().forEach { it(value) }
        }
    }

    private fun config(network: NetworkStatus?, maxWait: Duration = 5.minutes, stallTimeout: Duration = 30.seconds, recovering: Boolean = true) =
        PlayerConfig(
            network = NetworkConfig(autoResolve = false, recovery = if (recovering) NetworkRecovery(maxWait, network) else null),
            buffer = BufferPolicy(stallTimeout = stallTimeout),
        )

    /** The network goes: every read fails, every open fails, and the status says so. */
    private fun CoreHarness.goOffline(faults: FaultPlan, network: FakeNetwork?) {
        faults.readsFail = true
        backend.openFailureFor = { IllegalStateException("no route to host") }
        network?.set(false)
    }

    private fun CoreHarness.goOnline(faults: FaultPlan, network: FakeNetwork?) {
        faults.readsFail = false
        backend.openFailureFor = null
        network?.set(true)
    }

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(10.milliseconds)
            waited += 10.milliseconds
        }
        return true
    }

    private fun CoreHarness.snapshot() = core.snapshots.value

    private fun CoreHarness.reconnectWarnings() =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.Reconnecting>()

    /** Plays [address] for five seconds, then takes the network away and waits for the wait to start. */
    private suspend fun CoreHarness.playThenLoseTheNetwork(faults: FaultPlan, network: FakeNetwork?, uri: String = address) {
        openWithRenderer(uri)
        core.play()
        run(5.seconds)
        goOffline(faults, network)
        assertTrue(runUntil(5.seconds) { snapshot().reconnecting || snapshot().status == PlaybackStatus.Failed }, "the read did not fail")
    }

    @Test
    fun aReadTheNetworkFailsWaitsAndOpensAgainAtItsPositionWhenTheNetworkIsBack() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network))
        harness.playThenLoseTheNetwork(faults, network)
        val lostAt = harness.core.position()
        val waiting = harness.snapshot()
        assertTrue(waiting.reconnecting)
        assertEquals(PlaybackStatus.Buffering, waiting.status)
        assertEquals(null, waiting.error, "the player does not fail")
        assertEquals(60.seconds, waiting.duration, "the item keeps its length")
        assertEquals(address, waiting.media?.uri)
        assertIs<PlaybackError.SourceUnavailable>(harness.reconnectWarnings().single().error)
        assertEquals(1, network.watching, "the status is watched while the player waits")
        val opened = harness.backend.sessions.size
        // Twenty seconds without a network: the timer tries at 1, 3, 7 and 15 seconds and fails.
        harness.run(20.seconds)
        assertTrue(harness.snapshot().reconnecting)
        assertEquals(opened, harness.backend.sessions.size, "no attempt opened anything")
        assertEquals(lostAt, harness.core.position(), "the position stays")
        // The next attempt is five seconds away, so only the status can bring it this soon.
        harness.goOnline(faults, network)
        harness.run(300.milliseconds)
        assertEquals(opened + 1, harness.backend.sessions.size, "the item opened again when the network came back")
        assertTrue(harness.runUntil(5.seconds) { harness.snapshot().status == PlaybackStatus.Playing }, "status ${harness.snapshot().status}")
        assertFalse(harness.snapshot().reconnecting)
        assertEquals(lostAt.inWholeMicroseconds, harness.source.seekTargets.last(), "at the position it reached")
        assertEquals(0, network.watching, "and the status is let go")
        harness.run(1.seconds)
        assertTrue(harness.core.position() > lostAt, "and plays on")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun withRecoveryOffTheItemFailsAtOnce() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(null, recovering = false))
        harness.playThenLoseTheNetwork(faults, null)
        assertEquals(PlaybackStatus.Failed, harness.snapshot().status)
        assertFalse(harness.snapshot().reconnecting)
        assertIs<PlaybackError.SourceUnavailable>(harness.snapshot().error)
        assertEquals(emptyList(), harness.reconnectWarnings())
        harness.close()
    }

    @Test
    fun withoutAStatusTheTimerTriesAgain() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(null))
        harness.playThenLoseTheNetwork(faults, null)
        val opened = harness.backend.sessions.size
        // Back after 3.5 seconds: the attempts at 1 and 3 seconds failed, and the next is at 7.
        harness.run(3_500.milliseconds)
        harness.goOnline(faults, null)
        harness.run(3_300.milliseconds)
        assertTrue(harness.snapshot().reconnecting, "nothing told the player before its timer")
        assertEquals(opened, harness.backend.sessions.size)
        harness.run(500.milliseconds)
        assertEquals(opened + 1, harness.backend.sessions.size, "the timer's attempt opened it")
        assertFalse(harness.snapshot().reconnecting)
        harness.close()
    }

    @Test
    fun aLiveItemComesBackAtTheLiveEdge() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 600_000_000, live = true, seekable = false),
            faults = faults,
            config = config(network),
        )
        harness.playThenLoseTheNetwork(faults, network, "rtsp://camera.test/stream")
        assertTrue(harness.snapshot().reconnecting)
        assertFalse(harness.snapshot().seekable, "a live stream has no position to come back to")
        assertTrue(runCatching { KitePlayer(harness.core).seek(1.seconds, SeekMode.Precise) }.exceptionOrNull() is IllegalStateException, "so a seek is refused")
        harness.run(10.seconds)
        harness.goOnline(faults, network)
        assertTrue(harness.runUntil(5.seconds) { harness.snapshot().status == PlaybackStatus.Playing }, "status ${harness.snapshot().status}")
        val source = harness.source
        assertEquals(emptyList(), source.seekTargets, "nothing seeks a live stream back")
        val behind = (harness.clock.nanos() - source.liveOriginNanos) / 1_000 - harness.core.position().inWholeMicroseconds
        assertTrue(behind < 1_000_000, "it plays at the live edge, ${behind / 1_000} ms behind the sender")
        harness.close()
    }

    @Test
    fun aDropWhileTheNetworkStaysUpIsTriedOnTheTimer() = runTest {
        // A camera that restarts: its stream stops, and the device keeps its network.
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network))
        harness.playThenLoseTheNetwork(faults, null, "rtsp://camera.test/stream")
        assertTrue(harness.snapshot().reconnecting)
        val opens = harness.backend.openCalls
        harness.run(800.milliseconds)
        assertEquals(opens, harness.backend.openCalls, "a network that never went is no reason to try at once")
        harness.run(400.milliseconds)
        assertEquals(opens + 1, harness.backend.openCalls, "the first attempt is a second after the failure")
        harness.close()
    }

    @Test
    fun theWaitGivesUpWithTheErrorThatStartedItAtItsLimit() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network, maxWait = 30.seconds))
        harness.playThenLoseTheNetwork(faults, network)
        val started = harness.reconnectWarnings().single().error
        harness.run(29.seconds)
        assertTrue(harness.snapshot().reconnecting, "still waiting before the limit")
        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Failed, harness.snapshot().status)
        assertFalse(harness.snapshot().reconnecting)
        assertEquals(started, harness.snapshot().error, "with the error that started the wait")
        assertEquals(0, network.watching)
        harness.close()
    }

    @Test
    fun aLocalFileFailsAtOnce() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(FakeNetwork()))
        harness.playThenLoseTheNetwork(faults, null, "file:///music/film.mkv")
        assertEquals(PlaybackStatus.Failed, harness.snapshot().status)
        assertEquals(emptyList(), harness.reconnectWarnings())
        harness.close()
    }

    @Test
    fun anAttemptThatFailsForAnotherReasonFailsWithThatError() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network))
        harness.playThenLoseTheNetwork(faults, network)
        harness.goOnline(faults, network)
        // The address now answers with a page that is not media.
        harness.backend.openFailureFor = { PlaybackException(PlaybackError.NotMedia(address, "an HTML page")) }
        harness.run(500.milliseconds)
        assertEquals(PlaybackStatus.Failed, harness.snapshot().status)
        assertIs<PlaybackError.NotMedia>(harness.snapshot().error)
        assertFalse(harness.snapshot().reconnecting)
        harness.close()
    }

    @Test
    fun aSeekDuringTheWaitMovesWhereTheItemOpensAgain() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network))
        harness.playThenLoseTheNetwork(faults, network)
        KitePlayer(harness.core).seek(40.seconds, SeekMode.Precise)
        assertEquals(40.seconds, harness.core.position(), "the position reads the seek at once")
        harness.run(1.seconds)
        assertEquals(40.seconds, harness.core.position())
        harness.goOnline(faults, network)
        assertTrue(harness.runUntil(5.seconds) { harness.snapshot().status == PlaybackStatus.Playing })
        assertEquals(40_000_000L, harness.source.seekTargets.last())
        assertTrue(harness.core.position() >= 40.seconds, "at ${harness.core.position()}")
        harness.close()
    }

    @Test
    fun stopEndsTheWait() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network))
        harness.playThenLoseTheNetwork(faults, network)
        harness.core.stop()
        assertEquals(PlaybackStatus.Idle, harness.snapshot().status)
        assertFalse(harness.snapshot().reconnecting)
        assertEquals(0, network.watching)
        val opened = harness.backend.sessions.size
        harness.goOnline(faults, network)
        harness.run(20.seconds)
        assertEquals(opened, harness.backend.sessions.size, "nothing opens after the stop")
        harness.close()
    }

    @Test
    fun aPauseDuringTheWaitHoldsWhenTheItemIsBack() = runTest {
        val faults = FaultPlan()
        val network = FakeNetwork()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults, config = config(network))
        harness.playThenLoseTheNetwork(faults, network)
        harness.core.pause()
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Paused, harness.snapshot().status)
        assertTrue(harness.snapshot().reconnecting)
        harness.core.play()
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Buffering, harness.snapshot().status, "play waits for the item as buffering")
        harness.core.pause()
        harness.run(100.milliseconds)
        val opened = harness.backend.sessions.size
        harness.goOnline(faults, network)
        assertTrue(harness.runUntil(5.seconds) { harness.backend.sessions.size > opened && !harness.snapshot().reconnecting })
        harness.run(1.seconds)
        assertEquals(PlaybackStatus.Paused, harness.snapshot().status)
        assertFalse(harness.snapshot().playRequested)
        harness.close()
    }

    @Test
    fun aStallOverTheNetworkWaitsToo() = runTest {
        val faults = FaultPlan().apply { readWedgesAfter = 200 }
        val network = FakeNetwork()
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 600_000_000),
            faults = faults,
            config = config(network, stallTimeout = 5.seconds),
        )
        harness.openWithRenderer(address)
        harness.core.play()
        assertTrue(harness.runUntil(60.seconds) { harness.snapshot().reconnecting }, "status ${harness.snapshot().status}, ${harness.snapshot().error}")
        assertIs<PlaybackError.SourceStalled>(harness.reconnectWarnings().single().error)
        // The server answers again: reads no longer wedge, and the timer's attempt opens the item.
        faults.readWedgesAfter = null
        assertTrue(harness.runUntil(5.seconds) { harness.snapshot().status == PlaybackStatus.Playing }, "status ${harness.snapshot().status}")
        harness.close()
    }
}
