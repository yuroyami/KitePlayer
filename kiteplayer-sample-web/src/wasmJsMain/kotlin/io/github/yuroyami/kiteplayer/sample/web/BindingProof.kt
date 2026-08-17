package io.github.yuroyami.kiteplayer.sample.web

import io.github.yuroyami.kitecodec.FFmpeg
import io.github.yuroyami.kitecodec.KiteCodecWeb
import io.github.yuroyami.kitecodec.MediaByteSource
import io.github.yuroyami.kitecodec.MediaSource
import io.github.yuroyami.kitecodec.MediaType
import kotlin.js.JsAny

/**
 * Drives the REAL `kitecodec-core` web backend from Kotlin (17.14 X-07).
 *
 * Not the raw binding: this calls `MediaSource`, `StreamInfo` and `Frame`, which is the API every
 * other platform uses and the one `:kiteplayer-ffmpeg` is written against. If this works, the
 * engine has something to sit on.
 */
internal suspend fun runBackendProof(clip: ByteArray, report: (String) -> Unit) {
    // attach, not load: this page is webpack-bundled, and a bundler rewrites import(url) at build
    // time. index.html loads the module and publishes it, which no bundler can break.
    // The page publishes the module from a DEFERRED module script, so it lands after this bundle
    // starts. Bounded wait: a missing module must report itself rather than hang the page.
    var loaded = loadedCodecModule()
    var waited = 0
    while (loaded == null && waited < 5000) {
        kotlinx.coroutines.delay(50)
        waited += 50
        loaded = loadedCodecModule()
    }
    if (loaded == null) error("the page did not publish a codec module after ${waited}ms")
    KiteCodecWeb.attach(loaded)
    report("codec loaded, identity ${if (FFmpeg.identity.isAcceptable) "acceptable" else "REJECTED"}")
    report("build ${FFmpeg.identity.buildFFmpegRef}, abi ${FFmpeg.identity.cAbiVersion}, ${FFmpeg.identity.libraries.size} libraries")

    MediaSource.open(ArrayByteSource(clip), emptyMap()).use { media ->
        val video = media.primaryVideo
        report("container ${media.formatName}, ${media.streams.size} streams, ${media.durationMicros?.div(1000)}ms, seekable ${media.isSeekable}")
        if (video == null) {
            report("no video stream")
            return
        }
        report("video ${video.codec.name} ${video.video?.width}x${video.video?.height} timeBase ${video.timeBase.num}/${video.timeBase.den}")

        var frames = 0
        var firstPts = Long.MIN_VALUE
        media.openDecoder(video).use { decoder ->
            media.openPacketReader(listOf(video)).use { reader ->
                while (frames < 3) {
                    val packet = reader.read() ?: break
                    packet.use { p ->
                        if (decoder.send(p)) {
                            while (frames < 3) {
                                val frame = decoder.receive() ?: break
                                frame.use { f ->
                                    if (firstPts == Long.MIN_VALUE) firstPts = f.info.pts
                                    if (frames == 0) {
                                        val bytes = f.copyPlanesToByteArray()
                                        report("frame ${f.info.width}x${f.info.height} ${f.info.pixelFormat.name}, ${bytes.size} plane bytes")
                                    }
                                    frames++
                                }
                            }
                        }
                    }
                }
            }
        }
        report(if (frames > 0) "DECODED $frames frames through kitecodec-core, first pts $firstPts" else "FAILED: no frames")
    }

    // X-12: the engine's own entry point, which is what a consumer actually calls.
    reportPlayerWiring(report)
}

/**
 * Asks the ENGINE whether the web is playable, which is the question a consumer asks.
 *
 * Everything above proves the codec works. This proves the player agrees: availability must flip
 * once the codec module is loaded, and `createOrNull` must return a real player rather than null.
 */
private suspend fun reportPlayerWiring(report: (String) -> Unit) {
    val availability = io.github.yuroyami.kiteplayer.KitePlayerPlatform.availability
    report("player availability: $availability")
    val player = io.github.yuroyami.kiteplayer.KitePlayerPlatform.createOrNull()
    if (player == null) {
        report("player: createOrNull returned NULL, the web stack is not wired")
    } else {
        report("player: CREATED through KitePlayerPlatform, backends resolved")
        player.closeAndAwait()
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.__kite ?? null")
private external fun loadedCodecModule(): JsAny?

/** The shape a browser actually has: the whole clip already in memory. */
private class ArrayByteSource(private val bytes: ByteArray) : MediaByteSource {
    private var position = 0
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean get() = true
    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + n)
        position += n
        return n
    }
    override fun seek(position: Long) { this.position = position.toInt() }
    override fun close() {}
}

/**
 * Fetches the clip beside the page, or null when there is none.
 *
 * Copied a byte at a time, which is why the proof uses a SMALL clip. Kotlin/Wasm has no bulk
 * Uint8Array to ByteArray move, and the same limit applies again when `WebIoBridge` stages the
 * bytes into the codec module. Both are real work before the web ships: a typed-array bridge, or
 * better, fetching straight into codec memory and never materialising a Kotlin array at all.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal suspend fun fetchClip(url: String): ByteArray? {
    val length = awaitInt(fetchIntoGlobal(url))
    if (length <= 0) return null
    val bytes = ByteArray(length)
    for (i in 0 until length) bytes[i] = fetchByte(i).toByte()
    return bytes
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""(url) => fetch(url)
    .then(r => r.ok ? r.arrayBuffer() : null)
    .then(b => { if (!b) return 0; globalThis.__clip = new Uint8Array(b); return globalThis.__clip.length; })
    .catch(() => 0)""")
private external fun fetchIntoGlobal(url: String): kotlin.js.Promise<kotlin.js.JsNumber>

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(i) => globalThis.__clip[i]")
private external fun fetchByte(index: Int): Int

/** Awaits a JS promise without a coroutines-js dependency. */
private suspend fun awaitInt(promise: kotlin.js.Promise<kotlin.js.JsNumber>): Int =
    kotlin.coroutines.suspendCoroutine { continuation ->
        promise.then(
            onFulfilled = { value -> continuation.resumeWith(Result.success(value.toInt())); value },
            onRejected = { error -> continuation.resumeWith(Result.success(0)); error },
        )
    }
