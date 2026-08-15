package io.github.yuroyami.kiteplayer.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import java.util.concurrent.CountDownLatch

/**
 * Hosts KiteVideo under real Compose modifiers, because modifiers applying to the video itself
 * is the whole D-6 flagship claim: the picture below is clipped to rounded corners and rotated
 * a few degrees by graphicsLayer, with no platform view anywhere.
 */
internal class KiteVideoTestActivity : ComponentActivity() {

    lateinit var videoState: KiteVideoState
        private set
    val composeConsumerReady = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoState = createAndroidKiteVideoState(window)
        setContent {
            KiteVideo(
                state = videoState,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .graphicsLayer { rotationZ = 3f },
            )
            LaunchedEffect(videoState) {
                // Match production integration: let KiteVideo bind its exact Window consumer
                // before a renderer is attached and media opens.
                withFrameNanos { }
                composeConsumerReady.countDown()
            }
        }
    }
}
