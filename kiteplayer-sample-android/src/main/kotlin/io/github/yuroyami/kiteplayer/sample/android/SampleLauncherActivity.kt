package io.github.yuroyami.kiteplayer.sample.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

/** Launcher only: each choice opens an Activity dedicated to one presentation architecture. */
internal class SampleLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preserve a convenient generic smoke launch as well as the historical explicit
        // .MainActivity component used by the measured harness.
        if (intent.getBooleanExtra(SMOKE_EXTRA, false)) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                intent.extras?.let(::putExtras)
            })
            finish()
            return
        }

        setContentView(R.layout.activity_launcher)
        findViewById<Button>(R.id.direct_xml).setOnClickListener { open(MainActivity::class.java) }
        findViewById<Button>(R.id.compose_interop).setOnClickListener {
            open(ComposeInteropActivity::class.java)
        }
        findViewById<Button>(R.id.compose_native).setOnClickListener {
            open(ComposeVideoActivity::class.java)
        }
        findViewById<Button>(R.id.compose_swap).setOnClickListener {
            open(ComposeSwapActivity::class.java)
        }
    }

    private fun open(activity: Class<out Activity>) {
        startActivity(Intent(this, activity))
    }

    private companion object {
        const val SMOKE_EXTRA = "s1c_smoke"
    }
}
