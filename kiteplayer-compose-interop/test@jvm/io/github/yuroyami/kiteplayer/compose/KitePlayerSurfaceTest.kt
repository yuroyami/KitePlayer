package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first test this module has ever had, and the honest limit on what it can reach.
 *
 * **The JVM actual cannot be composed here at all**, which is worth writing down because it costs
 * an hour to rediscover. Since the desktop native view landed, `KitePlayerSurface` on JVM hosts a
 * `SwingPanel`, and that needs an interop container which only exists inside a real Compose
 * window; composing it here fails with "LocalInteropContainer not provided". That is the harness
 * being honest rather than the code being wrong, and the desktop surface is proved instead by the
 * sample driving a real window.
 *
 * What IS reachable is the placeholder that every platform without a native view composes, and
 * its sizing contract is worth pinning: a video hole that ignores the modifier it was given
 * breaks whatever layout it sits in, and the damage surfaces somewhere else entirely.
 *
 * The size is read through `onGloballyPositioned`, which reports what the composable was actually
 * measured and placed at. A first version of this file asked the test harness for the ROOT node's
 * size instead, and that was vacuous: it passed while asserting 320 by 180 in one case and 0 by 0
 * in another, and kept passing when the placeholder was mutated to ignore its constraints
 * entirely. Two tests asserting different sizes of the same thing and both passing is the tell.
 */
@OptIn(ExperimentalTestApi::class)
class KitePlayerSurfaceTest {

    @Test
    fun `the placeholder takes exactly the size it is given rather than collapsing`() =
        runComposeUiTest {
            var measured: IntSize? = null
            var expected: IntSize? = null
            setContent {
                with(LocalDensity.current) {
                    expected = IntSize(320.dp.roundToPx(), 180.dp.roundToPx())
                }
                EmptyKitePlayerSurface(
                    Modifier
                        .size(320.dp, 180.dp)
                        .onGloballyPositioned { measured = it.size },
                )
            }
            waitForIdle()
            assertEquals(expected, measured)
        }

    @Test
    fun `the placeholder claims no size of its own when it is given none`() = runComposeUiTest {
        var measured: IntSize? = null
        setContent {
            EmptyKitePlayerSurface(Modifier.onGloballyPositioned { measured = it.size })
        }
        waitForIdle()
        // A hole with no instructions must not invent a size: a caller who wanted one would have
        // said so, and inventing one is how a video view silently pushes a layout around.
        assertEquals(IntSize(0, 0), measured)
    }
}
