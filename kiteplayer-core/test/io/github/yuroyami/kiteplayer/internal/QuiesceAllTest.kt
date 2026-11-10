package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Parking the lanes costs one deadline however many are stuck (#712). Each lane was asked and
 * waited for in turn, each with a deadline of its own, so a release or a seek with the demux and
 * both decode lanes stuck waited about six seconds before it could cancel anything.
 */
class QuiesceAllTest {

    @Test
    fun threeStuckLanesCostOneDeadline() = runTest {
        val lanes = listOf(Worker("demux"), Worker("video decode"), Worker("audio decode"))
        val started = currentTime
        val unparked = quiesceAll(lanes, 2.seconds)
        assertEquals(2_000L, currentTime - started, "the park waited past one deadline")
        assertEquals(lanes, unparked)
    }

    @Test
    fun lanesThatParkAreNotNamedAndOneStuckLaneStillCostsOneDeadline() = runTest {
        val parks = Worker("parks")
        val stuck = Worker("stuck")
        val alsoParks = Worker("also parks")
        // A lane parks at its next checkpoint once it sees the request.
        for (lane in listOf(parks, alsoParks)) launch { lane.checkpoint() }
        val started = currentTime
        val unparked = quiesceAll(listOf(parks, stuck, alsoParks), 2.seconds)
        assertEquals(listOf(stuck), unparked)
        assertEquals(2_000L, currentTime - started)
        listOf(parks, alsoParks).forEach { it.release(it.epoch) }
    }

    @Test
    fun lanesThatAllParkCostNoWait() = runTest {
        val lanes = listOf(Worker("a"), Worker("b"))
        for (lane in lanes) launch { lane.checkpoint() }
        val started = currentTime
        assertEquals(emptyList(), quiesceAll(lanes, 2.seconds))
        assertEquals(0L, currentTime - started)
        lanes.forEach { it.release(it.epoch) }
    }
}
