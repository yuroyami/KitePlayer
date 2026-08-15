package io.github.yuroyami.kiteplayer.sample.android

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer
import io.github.yuroyami.kiteplayer.view.KitePlayerView

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

        // No surface wait: the view attaches its headless-capable renderer before open, then forwards
        // Surface lifecycle changes without rebuilding the decoder.
        if (smoke) controller.runSmoke(playerView) else controller.openNormally(playerView)
    }

    override fun onPause() {
        super.onPause()
        /* Backgrounding pauses; the sample invents no audio-focus policy (S1.c.6 step 4). */
        if (!smoke) controller.onBackground()
    }

    override fun onDestroy() {
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
}
