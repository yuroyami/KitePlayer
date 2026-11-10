@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals

/** A fake device state: a context in [contextState] that counts the resumes it is asked for. */
@JsFun("(contextState, paused, closed) => ({ paused: paused, closed: closed, ctx: { state: contextState, resumes: 0, resume() { this.resumes++; return Promise.resolve(); } } })")
private external fun fakeDevice(contextState: String, paused: Boolean, closed: Boolean): JsAny

@JsFun("(s) => s.ctx.resumes")
private external fun resumesOf(state: JsAny): Int

/** A gesture on the page resumes the audio a browser holds back, but never a paused player's (#910). */
class WebAudioUnlockTest {

    private fun resumesAfterGesture(contextState: String, paused: Boolean = false, closed: Boolean = false): Int {
        val device = fakeDevice(contextState, paused, closed)
        webAudioUnlock(device)
        return resumesOf(device)
    }

    @Test
    fun aGestureResumesASuspendedContext() {
        assertEquals(1, resumesAfterGesture("suspended"))
    }

    @Test
    fun aGestureLeavesAPausedPlayerSilent() {
        assertEquals(0, resumesAfterGesture("suspended", paused = true), "what was left queued would play")
    }

    @Test
    fun aGestureLeavesARunningOrClosedContextAlone() {
        assertEquals(0, resumesAfterGesture("running"))
        assertEquals(0, resumesAfterGesture("closed"))
        assertEquals(0, resumesAfterGesture("suspended", closed = true))
    }
}

@JsFun("() => { const d = globalThis.document; if (d) d.dispatchEvent(new Event('pointerdown')); }")
private external fun dispatchPointerDown()

/** The real device's setup, its gesture listener and its close run in a browser; Node has no Web Audio. */
class WebAudioDeviceSmokeTest {
    @Test
    fun aRealDeviceTakesAGestureAndClosesCleanly() = kotlinx.coroutines.test.runTest {
        val device = WebAudioWorkletDevice.createOrNull(requestedChannels = 2, blockFrames = 512) ?: return@runTest
        dispatchPointerDown()
        device.suspendPlayback()
        dispatchPointerDown()
        device.close()
        dispatchPointerDown()
    }
}
