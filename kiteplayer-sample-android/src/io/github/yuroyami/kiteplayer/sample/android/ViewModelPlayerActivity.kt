package io.github.yuroyami.kiteplayer.sample.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo

/**
 * The pattern the README and `rememberKitePlayer` recommend for a rotation (#945). The player lives
 * in a [PlayerViewModel], and this Activity sets no `configChanges`, so a rotation recreates it and
 * playback goes on with no new open: the new composition finds the same player already playing, and
 * [KitePlayerVideo] attaches its own picture to it.
 */
internal class ViewModelPlayerActivity : ComponentActivity() {
    private val model: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = model.controller
        setContent {
            SampleComposeScreen(
                title = getString(R.string.demo_view_model),
                detail = getString(R.string.demo_view_model_detail),
                controller = controller,
                video = {
                    KitePlayerVideo(
                        player = controller.player,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                    )
                },
            )
            // Opens once in the model's life; after a rotation this returns without a second open.
            LaunchedEffect(controller) {
                withFrameNanos { }
                controller.openNormally()
            }
        }
    }

    override fun onPause() {
        // Leaving the screen pauses; a rotation recreates the Activity and must not.
        if (!isChangingConfigurations) model.controller.onBackground()
        super.onPause()
    }
}

/** Holds the sample's player through rotations and closes it when the screen is left for good. */
internal class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val controller: SampleController = SampleController(application)

    override fun onCleared() {
        controller.shutdown()
    }
}
