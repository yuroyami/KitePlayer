package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation

/** Detected musical cues, separate from predicted beat-grid crossings and instrument identities. */
@AudioVizAuthoringApi
public enum class AudioEventKind {
    /** Broadband onset. */
    Onset,
    /** Low-frequency transient, which can also be a pitched bass note. */
    LowTransient,
    /** Body/crack transient, without asserting that it is a snare. */
    BodyTransient,
    /** High-frequency transient, without asserting that it is a hi-hat. */
    HighTransient,
    /** An attack accompanying energy recovery; this does not identify a formal musical section. */
    EnergyRise,
    /** A section transition supported by multiple musical features, not by energy alone. */
    SectionBoundary,
    /** A drop with independent structural support. An energy recovery alone is [EnergyRise]. */
    Drop,
    /** A structural thinning with independent support, not merely a reduction in energy. */
    Breakdown,
}

/**
 * Which publisher produced an event. Each source has its own completion watermark, sequence
 * numbers, lateness budget and retention, so a slow source never holds back another.
 */
@AudioVizAuthoringApi
public enum class AudioEventSource {
    /** The causal transient detectors, confirmed within one analysis window. Budget 30 ms. */
    LiveTransient,
    /** A causal structural detector, which confirms a boundary after it happened. Budget 3 s. */
    LiveStructure,
    /** The structural events of an installed song map, delivered on time only. */
    SongMap,
}

/** One detector result. Availability is the newest media sample needed to produce this result. */
@AudioVizAuthoringApi
public class AudioDetection internal constructor(
    public val kind: AudioEventKind,
    public val ptsMicros: Long,
    public val availableMicros: Long,
    /** Shared-reference energy strength in 0..1, independent of confidence and surprise. */
    public val strength: Float,
    /** Detector support in 0..1, not an energy level. */
    public val confidence: Float,
    /** Change relative to recent context in 0..1, not an energy level. */
    public val surprise: Float,
)

/** Immutable publication of all detections of one source through an inclusive media-time watermark. */
@AudioVizAuthoringApi
public class AudioDetections internal constructor(
    public val availableThroughMicros: Long,
    public val completeThroughMicros: Long,
    private val detections: Array<AudioDetection>,
    /** The publisher of this batch. Its watermark says nothing about any other source. */
    public val source: AudioEventSource = AudioEventSource.LiveTransient,
) {
    public val size: Int get() = detections.size
    public operator fun get(index: Int): AudioDetection = detections[index]
}

/**
 * An accepted detection with an identity unique within one continuous analysis history. The
 * identity is the source, generation, revision and sequence together; sequence numbers are
 * comparable only within one source.
 */
@AudioVizAuthoringApi
public class AudioEvent internal constructor(
    public val generation: Generation,
    public val analysisRevision: Long,
    public val sequence: Long,
    public val detection: AudioDetection,
    public val source: AudioEventSource = AudioEventSource.LiveTransient,
) {
    /** True when [other] is the same accepted event, compared by its whole identity. */
    public fun sameIdentity(other: AudioEvent?): Boolean = other != null && source == other.source &&
        generation == other.generation && analysisRevision == other.analysisRevision && sequence == other.sequence
}

/** A retained event and the presentation seconds until its original estimated media time. */
@AudioVizAuthoringApi
public class UpcomingAudioEvent internal constructor(
    public val event: AudioEvent,
    public val secondsUntil: Float,
)

/** One consumer's delivery of a shared event. Zero lateness denotes normal interval delivery. */
@AudioVizAuthoringApi
public class DeliveredAudioEvent internal constructor(
    public val event: AudioEvent,
    /** Positive only when this detection arrived after the consumer had passed its original time. */
    public val lateByMicros: Long,
)

/** Immutable events and accounting from one cursor sample; indexed access preserves multiplicity. */
@AudioVizAuthoringApi
public class AudioEventDelivery internal constructor(
    public val generation: Generation,
    public val analysisRevision: Long,
    public val completeThroughMicros: Long?,
    private val events: Array<DeliveredAudioEvent>,
    public val lateDiscards: Long = 0L,
    public val catchUpDiscards: Long = 0L,
    /** Whether this call reset its catch-up boundary, rather than replaying a missed burst. */
    public val reset: Boolean = false,
    /** The structural source's watermark, or null before it has published. */
    public val structureCompleteThroughMicros: Long? = null,
    /** Live structural events dropped because a complete song map already covers their time. */
    public val duplicateDiscards: Long = 0L,
) {
    public val size: Int get() = events.size
    public operator fun get(index: Int): DeliveredAudioEvent = events[index]
}

/** Retention diagnostics. Payload bytes exclude the bounded object/array/atomic-slot overhead. */
@AudioVizAuthoringApi
public class AudioEventHistoryStats internal constructor(
    public val retainedEvents: Int,
    public val retainedPayloadBytes: Long,
    public val evictedEvents: Long,
    public val rejectedPublications: Long,
    public val completeThroughMicros: Long?,
)
