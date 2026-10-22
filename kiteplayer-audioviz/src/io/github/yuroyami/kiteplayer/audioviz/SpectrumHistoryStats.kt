package io.github.yuroyami.kiteplayer.audioviz

/** Retained feature payload, with bounded slot/object overhead in addition to the payload bytes. */
@AudioVizAuthoringApi
public class SpectrumHistoryStats internal constructor(
    public val retainedFrames: Int,
    public val retainedPayloadBytes: Long,
    public val maximumPayloadBytes: Long,
    public val retentionMicros: Long,
    public val evictedFrames: Long,
    public val rejectedOversizedFrames: Long,
)
