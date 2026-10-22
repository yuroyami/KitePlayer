package io.github.yuroyami.kiteplayer.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.compose.KitePlayerSurface

/** Compose UI hosting the same native [io.github.yuroyami.kiteplayer.view.KitePlayerView]. */
internal class ComposeInteropActivity : ComponentActivity() {
    private lateinit var controller: SampleController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SampleController(applicationContext)
        val player = controller.player

        setContent {
            SampleComposeScreen(
                title = getString(R.string.demo_compose_interop),
                detail = getString(R.string.demo_compose_interop_detail),
                controller = controller,
                video = {
                    KitePlayerSurface(player = player, modifier = Modifier.fillMaxSize())
                },
            )

            // The AndroidView and its renderer enter composition before decoder selection.
            LaunchedEffect(player) {
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
        // ComponentActivity disposes composition first, which lets KitePlayerSurface release its
        // native view renderer before the independently owned player closes.
        try {
            super.onDestroy()
        } finally {
            controller.shutdown()
        }
    }
}
