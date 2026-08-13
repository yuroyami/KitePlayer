package io.github.yuroyami.kiteplayer.sample.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.yuroyami.kiteplayer.phone.KitePlayerView

/**
 * The whole user interface, built programmatically: one [KitePlayerView] and three buttons.
 * Since S1.d.4 the surface lifecycle lives inside the reusable view; this Activity holds no
 * SurfaceHolder callback, no renderer and no Surface, which is the shape an ordinary consumer's
 * Activity actually has.
 */
internal class MainActivity : Activity() {

    private lateinit var controller: SampleController
    private var smoke = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SampleController(applicationContext)
        smoke = intent.getBooleanExtra("s1c_smoke", false)

        val playerView = KitePlayerView(this)
        val showPerfOverlay = true
        val video = FrameLayout(this).apply {
            addView(
                playerView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                video,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            if (!smoke) {
                val controls = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.play)
                        setOnClickListener { controller.play() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.pause)
                        setOnClickListener { controller.pause() }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.seek_five)
                        setOnClickListener { controller.seekToFiveSeconds() }
                    })
                }
                addView(controls)
            }
        }
        if (!smoke && showPerfOverlay) {
            val perfOverlay = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                setPadding(12, 8, 12, 8)
                text = getString(R.string.perf_waiting)
            }
            video.addView(
                perfOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
            controller.observePerformance(playerView) { perfOverlay.text = it }
        }
        setContentView(root, FrameLayout.LayoutParams(-1, -1))

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
        controller.shutdown()
        super.onDestroy()
    }
}
