@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * An engine made before the web module lands keeps bounded current state, hands only that state to
 * the module when it lands, and falls back when the module does not land (#291).
 *
 * Each case starts from a fresh page with a load promise it controls and a fake module that logs
 * every call. The real module goes back afterwards, so the other web tests still find it.
 */
class LibassWebPendingTest {

    private var realModule: JsAny? = null

    @BeforeTest
    fun freshPage() {
        realModule = KiteLibassWeb.module
        KiteLibassWeb.resetForTesting()
    }

    @AfterTest
    fun restorePage() {
        KiteLibassWeb.resetForTesting()
        realModule?.let(KiteLibassWeb::attach)
    }

    private fun frame(width: Int) = TypesetFrame(width = width, height = 720, videoWidth = 640, videoHeight = 360)

    @Test
    fun aModuleThatLandsLateGetsOnlyTheCurrentState() = runTest {
        val load = deferredPromise()
        KiteLibassWeb.importModule = { promiseOf(load) }
        val typesetter = LibassTypesetter(assertNotNull(LibassEngine.open()))
        typesetter.addFont("Sans.ttf", ByteArray(64))
        typesetter.openTrack(ByteArray(10))
        repeat(50) { typesetter.addEvent(ByteArray(8), it * 100L, 50) }
        // A second track replaces the first, and the first track's events with it.
        typesetter.openTrack(ByteArray(20))
        repeat(20) { typesetter.addEvent(ByteArray(8), it * 100L, 50) }
        typesetter.clearEvents()
        repeat(5) { typesetter.addEvent(ByteArray(8), it * 100L, 50) }
        repeat(1_000) { assertNull(typesetter.render(it.toLong(), frame(1280 + it)), "a pending engine drew") }

        val fake = fakeLibassModule()
        resolvePromise(load, fake)
        promiseOf(load).await<JsAny>()
        assertNull(typesetter.render(2_000, frame(1920)))

        val calls = callsOf(fake).split(',')
        assertEquals(1, calls.count { it.startsWith("font:") }, "fonts replayed: $calls")
        assertEquals(listOf("open_track:20"), calls.filter { it.startsWith("open_track") }, "only the last track opens")
        assertEquals(1, calls.count { it == "clear" }, "the clear after the track still reaches it")
        assertEquals(5, calls.count { it.startsWith("event:") }, "only the events after the clear are current")
        // One frame is the kept one and one is this render's own, not one per pending render.
        assertEquals(2, calls.count { it.startsWith("frame:") }, "frames replayed: ${calls.count { it.startsWith("frame:") }}")
        typesetter.close()
    }

    @Test
    fun aModuleThatNeverLandsFallsBackAtTheDeadline() = runTest {
        KiteLibassWeb.importModule = { neverResolvingPromise() }
        KiteLibassWeb.loadDeadline = Duration.ZERO
        val typesetter = LibassTypesetter(assertNotNull(LibassEngine.open()))
        assertFailsWith<IllegalStateException>("a load past its deadline must reach the fallback") {
            typesetter.render(0, frame(1280))
        }
        assertNull(LibassEngine.open(), "the next player must not wait for the same load again")
        typesetter.close()
    }

    @Test
    fun waitingDataOverTheBudgetFallsBack() = runTest {
        KiteLibassWeb.importModule = { neverResolvingPromise() }
        val typesetter = LibassTypesetter(assertNotNull(LibassEngine.open()))
        val big = ByteArray((LibassEngine.PENDING_BUDGET_BYTES + 1).toInt())
        assertFailsWith<IllegalStateException>("data over the budget must reach the fallback") {
            typesetter.addFont("Huge.ttf", big)
        }
        assertFailsWith<IllegalStateException>("the refusal must stand on the next render") {
            typesetter.render(0, frame(1280))
        }
        typesetter.close()
    }
}

@JsFun("() => new Promise(() => {})")
private external fun neverResolvingPromise(): Promise<JsAny>

@JsFun("() => { const d = {}; d.promise = new Promise(r => { d.resolve = r; }); return d; }")
private external fun deferredPromise(): JsAny

@JsFun("(d) => d.promise")
private external fun promiseOf(deferred: JsAny): Promise<JsAny>

@JsFun("(d, v) => { d.resolve(v); }")
private external fun resolvePromise(deferred: JsAny, value: JsAny)

@JsFun("(m) => m.calls.join(',')")
private external fun callsOf(module: JsAny): String

// Just enough of kiteass.mjs to be attached and replayed into: every call is logged by name.
@JsFun(
    """() => {
        const calls = [];
        const heap = new Uint8Array(1 << 20);
        let top = 16;
        return {
            calls,
            HEAPU8: heap,
            _kass_library_version: () => 0x01700000,
            _kass_open: () => { calls.push('open'); return 1; },
            _kass_close: () => { calls.push('close'); },
            _kass_alloc: (n) => { if (top + n > heap.length) top = 16; const p = top; top += n; return p; },
            _kass_free: () => {},
            _kass_open_track: (s, p, n) => { calls.push('open_track:' + n); return 1; },
            _kass_open_document: (s, p, n) => { calls.push('open_document:' + n); return 1; },
            _kass_add_event: (s, p, n, start) => { calls.push('event:' + start); },
            _kass_clear_events: () => { calls.push('clear'); },
            _kass_add_font: (s, name, p, n) => { calls.push('font:' + n); },
            _kass_set_frame: (s, fw, fh) => { calls.push('frame:' + fw + 'x' + fh); },
            _kass_render: () => { calls.push('render'); return 0; },
            _kass_packed_ptr: () => 0,
            _kass_packed_size: () => 0,
        };
    }""",
)
private external fun fakeLibassModule(): JsAny
