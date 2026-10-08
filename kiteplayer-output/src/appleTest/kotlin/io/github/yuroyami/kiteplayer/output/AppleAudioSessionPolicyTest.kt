package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioContent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleAudioSessionPolicyTest {

    @Test
    fun `managed leases activate once and only the final close deactivates`() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val first = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        val second = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)

        assertEquals(listOf("category:playback:moviePlayback:mixWithOthers", "active:true:none"), controller.calls)
        assertEquals(2, manager.activeLeaseCount)

        first.close()
        first.close()
        assertEquals(listOf("category:playback:moviePlayback:mixWithOthers", "active:true:none"), controller.calls)
        assertEquals(1, manager.activeLeaseCount)

        second.close()
        second.close()
        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
        assertEquals(0, manager.activeLeaseCount)
    }

    @Test
    fun aManagedLeaseActivatesTheSessionAgainOnResume() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)
        val lease = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)

        lease.reactivate()
        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "category:playback:moviePlayback:none",
                "active:true:none",
            ),
            controller.calls,
        )

        lease.close()
        lease.reactivate()
        assertEquals("active:false:notifyOthers", controller.calls.last(), "a closed lease activates nothing")
    }

    @Test
    fun aPausedOpenMixesWithOtherAppsAndTheFirstPlayStopsThem() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val first = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie)
        assertEquals(
            listOf("category:playback:moviePlayback:mixWithOthers", "active:true:none"),
            controller.calls,
            "an open that has not played leaves other apps' sound playing",
        )

        first.reactivate()
        first.reactivate()
        val second = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Music)
        first.close()
        second.close()
        val again = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie)
        again.close()

        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "category:playback:moviePlayback:none",
                "active:true:none",
                "active:true:none",
                "category:playback:default:none",
                "active:false:notifyOthers",
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

    @Test
    fun aRefusedStopOfMixingIsTriedAgainAtTheNextPlay() {
        var refusals = 1
        val categories = mutableListOf<Boolean>()
        val controller = object : AppleAudioSessionController {
            override fun setPlaybackCategory(content: AudioContent, mixesWithOthers: Boolean) {
                categories += mixesWithOthers
                if (!mixesWithOthers && refusals-- > 0) throw PlannedAppleAudioSessionFailure()
            }
            override fun setActive(active: Boolean, notifyOthers: Boolean) = Unit
        }
        val lease = AppleAudioSessionLeaseManager(controller).acquire(AppleAudioSessionPolicy.ManagedPlayback)
        lease.reactivate()
        lease.reactivate()
        lease.reactivate()
        assertEquals(listOf(true, false, false), categories)
        lease.close()
    }

    @Test
    fun theSessionModeFollowsTheContentOfTheItemThatOpenedLast() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val speech = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Speech)
        val alsoSpeech = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Speech)
        val music = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Music)
        listOf(speech, alsoSpeech, music).forEach { it.close() }
        manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Music).close()

        assertEquals(
            listOf(
                "category:playback:spokenAudio:mixWithOthers",
                "active:true:none",
                "category:playback:default:mixWithOthers",
                "active:false:notifyOthers",
                "category:playback:default:mixWithOthers",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

    @Test
    fun aSurroundItemDeclaresItsChannelsBeforeTheActivationAndAsksTheRouteAfterIt() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 6).close()

        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "multichannel:true",
                "active:true:none",
                "channels:6",
                "channels:2",
                "multichannel:false",
                "active:false:notifyOthers",
            ),
            controller.calls,
            "the last close hands the route back as it was found",
        )
    }

    @Test
    fun aStereoItemAsksTheSessionForNothingAboutChannels() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 2).close()
        manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 1).close()

        assertEquals(emptyList(), controller.calls.filter { it.startsWith("multichannel") || it.startsWith("channels") })
    }

    @Test
    fun theChannelsFollowTheItemThatOpenedLast() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val stereo = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 2)
        val surround = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 8)
        val alsoSurround = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 6)
        val stereoAgain = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 2)
        listOf(stereo, surround, alsoSurround, stereoAgain).forEach { it.close() }

        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "multichannel:true",
                "channels:8",
                "channels:6",
                "multichannel:false",
                "channels:2",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

    @Test
    fun aSessionThatRefusesTheChannelsStillOpens() {
        val controller = RecordingAppleAudioSessionController(refuseChannels = true)
        val manager = AppleAudioSessionLeaseManager(controller)

        val lease = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback, AudioContent.Movie, channels = 6)
        assertEquals(1, manager.activeLeaseCount, "the output unit bounds the channels by what the route gives")
        lease.close()

        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "multichannel:true",
                "active:true:none",
                "channels:6",
                "active:false:notifyOthers",
            ),
            controller.calls,
            "nothing that was refused is taken back",
        )
    }

    @Test
    fun anApplicationManagedLeaseDeclaresNoChannels() {
        val controller = RecordingAppleAudioSessionController()
        AppleAudioSessionLeaseManager(controller)
            .acquire(AppleAudioSessionPolicy.ApplicationManaged, AudioContent.Movie, channels = 8)
            .close()
        assertEquals(emptyList(), controller.calls)
    }

    @Test
    fun aRefusedReactivationIsLeftForTheDeviceStartToReport() {
        // The first activation, at acquire, succeeds; the second, at resume, is refused.
        var activations = 0
        val controller = object : AppleAudioSessionController {
            override fun setPlaybackCategory(content: AudioContent, mixesWithOthers: Boolean) = Unit
            override fun setActive(active: Boolean, notifyOthers: Boolean) {
                if (active && ++activations == 2) throw PlannedAppleAudioSessionFailure()
            }
        }
        val manager = AppleAudioSessionLeaseManager(controller)
        val lease = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        lease.reactivate()
        assertEquals(2, activations)
        assertEquals(1, manager.activeLeaseCount, "the lease stays held after a refused reactivation")
    }

    @Test
    fun anApplicationManagedLeaseLeavesTheSessionToTheApplication() {
        val controller = RecordingAppleAudioSessionController()
        val lease = AppleAudioSessionLeaseManager(controller).acquire(AppleAudioSessionPolicy.ApplicationManaged)
        lease.reactivate()
        assertEquals(emptyList(), controller.calls)
    }

    @Test
    fun `activation failure rolls back the lease count and the next acquire retries`() {
        val controller = RecordingAppleAudioSessionController(failActivationCount = 1)
        val manager = AppleAudioSessionLeaseManager(controller)

        assertFailsWith<PlannedAppleAudioSessionFailure> {
            manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        }
        assertEquals(0, manager.activeLeaseCount)

        val retry = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        assertEquals(1, manager.activeLeaseCount)
        retry.close()
        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

    @Test
    fun `application managed policy makes no session call`() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val first = manager.acquire(AppleAudioSessionPolicy.ApplicationManaged)
        val second = manager.acquire(AppleAudioSessionPolicy.ApplicationManaged)
        first.close()
        second.close()

        assertEquals(emptyList(), controller.calls)
        assertEquals(0, manager.activeLeaseCount)
    }

    @Test
    fun `concurrent managed acquires still form one process lease`() = runBlocking {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val leases = List(64) {
            async(Dispatchers.Default) {
                manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
            }
        }.awaitAll()

        assertEquals(64, manager.activeLeaseCount)
        assertEquals(listOf("category:playback:moviePlayback:mixWithOthers", "active:true:none"), controller.calls)

        leases.map { lease -> async(Dispatchers.Default) { lease.close() } }.awaitAll()
        assertEquals(0, manager.activeLeaseCount)
        assertEquals(
            listOf(
                "category:playback:moviePlayback:mixWithOthers",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

}

/** Shared fake seam for the lease-manager and sink-transaction fixtures. */
internal class RecordingAppleAudioSessionController(
    private var failActivationCount: Int = 0,
    private val refuseChannels: Boolean = false,
    private val observe: (String) -> Unit = {},
) : AppleAudioSessionController {
    private val lock = SynchronizedObject()
    private val recorded = mutableListOf<String>()

    val calls: List<String> get() = synchronized(lock) { recorded.toList() }

    override fun setPlaybackCategory(content: AudioContent, mixesWithOthers: Boolean) {
        val mode = when (content) {
            AudioContent.Music -> "default"
            AudioContent.Speech -> "spokenAudio"
            AudioContent.Movie, AudioContent.Automatic -> "moviePlayback"
        }
        record("category:playback:$mode:${if (mixesWithOthers) "mixWithOthers" else "none"}")
    }

    override fun setActive(active: Boolean, notifyOthers: Boolean) {
        synchronized(lock) {
            val event = "active:$active:${if (notifyOthers) "notifyOthers" else "none"}"
            recorded += event
            observe(event)
            if (active && failActivationCount > 0) {
                failActivationCount--
                throw PlannedAppleAudioSessionFailure()
            }
        }
    }

    override fun setMultichannelContent(offered: Boolean) {
        record("multichannel:$offered")
        if (refuseChannels) throw PlannedAppleAudioSessionFailure()
    }

    override fun preferOutputChannels(channels: Int) {
        record("channels:$channels")
        if (refuseChannels) throw PlannedAppleAudioSessionFailure()
    }

    private fun record(event: String) {
        synchronized(lock) {
            recorded += event
            observe(event)
        }
    }
}

internal class PlannedAppleAudioSessionFailure : IllegalStateException("planned activation failure")
