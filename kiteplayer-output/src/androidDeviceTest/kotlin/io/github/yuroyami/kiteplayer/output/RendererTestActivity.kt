package io.github.yuroyami.kiteplayer.output

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch

/**
 * The one SurfaceView host of the renderer device test (S1.c.5 step 8). Test-private: it is not
 * a reusable view and never becomes one; S1.d owns the reusable phone host. The latch hands the
 * created Surface to the test thread; destruction is driven by the test removing the view.
 */
internal class RendererTestActivity : Activity() {

    lateinit var surfaceView: SurfaceView
        private set

    val surfaceCreated = CountDownLatch(1)
    val surfaceDestroyed = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceCreated.countDown()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceDestroyed.countDown()
            }
        })
        setContentView(surfaceView)
    }
}
