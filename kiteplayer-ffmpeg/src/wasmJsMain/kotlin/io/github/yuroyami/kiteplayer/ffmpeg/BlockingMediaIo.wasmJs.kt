package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteffmpeg.MediaByteSource
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * The web cannot block, so this stages the source instead of parking a thread (17.14 X-09).
 *
 * Every other target bridges the engine's suspending [MediaIo] onto KiteFFmpeg's synchronous
 * [MediaByteSource] with `runBlocking`, which parks the demux worker and nothing else. A browser
 * has no such primitive: `runBlocking` does not exist in Kotlin/Wasm and blocking the main thread
 * is forbidden outright.
 *
 * What is possible today is what a browser actually has. A `File`, a `fetch` response and an
 * `ArrayBuffer` are all whole before playback starts, so a caller can hand over a source that is
 * already resident and every read is answered from memory with no suspension at all. A source that
 * genuinely needs to await bytes is REFUSED here, loudly, rather than served by a busy-wait that
 * would freeze the page.
 *
 * The general fix is the Worker of X-08, where blocking IS legal, and at that point this class
 * becomes the blocking bridge again.
 */
internal actual class BlockingMediaIo actual constructor(
    private val io: MediaIo,
) : MediaByteSource {

    actual override val size: Long? get() = io.size
    actual override val seekable: Boolean get() = io.seekable

    actual override fun read(into: ByteArray, offset: Int, length: Int): Int {
        val outcome = runWithoutSuspending { io.read(into, offset, length) }
        if (outcome == null) {
            throw UnsupportedOperationException(
                "This media source suspends while reading, and the web backend cannot wait for it: " +
                    "Kotlin/Wasm has no runBlocking and a browser forbids blocking the main thread. " +
                    "Supply a source whose bytes are already resident (a File, a fetch response or " +
                    "an ArrayBuffer), or run the player in a Worker where blocking is legal " +
                    "(KPKMP-FUTURE.md 17.14 X-08).",
            )
        }
        return outcome
    }

    actual override fun seek(position: Long) {
        val outcome = runWithoutSuspending { io.seek(position); 0 }
        if (outcome == null) {
            throw UnsupportedOperationException(
                "This media source suspends while seeking, which the web backend cannot wait for. " +
                    "See the message on read for the two supported shapes.",
            )
        }
    }

    actual override fun close(): Unit = io.close()
}

/**
 * Runs [block] and returns its value, or null if it SUSPENDED.
 *
 * `startCoroutineUninterceptedOrReturn` answers `COROUTINE_SUSPENDED` when the body did not finish
 * on the spot, which is exactly the distinction this backend needs: a memory-backed source finishes
 * immediately and a network-backed one does not. The continuation is only reached if the body
 * completes asynchronously, and that counts as suspended too, so its result is discarded.
 *
 * KNOWN LIMIT, and it belongs to whoever takes X-08. A suspended body is NOT cancelled: it keeps
 * running after the caller has already thrown, and its eventual resume writes into the caller's
 * `ByteArray` or moves the source position. That is harmless for every source this backend accepts,
 * because those never suspend, and it is exactly the hazard a Worker implementation must not
 * inherit unknowingly. Cancelling properly needs a `Job` around the call, which is worth doing when
 * a suspending source becomes legal rather than now, when reaching this path is already an error.
 */
@Suppress("UNCHECKED_CAST")
private fun runWithoutSuspending(block: suspend () -> Int): Int? {
    val noop = object : Continuation<Int> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Int>) = Unit
    }
    val outcome = block.startCoroutineUninterceptedOrReturn(noop)
    return if (outcome === COROUTINE_SUSPENDED) null else outcome as Int
}
