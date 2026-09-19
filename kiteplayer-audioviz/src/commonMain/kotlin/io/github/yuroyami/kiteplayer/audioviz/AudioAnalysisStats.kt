@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import kotlin.concurrent.atomics.AtomicLong

/**
 * Cumulative diagnostics for one shared analysis session. Poll these values for telemetry;
 * they are not Compose state. Durations measure elapsed wall time, not thread CPU time.
 */
public class AudioAnalysisStats internal constructor(
    /** PCM pool capacity while attached, excluding metadata and analyser working storage. */
    public val pcmCapacityBytes: Long,
    private val pendingNanos: () -> Long,
) {
    internal val copies = AtomicLong(0L)
    internal val drops = AtomicLong(0L)
    internal val droppedFrames = AtomicLong(0L)
    internal val invalid = AtomicLong(0L)
    internal val sanitized = AtomicLong(0L)
    internal val resets = AtomicLong(0L)
    internal val analyses = AtomicLong(0L)
    internal val failures = AtomicLong(0L)
    internal val copyTime = AtomicLong(0L)
    internal val copyMax = AtomicLong(0L)
    internal val analysisTime = AtomicLong(0L)

    public val copiedBlocks: Long get() = copies.load()
    public val droppedBlocks: Long get() = drops.load()
    public val droppedSampleFrames: Long get() = droppedFrames.load()
    public val invalidBlocks: Long get() = invalid.load()
    public val sanitizedSamples: Long get() = sanitized.load()
    public val discontinuities: Long get() = resets.load()
    public val publishedAnalyses: Long get() = analyses.load()
    public val analysisFailures: Long get() = failures.load()
    public val totalCopyWallNanos: Long get() = copyTime.load()
    public val maximumCopyWallNanos: Long get() = copyMax.load()
    public val totalAnalysisWallNanos: Long get() = analysisTime.load()
    public val queuedPcmNanos: Long get() = pendingNanos()
}
