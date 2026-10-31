@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.SubtitleFileParser
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A subtitle file added during playback is parsed off the actor, so the player answers commands
 * while it parses (#594). On the actor's lane a 4 MB script held every command for up to a second.
 */
class SubtitleParseLaneJvmTest {

    private class Lanes : PlaybackDispatchers {
        private val actor = newSingleThreadContext("parse-test-session")
        private val workers = newSingleThreadContext("parse-test-workers")
        private val parsing = newSingleThreadContext("parse-test-parse")
        override val session: CoroutineContext get() = actor
        override val demux: CoroutineContext get() = workers
        override val videoDecode: CoroutineContext get() = workers
        override val audioDecode: CoroutineContext get() = workers
        override val audioFeed: CoroutineContext get() = workers
        override val videoSchedule: CoroutineContext get() = workers
        override val raster: CoroutineContext get() = workers
        override val parse: CoroutineContext get() = parsing
        override fun close() {
            actor.close()
            workers.close()
            parsing.close()
        }
    }

    /** A file's bytes, served in one go. */
    private class Bytes(private val bytes: ByteArray) : MediaIo {
        private var at = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return -1
            val count = minOf(length, bytes.size - at)
            bytes.copyInto(into, offset, at, at + count)
            at += count
            return count
        }
        override suspend fun seek(position: Long) { at = position.toInt() }
        override fun close() = Unit
    }

    @Test
    fun aVolumeChangeIsAnsweredWhileAFileParses(): Unit = runBlocking {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scripted = ScriptedBackend(MediaScript(durationUs = 8_000_000), LeakLedger())
        val slowParser = SubtitleFileParser { _, _ ->
            parsing.countDown()
            // A parse that takes as long as the test wants, as a dense script does.
            release.await(10, TimeUnit.SECONDS)
            listOf(SubtitleCue.Text(startMicros = 0, endMicros = 1_000_000, spans = listOf(StyledSpan("line"))))
        }
        val backend = object : MediaBackend by scripted {
            override fun subtitleFileParser(): SubtitleFileParser = slowParser
        }
        val lanes = Lanes()
        val core = PlaybackCore(
            config = PlayerConfig(),
            backend = backend,
            output = ScriptedOutput(MonotonicClock.System, ScriptedSink()),
            dispatchers = lanes,
            closeDispatchers = false,
        )
        try {
            core.attachRenderer(RecordingRenderer())
            core.open(MediaItem("scripted://playing"))
            val adding = async(Dispatchers.Default) {
                core.addExternalSubtitle(SubtitleSource("test://dense.srt", io = { Bytes("1\n00:00:00,000 --> 00:00:01,000\nline\n".encodeToByteArray()) }))
            }
            check(parsing.await(10, TimeUnit.SECONDS)) { "the parse never started" }
            val answered = withTimeoutOrNull(3.seconds) { core.setVolume(0.5f) } != null
            release.countDown()
            adding.await()
            assertTrue(answered, "a volume change waited for the subtitle file to parse")
        } finally {
            release.countDown()
            core.closeAndAwait()
            lanes.close()
        }
    }
}
