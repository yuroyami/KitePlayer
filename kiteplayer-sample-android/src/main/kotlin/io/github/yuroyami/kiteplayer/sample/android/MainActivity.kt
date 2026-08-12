package io.github.yuroyami.kiteplayer.sample.android

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The whole user interface, built programmatically (S1.c.6 step 4): one SurfaceView and three
 * buttons. No XML layout, no reusable public view, no declarative UI toolkit; the reusable phone host is
 * S1.d's, and this Activity exists to prove the assembled backend in an ordinary app.
 */
internal class MainActivity : Activity() {

    private lateinit var controller: SampleController
    private var smoke = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SampleController(applicationContext)
        smoke = intent.getBooleanExtra("s1c_smoke", false)

        val surfaceView = SurfaceView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                surfaceView,
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
        setContentView(root, FrameLayout.LayoutParams(-1, -1))

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (smoke) controller.runSmoke(holder.surface) else controller.openNormally(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                controller.onSurfaceDestroyed()
            }
        })
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
