@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/**
 * A clock that reads the test scheduler's virtual time.
 *
 * This is what makes a whole playback session deterministic. The engine reads time through
 * [MonotonicClock] and waits through `delay`, so pointing both at one virtual clock means a session
 * runs through minutes of media in milliseconds and does the same thing every time. A [TestClock] moved
 * by hand is right for a pure timing rule; a session with five workers in it needs the clock and the
 * dispatcher to agree, which is what this does.
 */
internal class VirtualClock(private val scheduler: TestCoroutineScheduler) : MonotonicClock {
    override fun nanos(): Long = scheduler.currentTime * 1_000_000L
}

/**
 * One scripted playback session, wired the way a real one is wired.
 *
 * Everything the core touches is scripted and inspectable: the media, the device, the renderer, and the
 * clock. Every worker shares the test dispatcher, which is a cooperative single thread, so the engine's
 * confinement rules hold exactly as they do on real single-thread dispatchers, and nothing is timing
 * dependent.
 */
internal class CoreHarness(
    scope: TestScope,
    val script: MediaScript = MediaScript(),
    val faults: FaultPlan = FaultPlan.None,
    config: PlayerConfig = PlayerConfig(),
    val ledger: LeakLedger = LeakLedger(),
    val renderer: RecordingRenderer? = RecordingRenderer(),
) {
    val scheduler: TestCoroutineScheduler = scope.testScheduler
    val clock: VirtualClock = VirtualClock(scheduler)

    /** One trace shared by the device, the decoders and the cursor, so the seek order is observable. */
    val trace: ScriptTrace = ScriptTrace()
    val backend: ScriptedBackend = ScriptedBackend(script, ledger, faults, trace)
    val sink: ScriptedSink = ScriptedSink(faults = faults, trace = trace)
    val output: ScriptedOutput = ScriptedOutput(clock, sink)

    val core: PlaybackCore = PlaybackCore(
        config = config,
        backend = backend,
        output = output,
        dispatchers = PlaybackDispatchers.sharing(StandardTestDispatcher(scheduler)),
        closeDispatchers = false,
        // Under the test's own background lifetime, so a test that fails an assertion before it closes
        // still leaves no worker running. Without it, one failed assertion leaves five loops on the
        // scheduler and the test framework drains them for ever.
        parent = scope.backgroundScope.coroutineContext[Job],
    )

    /** The names of the handlers as they actually ran, once recording is switched on. */
    val handlerTrace: MutableList<String> = mutableListOf()

    /** Everything the session announced, in order. */
    val events: MutableList<PlayerEvent> = mutableListOf()

    /**
     * The device's own pull loop.
     *
     * In the background scope so it stops with the test rather than keeping it alive, and held here so a
     * campaign that runs a hundred sessions in one test can let each one go rather than accumulate a
     * hundred idle devices.
     */
    private val device: Job = scope.backgroundScope.launch { sink.runDevice(clock) }

    init {
        scope.backgroundScope.launch { core.events.collect { events += it } }
    }

    val source: ScriptedSource get() = backend.sessions.last().scriptedSource

    val session: ScriptedSession get() = backend.sessions.last()

    fun recordHandlers() {
        core.onHandlerRun = { handlerTrace += it }
    }

    fun stopRecordingHandlers() {
        core.onHandlerRun = null
    }

    /** Attaches the harness renderer, if it has one. */
    suspend fun attachRenderer() {
        renderer?.let { core.attachRenderer(it) }
    }

    suspend fun open(uri: String = "scripted://media") {
        core.open(MediaItem(uri))
    }

    /** Opens with the renderer already attached, which is what a real caller does. */
    suspend fun openWithRenderer(uri: String = "scripted://media") {
        attachRenderer()
        open(uri)
    }

    suspend fun close() {
        core.closeAndAwait()
        device.cancel()
    }

    /** Lets virtual time pass while the session runs. */
    suspend fun run(duration: Duration) {
        delay(duration)
    }
}
