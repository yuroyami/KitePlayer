package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.runtime.withFrameNanos
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.compose.KiteVideoState
import io.github.yuroyami.kiteplayer.compose.KiteVideoUploadProfiler
import io.github.yuroyami.kiteplayer.compose.KiteVideoUploadSamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * A UI-thread collector for draw-phase timings. Compose Desktop draws and resumes
 * [withFrameNanos] on the same AWT thread, so no synchronisation is needed and none is paid.
 * Empty capacity means "not measuring", which is one compare per draw.
 */
internal class NanoSamples {
    private var values = LongArray(0)
    private var count = 0

    fun begin(capacity: Int) {
        values = LongArray(capacity)
        count = 0
    }

    fun record(nanos: Long) {
        if (count < values.size) values[count++] = nanos
    }

    fun end(): LongArray {
        val taken = values.copyOf(count)
        values = LongArray(0)
        count = 0
        return taken
    }
}

/**
 * The video node timed on both sides of the modifier chain. [outer] wraps the whole decorated
 * node, [inner] wraps only KiteVideo's own draw. They disagree once a graphicsLayer exists, and
 * that disagreement is the measurement, not a mistake: a layer redraws its cached content on its
 * own schedule.
 */
internal class DrawCost {
    val outer = NanoSamples()
    val inner = NanoSamples()

    fun begin(capacity: Int) {
        outer.begin(capacity)
        inner.begin(capacity)
    }
}

/** One phase of the run: the same clip, once without Compose modifiers and once with them. */
internal class Phase(
    val name: String,
    val upload: KiteVideoUploadSamples,
    val drawOuter: LongArray,
    val drawInner: LongArray,
    val publishedFrames: Long,
    val supersededFrames: Long,
    val failedFrames: Long,
    val droppedLate: Long,
    val droppedDecode: Long,
    val decodedFrames: Long,
    val wallNanos: Long,
    val uiFrames: Int,
    val gcCount: Long,
    val gcMillis: Long,
    val loadAverage: Double,
)

/**
 * Collections and collector time so far. The upload path allocates a fresh plane buffer and a
 * fresh RGBA buffer per frame, so a phase's garbage collector work is part of its cost.
 */
private fun gcTotals(): Pair<Long, Long> {
    var count = 0L
    var millis = 0L
    ManagementFactory.getGarbageCollectorMXBeans().forEach { collector ->
        if (collector.collectionCount > 0) count += collector.collectionCount
        if (collector.collectionTime > 0) millis += collector.collectionTime
    }
    return count to millis
}

private const val WARMUP_FRAMES = 60L

/**
 * KV-5's measurement (17.13 W-05). Alternates a plain phase and a modifier phase for as many
 * rounds as asked, warming up before each, then renders every phase into one report.
 */
internal suspend fun runMeasurement(
    options: SampleOptions,
    player: KitePlayer,
    video: KiteVideoState,
    drawCost: DrawCost,
    setModifiers: (Boolean) -> Unit,
): String {
    val started = withTimeoutOrNull(60_000) {
        player.state.first { it.status == PlaybackStatus.Playing }
    } ?: return "MEASUREMENT ABORTED: playback never reached Playing.\n"

    // Alternating, not one phase after the other: this machine drifts between runs, and only an
    // interleaved order can tell a modifier cost apart from that drift.
    val phases = mutableListOf<Phase>()
    repeat(options.repeats) { round ->
        for (decorated in listOf(false, true)) {
            setModifiers(decorated)
            awaitFrames(video, WARMUP_FRAMES)
            val name = if (decorated) "compose modifiers" else "no modifiers"
            phases += measurePhase(
                name = "$name, round ${round + 1}",
                frames = options.frames.toLong(),
                player = player,
                video = video,
                drawCost = drawCost,
            )
        }
    }

    return renderReport(options, started.videoSize.toString(), video, phases)
}

/** Waits for [count] more published frames, giving up rather than hanging if playback stalls. */
private suspend fun awaitFrames(video: KiteVideoState, count: Long) {
    val from = video.presentedFrames
    var spins = 0
    while (video.presentedFrames - from < count && spins < count * 60) {
        withFrameNanos { }
        spins++
    }
}

private suspend fun measurePhase(
    name: String,
    frames: Long,
    player: KitePlayer,
    video: KiteVideoState,
    drawCost: DrawCost,
): Phase {
    val fromPublished = video.presentedFrames
    val fromSuperseded = video.supersededFrames
    val fromFailed = video.failedFrames
    val fromStats = player.stats.value
    val (fromGcCount, fromGcMillis) = gcTotals()

    // Room for four times the target, so a phase never silently truncates its own tail.
    KiteVideoUploadProfiler.start((frames * 4).toInt())
    drawCost.begin((frames * 8).toInt())
    val fromNanos = System.nanoTime()

    var uiFrames = 0
    while (video.presentedFrames - fromPublished < frames && uiFrames < frames * 60) {
        withFrameNanos { }
        uiFrames++
    }

    val wallNanos = System.nanoTime() - fromNanos
    val upload = KiteVideoUploadProfiler.stop()
    val drawOuter = drawCost.outer.end()
    val drawInner = drawCost.inner.end()
    val toStats = player.stats.value
    val (toGcCount, toGcMillis) = gcTotals()

    return Phase(
        name = name,
        upload = upload,
        drawOuter = drawOuter,
        drawInner = drawInner,
        publishedFrames = video.presentedFrames - fromPublished,
        supersededFrames = video.supersededFrames - fromSuperseded,
        failedFrames = video.failedFrames - fromFailed,
        droppedLate = toStats.droppedFramesLate - fromStats.droppedFramesLate,
        droppedDecode = toStats.droppedFramesDecode - fromStats.droppedFramesDecode,
        decodedFrames = toStats.decodedVideoFrames - fromStats.decodedVideoFrames,
        wallNanos = wallNanos,
        uiFrames = uiFrames,
        gcCount = toGcCount - fromGcCount,
        gcMillis = toGcMillis - fromGcMillis,
        // Sampled at the end of the phase, because this machine shares its cores with whatever
        // else the owner is running and a contaminated phase must be visible in the data.
        loadAverage = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage,
    )
}

/** Nearest-rank percentile, in nanoseconds. [sorted] must already be sorted ascending. */
internal fun percentileNanos(sorted: LongArray, percentile: Double): Long {
    if (sorted.isEmpty()) return 0L
    val rank = Math.ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

private fun LongArray.meanNanos(): Double = if (isEmpty()) 0.0 else sum().toDouble() / size

private fun millis(nanos: Double): String = String.format(Locale.US, "%.3f", nanos / 1e6)

private fun millis(nanos: Long): String = millis(nanos.toDouble())

private fun StringBuilder.distribution(label: String, samples: LongArray) {
    val sorted = samples.sortedArray()
    appendLine(
        String.format(
            Locale.US,
            "  %-14s n=%-5d mean=%-8s p50=%-8s p95=%-8s p99=%-8s max=%s  (ms)",
            label,
            sorted.size,
            millis(sorted.meanNanos()),
            millis(percentileNanos(sorted, 50.0)),
            millis(percentileNanos(sorted, 95.0)),
            millis(percentileNanos(sorted, 99.0)),
            millis(percentileNanos(sorted, 100.0)),
        ),
    )
}

private fun StringBuilder.phase(phase: Phase) {
    val seconds = phase.wallNanos / 1e9
    appendLine("PHASE ${phase.name}")
    appendLine(
        String.format(
            Locale.US,
            "  window        %.2f s, %d published frames (%.1f fps), %d UI frames (%.1f fps)",
            seconds,
            phase.publishedFrames,
            phase.publishedFrames / seconds,
            phase.uiFrames,
            phase.uiFrames / seconds,
        ),
    )
    distribution("upload total", phase.upload.totalNanos())
    distribution("  convert", phase.upload.convertNanos)
    distribution("  image build", phase.upload.imageNanos)
    distribution("draw outer", phase.drawOuter)
    distribution("draw inner", phase.drawInner)
    appendLine(
        "  dropped       renderer superseded=${phase.supersededFrames} " +
            "renderer failed=${phase.failedFrames} " +
            "engine late=${phase.droppedLate} engine decode=${phase.droppedDecode} " +
            "(decoded=${phase.decodedFrames})",
    )
    appendLine(
        String.format(
            Locale.US,
            "  machine       %d gc collections, %d ms in gc, host load average %.1f",
            phase.gcCount,
            phase.gcMillis,
            phase.loadAverage,
        ),
    )
}

private fun renderReport(
    options: SampleOptions,
    videoSize: String,
    video: KiteVideoState,
    phases: List<Phase>,
): String = buildString {
    val cost = video.frameCost
    appendLine()
    appendLine("=== KV-5 desktop upload measurement (KPKMP 17.13 W-05) ===")
    appendLine("host          ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
        "${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cpus")
    appendLine("jdk           ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} " +
        "(${System.getProperty("java.vendor")})")
    appendLine("heap          max=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB")
    appendLine("clip          ${options.media}")
    appendLine("video         $videoSize")
    appendLine("target        ${options.frames} published frames per phase, ${options.repeats} round(s)")
    phases.forEach { measured ->
        appendLine()
        phase(measured)
    }
    appendLine()
    appendLine("cross-check   KiteVideoFrameCost over the whole run: " +
        "samples=${cost.samples} mean=${millis(cost.averageNanos)} ms worst=${millis(cost.worstNanos)} ms")
    appendLine("=== end ===")
}
