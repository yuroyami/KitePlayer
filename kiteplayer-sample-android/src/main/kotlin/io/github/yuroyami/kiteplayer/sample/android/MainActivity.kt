package io.github.yuroyami.kiteplayer.sample.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.io.ofAsset
import io.github.yuroyami.kiteplayer.io.ofUri
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer
import io.github.yuroyami.kiteplayer.view.KitePlayerView
import io.github.yuroyami.kiteplayer.view.enterPictureInPicture
import io.github.yuroyami.kiteplayer.view.keepPictureInPictureParamsCurrent

/**
 * Direct native-view demo: one [KitePlayerView] inflated from XML and three ordinary buttons.
 * The XML path is deliberate:
 * it proves the native-view artifact is usable without Compose or a programmatic factory. The
 * surface lifecycle still lives entirely inside the reusable view, so this Activity owns no
 * SurfaceHolder callback, renderer or Surface.
 */
internal class MainActivity : Activity() {

    private lateinit var controller: SampleController
    private lateinit var playerView: KitePlayerView
    private var smoke = false
    private var pictureInPictureParams: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        smoke = intent.getBooleanExtra("s1c_smoke", false)
        controller = SampleController(
            context = applicationContext,
            hardwareDecode = if (smoke) HwdecPolicy.Require else HwdecPolicy.Auto,
        )

        setContentView(R.layout.activity_main)

        playerView = findViewById<KitePlayerView>(R.id.player_view).apply { installMobileRenderer() }
        val controls = findViewById<View>(R.id.controls)
        val perfOverlay = findViewById<TextView>(R.id.performance)
        val showPerfOverlay = true

        findViewById<Button>(R.id.play).setOnClickListener { controller.play() }
        findViewById<Button>(R.id.pause).setOnClickListener { controller.pause() }
        findViewById<Button>(R.id.seek_five).setOnClickListener { controller.seekToFiveSeconds() }

        controls.visibility = if (smoke) View.GONE else View.VISIBLE
        perfOverlay.visibility = if (!smoke && showPerfOverlay) View.VISIBLE else View.GONE
        if (perfOverlay.visibility == View.VISIBLE) {
            controller.observePerformance(playerView) { perfOverlay.text = it }
        }

        if (!smoke) {
            // The view keeps this window's picture-in-picture parameters current, so leaving the app
            // while the clip plays opens the window by itself on Android 12 and later.
            playerView.player = controller.player
            pictureInPictureParams = playerView.keepPictureInPictureParamsCurrent(this)
        }

        // No surface wait: the view attaches its headless-capable renderer before open, then forwards
        // Surface lifecycle changes without rebuilding the decoder.
        val item = requestedItem()
        if (smoke) controller.runSmoke(playerView, item) else controller.openNormally(playerView, item)
    }

    /**
     * The door the launcher asked for: a picked file through the content door, or the bundled clip
     * through the asset door. Null plays the private copy of the bundled clip by its path.
     */
    private fun requestedItem(): MediaItem? = when (intent.getStringExtra(EXTRA_SOURCE)) {
        SOURCE_PICKED -> intent.data?.let { uri -> MediaItem(uri = uri.toString(), io = MediaIo.ofUri(contentResolver, uri)) }
        SOURCE_ASSET -> MediaItem(uri = BUNDLED_CLIP, io = MediaIo.ofAsset(assets, BUNDLED_CLIP))
        SOURCE_PATH -> intent.getStringExtra(EXTRA_PATH)?.let { MediaItem(it) }
        else -> null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 nothing opens the window on its own, so leaving while playing asks for it.
        if (!smoke && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            controller.player.state.value.status == PlaybackStatus.Playing
        ) {
            playerView.enterPictureInPicture(this)
        }
    }

    override fun onPause() {
        super.onPause()
        /* Backgrounding pauses; the sample invents no audio-focus policy. The picture-in-picture
         * window is the exception: it exists to keep playing. */
        if (!smoke && !isInPictureInPictureMode) controller.onBackground()
    }

    override fun onDestroy() {
        pictureInPictureParams?.close()
        try {
            playerView.release()
        } finally {
            try {
                controller.shutdown()
            } finally {
                super.onDestroy()
            }
        }
    }

    companion object {
        /**
         * Which door to open: [SOURCE_PICKED] with the file as the intent's data, [SOURCE_ASSET], or
         * [SOURCE_PATH] with a file this app can read in [EXTRA_PATH], which the instrumented tests use.
         */
        const val EXTRA_SOURCE = "kite_source"
        const val SOURCE_PICKED = "picked"
        const val SOURCE_ASSET = "asset"
        const val SOURCE_PATH = "path"
        const val EXTRA_PATH = "kite_path"
    }
}
