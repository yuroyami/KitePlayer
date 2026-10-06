package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The actor's mailbox is bounded (#483). A storm of one setting while the actor is busy holds one
 * command and lands on the newest value, every call of it answered once the value that replaced it
 * applied; settings of different kinds keep the order they were first asked in; and requests past
 * the bound are refused with a clear error rather than kept or dropped, the player carrying on.
 * The calls below are all made before the actor runs, as they are while it is busy.
 */
class MailboxBoundTest {

    @Test
    fun aStormOfOneSettingHoldsOneCommandAndLandsOnTheNewest() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        val replies = (1..10_000).map { step ->
            CompletableDeferred<Unit>().also { harness.core.post(CoreCommand.SetVolume(step / 10_000f, it)) }
        }
        assertEquals(1, harness.core.waitingInMailbox, "ten thousand volume changes held more than one command")
        assertFalse(replies.first().isCompleted, "a replaced call was answered before its value applied")
        harness.run(50.milliseconds)
        assertTrue(replies.all { it.isCompleted && !it.isCancelled }, "a volume call was never answered")
        replies.forEach { it.await() }
        assertEquals(1f, harness.core.snapshots.value.volume)
        assertEquals(0, harness.core.waitingInMailbox)
        harness.close()
    }

    @Test
    fun settingsOfDifferentKindsKeepTheOrderTheyWereFirstAskedIn() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.post(CoreCommand.SetVolume(0.2f, CompletableDeferred()))
        harness.core.post(CoreCommand.SetMuted(true, CompletableDeferred()))
        harness.core.post(CoreCommand.SetVolume(0.7f, CompletableDeferred()))
        harness.core.post(CoreCommand.SetMuted(false, CompletableDeferred()))
        harness.core.post(CoreCommand.SetMuted(true, CompletableDeferred()))
        assertEquals(2, harness.core.waitingInMailbox)
        harness.run(50.milliseconds)
        assertEquals(0.7f, harness.core.snapshots.value.volume)
        assertEquals(true, harness.core.snapshots.value.muted)
        harness.close()
    }

    @Test
    fun requestsPastTheBoundAreRefusedAndThePlayerCarriesOn() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        var accepted = 0
        val refusal = assertFailsWith<IllegalStateException> {
            repeat(5_000) {
                harness.core.play()
                accepted++
            }
        }
        assertEquals(1024, accepted, "the bound let $accepted requests wait")
        assertTrue("requests waiting" in refusal.message.orEmpty(), refusal.message)
        harness.run(100.milliseconds)
        assertEquals(0, harness.core.waitingInMailbox)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        // Once the actor has caught up, the calls are taken again.
        harness.core.pause()
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }
}
