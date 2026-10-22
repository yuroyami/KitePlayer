package io.github.yuroyami.kiteplayer.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath

/** The live-swap demo: one clip, one player, the presentation flips underneath it. */
internal class ComposeSwapActivity : ComponentActivity() {
    private lateinit var controller: SampleController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SampleController(applicationContext)

        setContent {
            var path by remember { mutableStateOf(KiteRenderPath.NativeView) }
            var effective by remember { mutableStateOf("effective=?") }

            SampleComposeScreen(
                title = getString(R.string.demo_compose_swap),
                detail = getString(R.string.demo_compose_swap_detail),
                controller = controller,
                outputStats = effective,
                video = {
                    KitePlayerVideo(
                        player = controller.player,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp)),
                        path = path,
                        onEffectivePath = { effective = "effective=$it" },
                    )
                    BasicText(
                        text = "Swap ($path)",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC315D83))
                            .clickable {
                                path = if (path == KiteRenderPath.NativeView) {
                                    KiteRenderPath.ComposeCanvas
                                } else {
                                    KiteRenderPath.NativeView
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = TextStyle(color = Color.White, fontSize = 13.sp),
                    )
                },
            )

            LaunchedEffect(Unit) {
                // Two frames so the initial path's renderer is attached before decoder selection.
                withFrameNanos { }
                withFrameNanos { }
                controller.openNormally()
            }
        }
    }

    override fun onPause() {
        controller.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        // Composition owns the renderer; the Activity/controller owns the player.
        try {
            super.onDestroy()
        } finally {
            controller.shutdown()
        }
    }
}
