package io.github.yuroyami.kiteplayer.verification.network

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import probe.runProbe

/** No provider symbol or custom keep rule roots the optional transport in this release app. */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("urlBase64")?.let {
            Base64.decode(it, Base64.DEFAULT).decodeToString()
        } ?: intent.getStringExtra("url") ?: "http://10.0.2.2:8765/media"
        val expected = intent.getStringExtra("expectedIo") ?: "true"
        val runId = intent.getStringExtra("runId") ?: "manual"
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runProbe(arrayOf(url, expected))
                Log.i(TAG, "NETWORK_PROBE_ANDROID_OK run=$runId expectedIo=$expected")
            } catch (failure: Throwable) {
                Log.e(TAG, "NETWORK_PROBE_ANDROID_FAILED run=$runId expectedIo=$expected", failure)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private companion object {
        const val TAG = "KiteNetworkProbe"
    }
}
