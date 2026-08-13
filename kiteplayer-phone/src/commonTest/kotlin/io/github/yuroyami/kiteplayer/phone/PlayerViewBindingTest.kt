package io.github.yuroyami.kiteplayer.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerViewBindingTest {

    private val log = mutableListOf<String>()
    private var nextRenderer = 0

    private fun binding(rendererNeedsSurface: Boolean = true) = PlayerViewBinding<String, Int>(
        createRenderer = { (nextRenderer++).also { log += "create $it" } },
        attach = { player, renderer -> log += "attach $player $renderer" },
        detach = { player -> log += "detach $player" },
        close = { renderer -> log += "close $renderer" },
        rendererNeedsSurface = rendererNeedsSurface,
    )

    @Test
    fun aPlayerAloneBuildsNothing() {
        val b = binding()
        b.setPlayer("a")
        assertEquals(emptyList(), log)
        assertNull(b.activeRenderer)
    }

    @Test
    fun aSurfaceAloneBuildsNothing() {
        val b = binding()
        b.surfaceReady()
        assertEquals(emptyList(), log)
        assertNull(b.activeRenderer)
    }

    @Test
    fun bothPreconditionsBuildAndAttachInOrder() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        assertEquals(listOf("create 0", "attach a 0"), log)
        assertEquals(0, b.activeRenderer)
    }

    @Test
    fun theOrderOfPreconditionsDoesNotMatter() {
        val b = binding()
        b.surfaceReady()
        b.setPlayer("a")
        assertEquals(listOf("create 0", "attach a 0"), log)
    }

    @Test
    fun surfaceGoneClosesBeforeDetaching() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        log.clear()
        b.surfaceGone()
        assertEquals(listOf("close 0", "detach a"), log)
        assertNull(b.activeRenderer)
    }

    @Test
    fun surfaceGoneIsIdempotent() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        b.surfaceGone()
        log.clear()
        b.surfaceGone()
        assertEquals(emptyList(), log)
    }

    @Test
    fun surfaceReadyIsIdempotent() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        log.clear()
        b.surfaceReady()
        assertEquals(emptyList(), log)
    }

    @Test
    fun reassigningTheSamePlayerDoesNothing() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        log.clear()
        b.setPlayer("a")
        assertEquals(emptyList(), log)
    }

    @Test
    fun aPlayerSwapMidSurfaceTearsDownTheOldPairFirst() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        log.clear()
        b.setPlayer("b")
        assertEquals(listOf("close 0", "detach a", "create 1", "attach b 1"), log)
        assertEquals(1, b.activeRenderer)
    }

    @Test
    fun clearingThePlayerClosesAndDetaches() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        log.clear()
        b.setPlayer(null)
        assertEquals(listOf("close 0", "detach a"), log)
        assertNull(b.activeRenderer)
    }

    @Test
    fun aSurfaceReturningRebuildsForTheBoundPlayer() {
        val b = binding()
        b.setPlayer("a")
        b.surfaceReady()
        b.surfaceGone()
        log.clear()
        b.surfaceReady()
        assertEquals(listOf("create 1", "attach a 1"), log)
    }

    @Test
    fun settingAPlayerAfterSurfaceLossBuildsNothing() {
        val b = binding()
        b.surfaceReady()
        b.surfaceGone()
        log.clear()
        b.setPlayer("a")
        assertEquals(emptyList(), log)
    }

    @Test
    fun aHeadlessCapableRendererAttachesBeforeTheSurfaceExists() {
        val b = binding(rendererNeedsSurface = false)
        b.setPlayer("a")
        assertEquals(listOf("create 0", "attach a 0"), log)
        assertEquals(0, b.activeRenderer)
    }

    @Test
    fun surfaceLossDoesNotReplaceAHeadlessCapableRenderer() {
        val b = binding(rendererNeedsSurface = false)
        b.setPlayer("a")
        b.surfaceReady()
        log.clear()
        b.surfaceGone()
        b.surfaceReady()
        assertEquals(emptyList(), log)
        assertEquals(0, b.activeRenderer)
    }

    @Test
    fun clearingThePlayerAfterHeadlessSurfaceLossClosesAndDetaches() {
        val b = binding(rendererNeedsSurface = false)
        b.setPlayer("a")
        b.surfaceReady()
        b.surfaceGone()
        log.clear()

        b.setPlayer(null)

        assertEquals(listOf("close 0", "detach a"), log)
        assertNull(b.activeRenderer)
    }

    @Test
    fun swappingPlayersWithoutASurfaceReplacesTheHeadlessRenderer() {
        val b = binding(rendererNeedsSurface = false)
        b.setPlayer("a")
        log.clear()

        b.setPlayer("b")

        assertEquals(listOf("close 0", "detach a", "create 1", "attach b 1"), log)
        assertEquals(1, b.activeRenderer)
    }
}
