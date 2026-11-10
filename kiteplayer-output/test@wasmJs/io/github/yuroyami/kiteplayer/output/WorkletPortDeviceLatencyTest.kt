@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The worker's audio device answers the page's latest output latency, not the one it was built
 * with (#901). The page reads it once at the start, before the context even runs, and the worker
 * kept that figure for good, so after a move to Bluetooth headphones the picture ran ahead of the
 * sound by the difference.
 */
class WorkletPortDeviceLatencyTest {

    @Test
    fun theDeviceAnswersThePagesLatestLatency() {
        var latency: Double? = 0.02
        val device = WorkletPortDevice(WorkletPort(fakePort(), 2), 48_000, 2, { latency }) { }
        assertEquals(0.02, device.outputLatencySeconds())
        latency = 0.2
        assertEquals(0.2, device.outputLatencySeconds(), "the device kept the latency it was built with")
        latency = null
        assertNull(device.outputLatencySeconds())
    }
}

@JsFun("() => ({ postMessage: () => {} })")
private external fun fakePort(): JsAny
