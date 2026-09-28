@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.KiteFFmpegWeb
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The format matrix in a real browser (#59). karma.config.d/format-matrix.js serves the codec
 * module from the kiteffmpeg web zip and the repository's clips to the page. This test fetches each
 * clip into memory and runs every matrix row against it.
 *
 * The web build of FFmpeg carries fewer containers and codecs than the native builds. A row that
 * needs one it leaves out runs as MustSurvive here: it may play or refuse, and only a crash or a
 * hang fails it. Every other row keeps its own verdict. The transcript names the outcome and the
 * video decoder of every row.
 *
 * Node has no page that serves the clips, so the node half of wasmJsTest skips this test.
 */
class WebFormatMatrixTest {

    @Test
    fun everyRowTheWebBuildCarriesPlaysInTheBrowser() = runTest(timeout = 9.minutes) {
        val setup = karmaMatrixConfig()?.split("\n")
        if (setup == null) {
            println("skipped: the format matrix runs only in the browser half, where karma serves the clips")
            return@runTest
        }
        val (module, media) = setup
        // Real time rather than the test scheduler's: the rows wait on fetches under their own timeout.
        val results = withContext(Dispatchers.Default) {
            KiteFFmpegWeb.load(module)
            FormatMatrixRunner.runAll(verdictFor = ::webVerdict) { clip ->
                val bytes = checkNotNull(fetchBytes("$media/$clip")) { "the page could not fetch $media/$clip" }
                MediaItem.from(MediaIo.ofBytes(bytes), clip)
            }
        }
        // One message each: karma joins separate console messages with no line break between them.
        println(results.joinToString("\n", postfix = "\n") { "MATRIX $it" })
        println(conformanceReport("${conformancePlatformName()} in ${userAgent()}", results))
        val failed = results.filterNot { it.ok }
        assertTrue(failed.isEmpty(), "matrix rows failed in the browser:\n${failed.joinToString("\n")}")
    }
}

/**
 * The rows whose container or codec the web build of FFmpeg does not carry. KiteFFmpeg pins the web
 * codec set in its BuildFFmpegWasmTaskTest. Every other row keeps its own verdict in the browser.
 * AV1 is out because the web build has no dav1d: dav1d needs threads, and the default web artifact
 * runs without them.
 */
private val NOT_IN_THE_WEB_BUILD = setOf(
    "mpeg4part2.mp4",
    "tsoffset1400.ts",
    "av1.mkv",
    "avi-mpeg4.avi",
    "wmv-msmpeg4.wmv",
    "flv-flv1.flv",
    "vob-mpeg2.vob",
    "audio-eac3.mkv",
    "audio-dts.mkv",
    "audio-truehd.mkv",
    "audio-alac.m4a",
)

private fun webVerdict(row: MatrixRow): MatrixVerdict =
    if (row.clip in NOT_IN_THE_WEB_BUILD) MatrixVerdict.MustSurvive else row.verdict

/** The whole resource at [url], or null when the page cannot fetch it. */
private suspend fun fetchBytes(url: String): ByteArray? {
    val array = fetchArray(url).await<JsAny?>() ?: return null
    val length = arrayLength(array)
    val bytes = ByteArray(length)
    // One string per chunk rather than one call per byte, which is far too slow for a 20 MB clip.
    var at = 0
    while (at < length) {
        val end = minOf(length, at + CHUNK_BYTES)
        val chunk = latin1Slice(array, at, end)
        for (i in chunk.indices) bytes[at + i] = chunk[i].code.toByte()
        at = end
    }
    return bytes
}

private const val CHUNK_BYTES = 8192

/** The module and clip paths that karma.config.d/format-matrix.js hands the page, or null outside karma. */
@JsFun(
    """() => {
        const karma = globalThis.__karma__;
        const matrix = karma && karma.config ? karma.config.kiteMatrix : undefined;
        return matrix ? matrix.module + "\n" + matrix.media : null;
    }""",
)
private external fun karmaMatrixConfig(): String?

@JsFun("(url) => fetch(url).then(r => r.ok ? r.arrayBuffer().then(b => new Uint8Array(b)) : null, () => null)")
private external fun fetchArray(url: String): Promise<JsAny?>

@JsFun("(a) => a.length")
private external fun arrayLength(array: JsAny): Int

@JsFun("(a, from, to) => String.fromCharCode.apply(null, a.subarray(from, to))")
private external fun latin1Slice(array: JsAny, from: Int, to: Int): String

@JsFun("() => globalThis.navigator ? navigator.userAgent : 'an unknown browser'")
private external fun userAgent(): String
