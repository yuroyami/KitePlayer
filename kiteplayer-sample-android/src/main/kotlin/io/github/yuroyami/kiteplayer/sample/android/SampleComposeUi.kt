package io.github.yuroyami.kiteplayer.sample.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Shared controls and engine telemetry; the video slot remains specific to each public API. */
@Composable
internal fun SampleComposeScreen(
    title: String,
    detail: String,
    controller: SampleController,
    outputStats: String? = null,
    video: @Composable BoxScope.() -> Unit,
) {
    val snapshot by controller.player.state.collectAsState()
    val stats by controller.player.stats.collectAsState()
    val engineStats = String.format(
        Locale.US,
        "%s  decode=%.1f  submitted=%d  dropped=%d  %s",
        snapshot.status,
        stats.videoDecodeFps,
        stats.submittedFrames,
        stats.droppedFramesLate + stats.droppedFramesDecode,
        stats.hardwareDecode,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicText(
                text = title,
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = detail,
                style = TextStyle(color = Color(0xFFCCCCCC), fontSize = 13.sp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            content = video,
        )
        BasicText(
            text = engineStats,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = TextStyle(color = Color.White, fontSize = 12.sp),
        )
        outputStats?.let { line ->
            BasicText(
                text = line,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = TextStyle(color = Color(0xFF9AD5FF), fontSize = 12.sp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SampleButton("Play", Modifier.weight(1f), controller::play)
            SampleButton("Pause", Modifier.weight(1f), controller::pause)
            SampleButton("Seek 5s", Modifier.weight(1f), controller::seekToFiveSeconds)
        }
    }
}

@Composable
private fun SampleButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Color(0xFF315D83))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }
}
