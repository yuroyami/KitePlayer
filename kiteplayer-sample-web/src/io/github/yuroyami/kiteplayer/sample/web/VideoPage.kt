@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.sample.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteffmpeg.KiteFFmpegWeb
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.compose.KitePlayerControls
import io.github.yuroyami.kiteplayer.compose.KiteVideo
import io.github.yuroyami.kiteplayer.compose.KiteVideoFrameCost
import io.github.yuroyami.kiteplayer.compose.rememberKiteVideoState
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.coroutines.delay

/**
 * The web player page (#125): the clip beside the page, `./clip.mp4`, played through [KiteVideo]
 * with the library's own Compose controls drawn over the picture, and the cost of each frame read
 * off the video state every two seconds, on the page and in the console.
 *
 * The cost is the whole of what a frame costs the page's one thread: the conversion to RGBA in the
 * codec module plus the upload of that RGBA to the GPU. The second line is the upload alone, the
 * step that gets a frame onto the drawable: the page times every large `texSubImage2D` the browser
 * runs, which during playback is KiteVideo's one upload per frame. `?probe` measures the same call
 * on a synthetic frame.
 *
 * Browsers start audio suspended until the page is clicked, and the player's clock follows the
 * sound, so the picture moves once the play button has been pressed.
 *
 * `?shape` clips the picture to a rounded rectangle at 70 percent alpha, which only video drawn as
 * Compose content can do.
 */
@Composable
internal fun VideoPage() {
    val video = rememberKiteVideoState()
    var player by remember { mutableStateOf<KitePlayer?>(null) }
    var status by remember { mutableStateOf("loading the codec module") }
    var costLine by remember { mutableStateOf("") }

    LaunchedEffect(video) {
        installUploadTimer()
        val codec = awaitCodecModule()
        if (codec == null) {
            status = "the page did not publish a codec module; serve kite.mjs and kite.wasm beside it"
            return@LaunchedEffect
        }
        KiteFFmpegWeb.attach(codec)
        status = "fetching ./clip.mp4"
        val clip = fetchClip("./clip.mp4")
        if (clip == null) {
            status = "no ./clip.mp4 beside the page"
            return@LaunchedEffect
        }
        val created = KitePlayer()
        player = created
        created.attachRenderer(video.renderer)
        created.open(MediaItem.from(MediaIo.ofBytes(clip), "clip.mp4"))
        status = "ready, ${clip.size / 1024} KiB: press play"
        while (true) {
            delay(2_000)
            val line = costReport(video.frameCost, video.presentedFrames, video.supersededFrames, video.failedFrames) +
                "\nonto the drawable (texSubImage2D): " + uploadReport()
            costLine = line
            println("KiteVideo $line")
        }
    }
    DisposableEffect(Unit) {
        onDispose { player?.close() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        // ?shape draws the picture as the Compose content it is: clipped to a rounded rectangle and
        // at 70 percent alpha, over the page's background.
        val shaped = pageAsks("shape")
        KiteVideo(
            video,
            if (shaped) {
                Modifier.fillMaxSize().padding(48.dp).clip(RoundedCornerShape(64.dp)).alpha(0.7f)
            } else {
                Modifier.fillMaxSize()
            },
        )
        BasicText(
            text = if (costLine.isEmpty()) status else "$status\n$costLine",
            style = TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x99000000))
                .padding(8.dp),
        )
        KitePlayerControls(player, Modifier.fillMaxSize())
    }
}

/** One line of frame costs, in milliseconds, with the three counts that say what they cover. */
private fun costReport(cost: KiteVideoFrameCost, presented: Long, superseded: Long, failed: Long): String {
    fun ms(nanos: Long): String = ((nanos / 10_000L) / 100.0).toString()
    return "frame cost (convert + upload): last ${ms(cost.lastNanos)} ms, average ${ms(cost.averageNanos)} ms, " +
        "worst ${ms(cost.worstNanos)} ms over ${cost.samples} frames; presented $presented, superseded $superseded, failed $failed"
}

/**
 * Wraps WebGL2's `texSubImage2D` once so that every call with a megabyte or more of pixels is timed
 * on the page's clock. A harness for this page only: the library's own call is untouched, and a
 * page that never installs this pays nothing.
 */
@JsFun(
    """() => {
      const proto = globalThis.WebGL2RenderingContext && WebGL2RenderingContext.prototype;
      if (!proto || proto.__kiteTimed) return;
      const original = proto.texSubImage2D;
      globalThis.__kiteUploads = [];
      proto.texSubImage2D = function (...args) {
        const pixels = args[args.length - 1];
        const large = pixels && pixels.byteLength >= 1000000;
        const started = large ? performance.now() : 0;
        const result = original.apply(this, args);
        if (large) { const u = globalThis.__kiteUploads; u.push(performance.now() - started); if (u.length > 600) u.shift(); }
        return result;
      };
      proto.__kiteTimed = true;
    }""",
)
private external fun installUploadTimer()

/** The timed uploads so far: last, mean, 95th percentile and worst, in milliseconds. */
@JsFun(
    """() => {
      const u = globalThis.__kiteUploads || [];
      if (u.length === 0) return 'no upload yet';
      const sorted = [...u].sort((a, b) => a - b);
      const mean = u.reduce((a, b) => a + b, 0) / u.length;
      const f = (v) => v.toFixed(2);
      return 'last ' + f(u[u.length - 1]) + ' ms, average ' + f(mean) + ' ms, p95 ' + f(sorted[Math.floor(u.length * 0.95)]) +
        ' ms, worst ' + f(sorted[sorted.length - 1]) + ' ms over ' + u.length + ' uploads';
    }""",
)
private external fun uploadReport(): String

/** The codec module the page publishes, or null when it has not after five seconds. */
private suspend fun awaitCodecModule(): kotlin.js.JsAny? {
    var waited = 0
    while (waited < 5_000) {
        loadedCodecModule()?.let { return it }
        delay(50)
        waited += 50
    }
    return null
}

/** Whether the page's address carries [name] as a query parameter. */
@JsFun("(name) => new URLSearchParams(globalThis.location ? location.search : '').has(name)")
internal external fun pageAsks(name: String): Boolean
