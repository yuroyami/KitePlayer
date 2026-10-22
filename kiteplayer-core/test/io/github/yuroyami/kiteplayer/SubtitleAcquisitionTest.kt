package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reading a subtitle file the caller adds is a task its request owns (#412): a reader that is slow
 * to open holds no stop or close, a stop or a close or the caller leaving cancels it, the stall
 * limit covers the open, and a reader made after the cancel is closed exactly once.
 */
class SubtitleAcquisitionTest {

    private class BytesIo(private val bytes: ByteArray) : MediaIo {
        var closes = 0
        private var at = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override fun close() {
            closes++
        }
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(length, bytes.size - at)
            bytes.copyInto(into, offset, at, at + n)
            at += n
            return n
        }
        override suspend fun seek(position: Long) {
            at = position.toInt()
        }
    }

    private fun srt() = BytesIo("1\n00:00:00,000 --> 00:00:03,000\ntext\n".encodeToByteArray())

    @Test
    fun aReaderThatNeverOpensHoldsNoStopOrClose() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val gate = CompletableDeferred<Unit>()
        var factoryCancelled = false
        val adding = launch {
            harness.core.addExternalSubtitle(
                SubtitleSource("test://slow.srt", io = {
                    try {
                        gate.await()
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        factoryCancelled = true
                        throw cancellation
                    }
                    srt()
                }),
            )
        }
        harness.run(100.milliseconds)
        adding.cancel()
        val asked = harness.clock.nanos().nanoseconds
        val stopping = async { harness.core.stop() }
        val closing = async {
            harness.core.closeAndAwait()
            harness.clock.nanos().nanoseconds
        }
        harness.run(1.seconds)
        assertTrue(stopping.isCompleted, "stop waited for the subtitle reader")
        assertTrue(factoryCancelled, "the reader's open was never cancelled")
        // A close is answered from a thread of its own, because its last step closes the dispatchers
        // the engine runs on, so it may still be on its way after a second of virtual time and is
        // awaited (#521). With the device stopped nothing moves the virtual clock while it comes, so
        // the time it reads is when the engine let it go: inside the second, where a close held by
        // the reader would read the 30 second stall limit.
        harness.stopDevice()
        val closedAt = closing.await()
        assertTrue(closedAt - asked <= 1.seconds, "close waited ${closedAt - asked} for the subtitle reader")
    }

    @Test
    fun aStopCancelsTheReadAndAnswersTheCaller() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val adding = async {
            runCatching { harness.core.addExternalSubtitle(SubtitleSource("test://slow.srt", io = { CompletableDeferred<MediaIo>().await() })) }
        }
        harness.run(100.milliseconds)
        harness.core.stop()
        harness.run(100.milliseconds)
        assertTrue(adding.isCompleted, "the caller was never answered")
        assertIs<IllegalStateException>(adding.await().exceptionOrNull(), "the stop did not end the add")
        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aReaderThatNeverOpensIsRefusedAtTheStallLimitAndTheMediaPlaysOn() = runTest {
        val harness = CoreHarness(this, config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 5.seconds)))
        harness.openWithRenderer()
        val adding = async {
            runCatching { harness.core.addExternalSubtitle(SubtitleSource("test://silent.srt", io = { CompletableDeferred<MediaIo>().await() })) }
        }
        harness.run(6.seconds)
        val failure = adding.await().exceptionOrNull()
        assertIs<IllegalArgumentException>(failure)
        assertTrue("did not open within" in failure.message.orEmpty(), "the refusal was ${failure.message}")
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the refusal stopped the media")
        harness.close()
    }

    @Test
    fun aReaderMadeAfterTheStopIsClosedExactlyOnce() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val gate = CompletableDeferred<Unit>()
        val reader = srt()
        val adding = async {
            runCatching {
                harness.core.addExternalSubtitle(
                    SubtitleSource("test://stubborn.srt", io = {
                        // A factory that does not hear the cancel, and hands back a reader anyway.
                        withContext(NonCancellable) { gate.await() }
                        reader
                    }),
                )
            }
        }
        harness.run(100.milliseconds)
        harness.core.stop()
        assertTrue(adding.await().isFailure)
        gate.complete(Unit)
        harness.run(100.milliseconds)
        assertEquals(1, reader.closes, "the late reader was closed ${reader.closes} times")
        harness.close()
    }

    @Test
    fun aQuickReaderStillAddsAndSelectsItsTrack() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val reader = srt()
        val id = harness.core.addExternalSubtitle(SubtitleSource("test://quick.srt", io = { reader }))
        assertEquals(id, harness.core.snapshots.value.tracks.selectedSubtitle)
        assertEquals(1, reader.closes)
        harness.close()
    }

    @Test
    fun aDeclaredFileThatNeverOpensDoesNotHoldAStopOfTheOpen() = runTest {
        val harness = CoreHarness(this)
        harness.attachRenderer()
        val item = MediaItem(
            "scripted://media",
            externalSubtitles = listOf(SubtitleSource("test://declared.srt", io = { CompletableDeferred<MediaIo>().await() })),
        )
        val opening = async { runCatching { harness.core.open(item) } }
        harness.run(100.milliseconds)
        val stopping = async { harness.core.stop() }
        harness.run(500.milliseconds)
        assertTrue(stopping.isCompleted, "stop waited for the declared subtitle file")
        assertTrue(opening.await().isFailure, "the preempted open said it succeeded")
        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        harness.close()
    }
}
