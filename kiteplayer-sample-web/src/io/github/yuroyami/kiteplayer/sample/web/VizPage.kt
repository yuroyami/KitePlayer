package io.github.yuroyami.kiteplayer.sample.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteffmpeg.KiteFFmpegWeb
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.audioviz.KiteAudioViz
import io.github.yuroyami.kiteplayer.audioviz.rememberAudioVizState
import io.github.yuroyami.kiteplayer.compose.KitePlayerControls
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes

/**
 * The visualiser page, opened with `?viz` (#775): the song beside the page, `./song.mp3`, drawn by
 * [KiteAudioViz] with the library's Compose controls over it.
 *
 * Browsers start audio suspended until the page is clicked, so the drawing moves once the play
 * button has been pressed.
 */
@Composable
internal fun VizPage() {
    var player by remember { mutableStateOf<KitePlayer?>(null) }
    var status by remember { mutableStateOf("loading the codec module") }

    LaunchedEffect(Unit) {
        val codec = awaitCodecModule()
        if (codec == null) {
            status = "the page did not publish a codec module; serve kite.mjs and kite.wasm beside it"
            return@LaunchedEffect
        }
        KiteFFmpegWeb.attach(codec)
        status = "fetching ./song.mp3"
        val song = fetchClip("./song.mp3")
        if (song == null) {
            status = "no ./song.mp3 beside the page"
            return@LaunchedEffect
        }
        val created = KitePlayer()
        created.open(MediaItem.from(MediaIo.ofBytes(song), "song.mp3"))
        player = created
        status = "ready, ${song.size / 1024} KiB: press play"
    }
    DisposableEffect(Unit) {
        onDispose { player?.close() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        player?.let { opened ->
            val viz = rememberAudioVizState(opened)
            KiteAudioViz(viz, Modifier.fillMaxSize())
        }
        BasicText(
            text = status,
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
