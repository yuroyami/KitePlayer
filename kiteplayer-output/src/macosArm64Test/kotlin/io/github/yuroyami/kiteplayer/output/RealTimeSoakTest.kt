@file:OptIn(
    ExperimentalForeignApi::class,
    RawRingApi::class,
    NativeRuntimeApi::class,
    ObsoleteWorkersApi::class,
    ExperimentalStdlibApi::class,
)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.AudioToolbox.AURenderCallbackStruct
import platform.AudioToolbox.AudioComponentDescription
import platform.AudioToolbox.AudioComponentFindNext
import platform.AudioToolbox.AudioComponentInstance
import platform.AudioToolbox.AudioComponentInstanceDispose
import platform.AudioToolbox.AudioComponentInstanceNew
import platform.AudioToolbox.AudioComponentInstanceVar
import platform.AudioToolbox.AudioOutputUnitStart
import platform.AudioToolbox.AudioOutputUnitStop
import platform.AudioToolbox.AudioUnitInitialize
import platform.AudioToolbox.AudioUnitSetProperty
import platform.AudioToolbox.AudioUnitUninitialize
import platform.AudioToolbox.kAudioUnitManufacturer_Apple
import platform.AudioToolbox.kAudioUnitProperty_SetRenderCallback
import platform.AudioToolbox.kAudioUnitProperty_StreamFormat
import platform.AudioToolbox.kAudioUnitScope_Input
import platform.AudioToolbox.kAudioUnitSubType_DefaultOutput
import platform.AudioToolbox.kAudioUnitType_Output
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.darwin.OSStatus
import platform.posix.getenv
import kotlin.concurrent.AtomicLong
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The supervised device run: assertion 3 of plan section 15.2 B1.8, with the negative control that has
 * to fail, plus assertion 4 as corroboration.
 *
 * ### These cases do not run in the ordinary gate, on purpose
 *
 * Each one opens a real device, makes sound and takes minutes. They are gated on KPRT_DEVICE_SOAK being
 * set, and register item B1-24 records why that matters: KitePlayer has no CI, so this is a serial
 * human-supervised run on one machine, and a gate that silently spent twenty five minutes in the middle
 * of an ordinary test run would be turned off within a week. The gate command is in the report and in
 * the plan.
 *
 *     KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
 *       ./gradlew :kiteplayer-output:macosArm64Test --tests '*RealTimeSoakTest*' --rerun-tasks -i
 *
 * ### Where the strength of this evidence sits, and where it does not
 *
 * Plan section 15.3 grades this level 1 for macOS arm64 in debug, on one machine, with its numbers
 * recorded, and nothing more. It is NOT release-mode performance qualification; B10 owns that. It is
 * also the third of the four assertions in the order of authority the plan fixes, behind the render
 * audit and the interposed C test, because both of those are deterministic and this one is a
 * measurement of one run on one machine. No report may present it as the strongest of the four.
 *
 * ### Which numbers describe the callback, and which describe this test
 *
 * `worstCallbackNanos`, `segmentGiveups` and `zeroFilledCallbacks` are facts about the code that runs
 * on the device's thread. `underruns` is not: it says the ring was empty when the device asked, which
 * says the feeder was late, and the feeder in this file is managed Kotlin under pressure this file
 * manufactured on purpose. At ten minutes that distinction stops being theoretical. Two runs, measured:
 * 21 underruns against 51,679 callbacks with 79,997 collections, then 0 against 51,678 with 94,009. The
 * worst callback body in the same two runs was 9,083 ns and 10,458 ns against a 5,333,333 ns budget,
 * which is the number that did not vary.
 *
 * That asymmetry is what B1.8 built on purpose. A late feeder costs nothing until the ring's half second
 * of audio runs out; a late callback is an audible gap with no recovery. So the callback's three numbers
 * are asserted exactly, the underrun count is bounded as a ratio because zero holds on one run in two,
 * and the zero-underrun promise is carried where it belongs: `RealTimeMediaSoakTest`, which played real
 * media through this same ring and callback for ten minutes under the same pressure and reported zero.
 *
 * ### What the negative control is for, and what it does NOT attribute
 *
 * An assertion suite that has never rejected anything is not evidence. The control here is the
 * arrangement B1.8 removed: a real device whose render callback is a Kotlin lambda reached through a
 * `StableRef`, allocating on the real-time thread. It must break the property the positive case
 * asserts, and it does.
 *
 * What it must not be read as is an attribution to the garbage collector, and that correction came from
 * the independent verification of B1.8, which decomposed the control on this machine. Measured, four
 * arrangements of 0.4 minutes each against the same 5,333,333 ns budget: allocating and pressured
 * 11,613,208 ns worst over 76 callbacks, allocating and unpressured 10,541,834 over 2, non-allocating
 * and pressured 18,691,250 over 80, non-allocating and unpressured 10,467,500 over 11. So the
 * arrangement misses the deadline with the collector pressure removed AND with the per-call allocation
 * removed, and the pressure changes how OFTEN it misses rather than whether it can. The plan's own
 * stop-the-world figures, 63 to 256 microseconds, are 40 to 160 times too small to explain a 10
 * millisecond outlier, so the pause was never the mechanism; a managed callback doing managed work per
 * sample in a debug build is.
 *
 * That is why this file runs the control twice, once with the pressure and once without, and prints
 * both. At the full ten minutes on this machine the pair reads: pressured, worst 80,442,542 ns with
 * 1,608 of 51,521 callbacks over the budget and 103,877 collections; unpressured, worst 10,669,375 ns
 * with 101 of 51,681 over it and 624 collections. So removing the pressure removed 99.4 percent of the
 * collections and the arrangement still missed the deadline 101 times, by a whole device period.
 *
 * The reportable statement is that pair against the positive case, quoted as a ratio rather than as an
 * order of magnitude because the ratio is what was measured: the C callback's worst body was 9,083 ns,
 * which is 0.17 percent of the budget, with 79,997 collections in its own ten minutes and nothing over
 * budget at all.
 */
class RealTimeSoakTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** Half the device period at 512 frames and 48 kHz, which is B10's budget: 5,333,333 ns. */
    private val budgetNanos = 512L * 1_000_000_000L / 2 / 48_000

    private val enabled: Boolean get() = getenv("KPRT_DEVICE_SOAK") != null

    private val minutes: Double
        get() = getenv("KPRT_DEVICE_SOAK_MINUTES")?.toKString()?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0

    @Test
    fun `the C callback holds its deadline through ten minutes of hard collector pressure`() = runBlocking {
        if (!enabled) {
            println("RealTimeSoakTest: skipped, set KPRT_DEVICE_SOAK=1 to run the supervised device run")
            return@runBlocking
        }

        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { negotiated -> negotiated.sampleRate / 2 }
        val ring = handoff.ringPointer()
        val pressure = CollectorPressure()

        try {
            fillRing(ring, 0)
            sink.start()
            pressure.start()
            val outcome = feedFor(ring, minutes)
            sink.stop()
            pressure.stop()

            println(
                "RealTimeSoakTest: C callback, ${minutes} minutes: callbacks=${sink.callbacks} " +
                    "worstCallbackNanos=${sink.worstCallbackNanos} budget=$budgetNanos " +
                    "worstAsPercentOfBudget=${sink.worstCallbackNanos * 100.0 / budgetNanos} " +
                    "underruns=${ringUnderruns(ring)} segmentGiveups=${ringSegmentGiveups(ring)} " +
                    "zeroFilled=${sink.zeroFilledCallbacks} estimatedAnchors=${sink.estimatedAnchors} " +
                    "framesFed=${outcome.framesFed} collections=${pressure.collections()} " +
                    "allocations=${pressure.allocations()}",
            )

            assertTrue(sink.callbacks > 1000, "too few callbacks to mean anything: ${sink.callbacks}")
            assertTrue(pressure.collections() > 100, "the collector was not under pressure: ${pressure.collections()}")

            // The callback's own properties, asserted exactly. Each of these three is a fact about the
            // code that runs on the device's thread and about nothing else.
            assertEquals(0L, ringSegmentGiveups(ring), "the real-time segment walk must never have given up")
            assertEquals(0L, sink.zeroFilledCallbacks, "the ring must have been present for every callback")
            assertTrue(
                sink.worstCallbackNanos in 1 until budgetNanos,
                "the worst callback body was ${sink.worstCallbackNanos} ns against a budget of $budgetNanos ns",
            )

            // The underrun counter is NOT one of them, and the difference is the whole point of B1.8.
            // An underrun says the ring was empty when the device asked, which says the FEEDER was
            // late. The feeder here is managed Kotlin doing a scalar copy through cinterop, so it is a
            // mutator this test has deliberately arranged to be stopped hundreds of times a second,
            // and the ring it fills holds half a second of audio.
            //
            // Why this is a bound and not an equality, measured rather than assumed. Two ten minute
            // runs on this machine: one reported 21 underruns against 51,679 callbacks with 79,997
            // collections, the next reported 0 against 51,678 with 94,009. So zero is what a good run
            // gives and it is not what this arrangement guarantees, and asserting an equality that
            // holds on one run in two would make a gate that flakes rather than a gate that checks.
            // The worst callback body in those same two runs was 9,083 ns and 10,458 ns, both about
            // two tenths of one percent of the budget, which is the number that did not vary.
            //
            // So the bound is a ratio, and it is still falsifiable: a real-time path that stopped
            // reading, or a ring that lost its samples, would starve on a far larger share than one
            // callback in a thousand.
            //
            // Where the zero-underrun promise lives, and the measurement that moved it there.
            // `RealTimeMediaSoakTest` played real media through this same C ring and C callback for ten
            // minutes under the same manufactured pressure and reported ZERO underruns over sixty loops
            // and 28,771,328 frames, with a ring of 200 milliseconds rather than this test's 500. So
            // the ring, the callback and the collector cannot be what starved this one: the same three
            // ran that one with a smaller buffer and starved nothing. The likely difference is the
            // feeder, since this file's writes the ring one sample at a time through cinterop while the
            // engine's pins its array once and memcpys, and that reading is source level rather than
            // isolated: nobody swapped one feeder for the other and re-measured.
            val underruns = ringUnderruns(ring)
            assertTrue(
                underruns * 1_000 < sink.callbacks,
                "the ring ran dry on $underruns of ${sink.callbacks} callbacks, which is more than one " +
                    "in a thousand. Under this test's manufactured collector pressure a late managed " +
                    "feeder is expected and a few are normal; this many means the real-time reader or " +
                    "the ring itself lost audio, not that the feeder was slow.",
            )
        } finally {
            sink.close()
            pressure.stop()
        }
    }

    @Test
    fun `the same run against a Kotlin callback is the negative control and must fail`() = runBlocking {
        if (!enabled) {
            println("RealTimeSoakTest: skipped, set KPRT_DEVICE_SOAK=1 to run the negative control")
            return@runBlocking
        }

        // Two arms of the same arrangement, and the second one is the correction the independent
        // verification asked for. Both are the shape B1.8 removed: a real device, a Kotlin lambda reached
        // through a StableRef on the real-time thread, and an allocation per callback. They differ only in
        // whether the collector is under manufactured pressure, so the pair says how much of the deadline
        // miss the pressure accounts for instead of leaving that to be assumed.
        val pressured = runControl(pressured = true)
        val unpressured = runControl(pressured = false)

        for (arm in listOf(pressured, unpressured)) {
            println(
                "RealTimeSoakTest: NEGATIVE CONTROL, Kotlin callback, ${arm.label}, ${minutes} minutes: " +
                    "callbacks=${arm.callbacks} worstCallbackNanos=${arm.worstNanos} budget=$budgetNanos " +
                    "overBudget=${arm.overBudget} collections=${arm.collections} allocations=${arm.allocations}",
            )
        }

        assertTrue(pressured.callbacks > 1000, "the control device did not run: ${pressured.callbacks}")
        assertTrue(unpressured.callbacks > 1000, "the unpressured arm did not run: ${unpressured.callbacks}")
        assertTrue(
            pressured.collections > 100,
            "the pressured arm's collector was not under pressure: ${pressured.collections} collections",
        )

        // The control MUST break the property the positive case asserts. If this line ever passes,
        // assertion 3 has stopped being able to reject anything and the whole run is worthless as
        // evidence, which is why the failure message says so rather than just reporting a number.
        //
        // Asserted on the COUNT of over-budget callbacks and not only on the worst one, because the count
        // is what a listener would hear: one late buffer in a ten minute run is a number, and dozens of
        // them are a defect.
        assertTrue(
            pressured.overBudget > 0 && pressured.worstNanos >= budgetNanos,
            "the negative control did NOT exceed the budget: worst was ${pressured.worstNanos} ns against " +
                "$budgetNanos ns over ${pressured.callbacks} callbacks, with ${pressured.overBudget} " +
                "over budget. That does not make the Kotlin callback safe; it makes this control too weak " +
                "to prove assertion 3 can reject, and assertion 3's own pass is worth nothing until the " +
                "control is made to bite.",
        )

        // Deliberately NOT an assertion. The unpressured arm exceeding the budget too is what the
        // verification measured and it is information, not a failure; requiring it would turn a fast run
        // of a managed callback into a red gate, and this file may not assert a mechanism it has not
        // isolated. The pair of printed lines is the evidence, and the log entry carries the reading.
        if (unpressured.worstNanos < budgetNanos) {
            println(
                "RealTimeSoakTest: NOTE, the unpressured arm stayed inside the budget this run " +
                    "(${unpressured.worstNanos} ns against $budgetNanos ns). Earlier runs on this machine " +
                    "did not, so the collector pressure is not the whole mechanism and neither reading " +
                    "attributes the miss on its own.",
            )
        } else {
            println(
                "RealTimeSoakTest: the unpressured arm ALSO exceeded the budget " +
                    "(${unpressured.worstNanos} ns against $budgetNanos ns, ${unpressured.overBudget} " +
                    "callbacks over it, ${unpressured.collections} collections against " +
                    "${pressured.collections} for the pressured arm). So the collector pause is not the " +
                    "mechanism: the managed callback arrangement misses the deadline without it, and the " +
                    "pressure multiplies how often rather than whether.",
            )
        }
    }

    private class ControlOutcome(
        val label: String,
        val callbacks: Long,
        val worstNanos: Long,
        val overBudget: Long,
        val collections: Long,
        val allocations: Long,
    )

    private suspend fun runControl(pressured: Boolean): ControlOutcome {
        val control = KotlinCallbackSink(format)
        val pressure = if (pressured) CollectorPressure() else null
        // Measured here as well as inside the instrument, because the unpressured arm has no
        // instrument and its collection count is the number that says the pressure is really absent.
        val epochBefore = GC.lastGCInfo?.epoch?.toLong() ?: 0L
        try {
            control.open()
            control.start()
            pressure?.start()
            val until = AppleHostClock.nanos() + (minutes * 60_000_000_000L).toLong()
            while (AppleHostClock.nanos() < until) delay(50)
            control.stop()
            pressure?.stop()
            return ControlOutcome(
                label = if (pressured) "under collector pressure" else "with no collector pressure",
                callbacks = control.callbacks.value,
                worstNanos = control.worstCallbackNanos.value,
                overBudget = control.overBudget.value,
                collections = (GC.lastGCInfo?.epoch?.toLong() ?: 0L) - epochBefore,
                allocations = pressure?.allocations() ?: 0L,
            )
        } finally {
            control.close()
            pressure?.stop()
        }
    }

    @Test
    fun `the Kotlin heap does not drift across ten minutes of playback`() = runBlocking {
        if (!enabled) {
            println("RealTimeSoakTest: skipped, set KPRT_DEVICE_SOAK=1 to run the heap drift check")
            return@runBlocking
        }

        // Assertion 4, and corroboration only. Plan section 15.2 B1.8 is explicit that this is level 5 and
        // never the gate, for a reason this test cannot escape: it cannot attribute growth to the callback
        // rather than to anything else alive in the process. A flat heap here is consistent with a callback
        // that allocates nothing and equally consistent with one that allocates and is collected.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { negotiated -> negotiated.sampleRate / 2 }
        try {
            fillRing(handoff.ringPointer(), 0)
            sink.start()
            GC.collect()
            val before = heapObjectBytes()
            feedFor(handoff.ringPointer(), minutes)
            GC.collect()
            val after = heapObjectBytes()
            sink.stop()

            println(
                "RealTimeSoakTest: heap drift over ${minutes} minutes: before=$before after=$after " +
                    "delta=${after - before} callbacks=${sink.callbacks} (level 5 corroboration only)",
            )
            assertTrue(before > 0 && after > 0, "the runtime reported no heap size, so nothing was measured")
            // A generous bound, because the number being bounded is the whole process and not the callback.
            assertTrue(
                after - before < 4L * 1024 * 1024,
                "the heap grew by ${after - before} bytes across ${sink.callbacks} callbacks",
            )
        } finally {
            sink.close()
        }
    }

    // ---- Feeding, which is what the engine's audio feeder does ----

    private class FeedOutcome(val framesFed: Long)

    /**
     * Keeps the ring full for [forMinutes], at the pace the device drains it.
     *
     * A synthetic feeder rather than a decoder, and the split is deliberate: this file is about the
     * callback, so it holds the callback's own instruments, and the whole shipped path with real media
     * behind it is `kiteplayer-ffmpeg`'s `RealTimeMediaSoakTest`. Both are part of assertion 3 and the
     * report names both commands.
     */
    private suspend fun feedFor(ring: CPointer<cnames.structs.kprt_ring>, forMinutes: Double): FeedOutcome {
        val until = AppleHostClock.nanos() + (forMinutes * 60_000_000_000L).toLong()
        var written = ringConsumed(ring) + ringBuffered(ring)
        while (AppleHostClock.nanos() < until) {
            val accepted = feedRing(ring, 2_048, written, if (written == 0L) 0L else null)
            if (accepted == 0) {
                delay(2)
                continue
            }
            written += accepted
        }
        return FeedOutcome(framesFed = written)
    }

    private fun heapObjectBytes(): Long =
        GC.lastGCInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes?.toLong() ?: 0L

    /**
     * A Kotlin thread allocating hard with the collector's target heap pinned low, so collections happen
     * hundreds of times a second.
     *
     * This is the pressure plan section 15.2 B1.8 assertion 3 asks for. `GC.autotune = false` is what
     * makes `targetHeapBytes` stick; with autotuning on, the runtime raises the target as the process
     * allocates and the pressure disappears halfway through the run.
     */
    private class CollectorPressure {
        private val worker = Worker.start(name = "kprt-collector-pressure")
        private val running = AtomicLong(0)
        private val allocated = AtomicLong(0)
        private var previousAutotune = true
        private var previousTarget = 0L
        private var epochAtStart = 0L

        fun start() {
            epochAtStart = GC.lastGCInfo?.epoch?.toLong() ?: 0L
            previousAutotune = GC.autotune
            previousTarget = GC.targetHeapBytes
            GC.autotune = false
            GC.targetHeapBytes = 1L * 1024 * 1024
            running.value = 1
            val state = this
            worker.executeAfter(0L) { state.churn() }
        }

        private fun churn() {
            while (running.value == 1L) {
                // Objects, not one big array: what the collector has to trace is the object graph, and a
                // graph of many small nodes is the shape a managed callback would add to.
                var head: Node? = null
                repeat(2_000) { index -> head = Node(index, head) }
                allocated.addAndGet(2_000)
                if (head?.value == -1) println("unreachable, and here to stop the loop being optimised away")
            }
        }

        private class Node(val value: Int, val next: Node?)

        /**
         * Collections during this instrument's own life, not since the process started.
         *
         * `GC.lastGCInfo.epoch` is a process-cumulative counter, and reporting it raw is how a number
         * becomes a lie by accident. Measured on the first ten minute run of this file: the positive
         * case printed 79,997, the pressured control printed 183,874 and the unpressured arm printed
         * 184,498, which reads as though the arm with no pressure had collected 184,498 times. Its own
         * share was 624. The delta is what the report needs, so the delta is what this returns.
         */
        fun collections(): Long = (GC.lastGCInfo?.epoch?.toLong() ?: 0L) - epochAtStart

        fun allocations(): Long = allocated.value

        fun stop() {
            if (running.value == 0L) return
            running.value = 0
            worker.requestTermination(processScheduledJobs = false)
            GC.autotune = previousAutotune
            if (previousTarget > 0) GC.targetHeapBytes = previousTarget
        }
    }

    /**
     * The negative control's device: a real AudioUnit whose render callback is a Kotlin lambda.
     *
     * This is deliberately the shape `CoreAudioSink` had before B1.8, including the `StableRef` the
     * callback dereferences on its first instruction and an allocation inside the callback body. It is
     * test-only and nothing in the library links it.
     */
    private class KotlinCallbackSink(private val format: AudioFormat) {
        private var unit: AudioComponentInstance? = null
        private var selfRef: StableRef<KotlinCallbackSink>? = null
        private var running = false

        val callbacks = AtomicLong(0)
        val worstCallbackNanos = AtomicLong(0)
        val overBudget = AtomicLong(0)
        private val budgetNanos = 512L * 1_000_000_000L / 2 / 48_000

        fun open() {
            val instance = memScoped {
                val description = alloc<AudioComponentDescription>().apply {
                    componentType = kAudioUnitType_Output
                    componentSubType = kAudioUnitSubType_DefaultOutput
                    componentManufacturer = kAudioUnitManufacturer_Apple
                }
                val component = AudioComponentFindNext(null, description.ptr)
                    ?: error("no default output component")
                val holder = alloc<AudioComponentInstanceVar>()
                check(AudioComponentInstanceNew(component, holder.ptr) == 0) { "AudioComponentInstanceNew" }
                holder.value ?: error("null instance")
            }
            val ref = StableRef.create(this)
            selfRef = ref
            memScoped {
                val bytesPerFrame = (format.channels * 4).toUInt()
                val asbd = alloc<AudioStreamBasicDescription>().apply {
                    mSampleRate = format.sampleRate.toDouble()
                    mFormatID = kAudioFormatLinearPCM
                    mFormatFlags = kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked
                    mBitsPerChannel = 32u
                    mChannelsPerFrame = format.channels.toUInt()
                    mFramesPerPacket = 1u
                    mBytesPerFrame = bytesPerFrame
                    mBytesPerPacket = bytesPerFrame
                }
                check(
                    AudioUnitSetProperty(
                        instance, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0u,
                        asbd.ptr, sizeOf<AudioStreamBasicDescription>().toUInt(),
                    ) == 0,
                ) { "set stream format" }

                val callback = alloc<AURenderCallbackStruct>().apply {
                    inputProc = staticCFunction { refCon, _, _, _, frames, data ->
                        // The first instruction is the one register item B1-17 is about: a StableRef
                        // dereference, which makes this thread a Kotlin mutator the collector must stop.
                        val sink = refCon?.asStableRef<KotlinCallbackSink>()?.get()
                            ?: return@staticCFunction 0
                        val buffers = data?.pointed ?: return@staticCFunction 0
                        sink.renderInKotlin(frames.toInt(), buffers.mBuffers[0].mData?.reinterpret())
                    }
                    inputProcRefCon = ref.asCPointer()
                }
                check(
                    AudioUnitSetProperty(
                        instance, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0u,
                        callback.ptr, sizeOf<AURenderCallbackStruct>().toUInt(),
                    ) == 0,
                ) { "set render callback" }
            }
            check(AudioUnitInitialize(instance) == 0) { "AudioUnitInitialize" }
            unit = instance
        }

        private fun renderInKotlin(frames: Int, destination: CPointer<FloatVar>?): OSStatus {
            val entered = AppleHostClock.nanos()
            if (destination != null) {
                // A managed allocation on the real-time thread, which is what a Kotlin callback does
                // whether it means to or not: before B1.8 the shipped one made up to five transient
                // cinterop view objects per call, and a debug build allocated every one of them.
                val scratch = FloatArray(frames * format.channels)
                for (i in scratch.indices) destination[i] = scratch[i]
            }
            val spent = AppleHostClock.nanos() - entered
            if (spent > worstCallbackNanos.value) worstCallbackNanos.value = spent
            if (spent >= budgetNanos) overBudget.incrementAndGet()
            callbacks.incrementAndGet()
            return 0
        }

        fun start() {
            val instance = unit ?: error("open first")
            check(AudioOutputUnitStart(instance) == 0) { "AudioOutputUnitStart" }
            running = true
        }

        fun stop() {
            val instance = unit ?: return
            if (running) {
                AudioOutputUnitStop(instance)
                running = false
            }
        }

        fun close() {
            unit?.let { instance ->
                if (running) AudioOutputUnitStop(instance)
                AudioUnitUninitialize(instance)
                AudioComponentInstanceDispose(instance)
            }
            unit = null
            running = false
            selfRef?.dispose()
            selfRef = null
        }
    }
}
