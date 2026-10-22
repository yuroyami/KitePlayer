package io.github.yuroyami.kiteplayer.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.nw_path_status_invalid
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/** Apple's network status (#461): the system's path monitor, reported when it changes. */
class PathMonitorNetworkStatusTest {

    @Test
    fun aPathIsReportedWhenItChangesAndUntilItCloses() {
        val seen = mutableListOf<Boolean>()
        val reports = PathReports { seen += it }
        reports.report(nw_path_status_satisfied)
        reports.report(nw_path_status_satisfied)
        assertEquals(listOf(true), seen, "the network now, once")
        reports.report(nw_path_status_unsatisfied)
        reports.report(nw_path_status_invalid)
        assertEquals(listOf(true, false), seen, "a network that went")
        reports.report(nw_path_status_satisfiable)
        assertEquals(listOf(true, false, true), seen, "a path the system can bring up is a network")
        reports.close()
        reports.report(nw_path_status_unsatisfied)
        assertEquals(listOf(true, false, true), seen, "nothing once closed")
    }

    /** The real monitor of this machine: it reports the path it has now, soon after it starts. */
    @Test
    fun theSystemMonitorReportsTheNetworkNow() = runBlocking {
        val seen = Channel<Boolean>(Channel.UNLIMITED)
        val watch = PathMonitorNetworkStatus.watch { seen.trySend(it) }
        val first = withTimeoutOrNull(10.seconds) { seen.receive() }
        watch.close()
        assertNotNull(first, "no path was reported")
        Unit
    }

    @Test
    fun theTransportGivesTheAppleStatus() {
        assertSame(PathMonitorNetworkStatus, KtorMediaIoResolverProvider().networkStatus())
    }
}
