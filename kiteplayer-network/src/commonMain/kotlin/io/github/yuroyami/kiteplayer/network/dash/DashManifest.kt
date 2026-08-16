package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlElement
import io.github.yuroyami.kiteplayer.network.xml.XmlMini

/**
 * A DASH MPD parsed in pure commonMain Kotlin (the un-parked D-4 work; libxml2's verdict was
 * NEVER, KPKMP 17.12 D-7). The model keeps what segment resolution needs and nothing else:
 * periods, adaptation sets, representations, the three segment addressing forms (template
 * with number or timeline, and an explicit list), and BaseURL chains.
 *
 * Honest scope, stated where it is true: static (VOD) presentations resolve fully; dynamic
 * (live) manifests parse but segment resolution refuses them, because a live window without
 * clock arithmetic is a lie. Multi-period joins, xlink and encryption descriptors are out of
 * this tier.
 */
public data class DashManifest(
    val isDynamic: Boolean,
    /** mediaPresentationDuration, microseconds, null when absent (live). */
    val durationMicros: Long?,
    val periods: List<DashPeriod>,
    /** The manifest-level BaseURL chain already applied onto the fetch URL. */
    val baseUrl: String,
)

public data class DashPeriod(
    val baseUrl: String,
    val durationMicros: Long?,
    val adaptationSets: List<DashAdaptationSet>,
)

public data class DashAdaptationSet(
    val contentType: String?,
    val mimeType: String?,
    val segmentTemplate: DashSegmentTemplate?,
    val representations: List<DashRepresentation>,
)

public data class DashRepresentation(
    val id: String?,
    val bandwidth: Long,
    val codecs: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val baseUrl: String,
    val segmentTemplate: DashSegmentTemplate?,
    /** SegmentList media URLs, already base-resolved, in order. */
    val segmentUrls: List<String>,
    /** SegmentList initialization URL, base-resolved. */
    val initializationUrl: String?,
)

public data class DashSegmentTemplate(
    val initialization: String?,
    val media: String?,
    val startNumber: Long,
    val timescale: Long,
    /** Per-segment duration in [timescale] units; null when a timeline speaks instead. */
    val duration: Long?,
    val timeline: List<DashTimelineEntry>,
)

/** One `S` element: an explicit start [t] (timescale units), duration [d], and [r] repeats. */
public data class DashTimelineEntry(val t: Long?, val d: Long, val r: Long)

/** The resolved segment plan for one representation: what to fetch, in order. */
public data class DashSegmentPlan(
    val initializationUrl: String?,
    val mediaUrls: List<String>,
)

public object DashManifestParser {

    /** Parses [xml] fetched from [manifestUrl]; the URL anchors every relative BaseURL. */
    public fun parse(xml: String, manifestUrl: String): DashManifest {
        val root = XmlMini.parse(xml)
        require(root.name == "MPD") { "not a DASH manifest: root element is <${root.name}>" }
        val mpdBase = resolveBaseUrl(directoryOf(manifestUrl), root)
        val isDynamic = root.attr("type") == "dynamic"
        val duration = root.attr("mediaPresentationDuration")?.let(::parseIsoDurationMicros)
        val periods = root.children("Period").map { period ->
            val periodBase = resolveBaseUrl(mpdBase, period)
            DashPeriod(
                baseUrl = periodBase,
                durationMicros = period.attr("duration")?.let(::parseIsoDurationMicros),
                adaptationSets = period.children("AdaptationSet").map { set ->
                    val setTemplate = set.child("SegmentTemplate")?.let(::parseTemplate)
                    DashAdaptationSet(
                        contentType = set.attr("contentType"),
                        mimeType = set.attr("mimeType"),
                        segmentTemplate = setTemplate,
                        representations = set.children("Representation").map { rep ->
                            parseRepresentation(rep, periodBase, set, setTemplate)
                        },
                    )
                },
            )
        }
        return DashManifest(isDynamic, duration, periods, mpdBase)
    }

    private fun parseRepresentation(
        rep: XmlElement,
        periodBase: String,
        set: XmlElement,
        setTemplate: DashSegmentTemplate?,
    ): DashRepresentation {
        val repBase = resolveBaseUrl(periodBase, rep)
        val ownTemplate = rep.child("SegmentTemplate")?.let(::parseTemplate)
        val segmentList = rep.child("SegmentList") ?: set.child("SegmentList")
        return DashRepresentation(
            id = rep.attr("id"),
            bandwidth = rep.attr("bandwidth")?.toLongOrNull() ?: 0L,
            codecs = rep.attr("codecs") ?: set.attr("codecs"),
            mimeType = rep.attr("mimeType") ?: set.attr("mimeType"),
            width = rep.attr("width")?.toIntOrNull(),
            height = rep.attr("height")?.toIntOrNull(),
            baseUrl = repBase,
            segmentTemplate = ownTemplate ?: setTemplate,
            segmentUrls = segmentList?.children("SegmentURL")
                ?.mapNotNull { it.attr("media") }
                ?.map { resolveUrl(repBase, it) }
                ?: emptyList(),
            initializationUrl = segmentList?.child("Initialization")?.attr("sourceURL")
                ?.let { resolveUrl(repBase, it) },
        )
    }

    private fun parseTemplate(template: XmlElement): DashSegmentTemplate = DashSegmentTemplate(
        initialization = template.attr("initialization"),
        media = template.attr("media"),
        startNumber = template.attr("startNumber")?.toLongOrNull() ?: 1L,
        timescale = template.attr("timescale")?.toLongOrNull() ?: 1L,
        duration = template.attr("duration")?.toLongOrNull(),
        timeline = template.child("SegmentTimeline")?.children("S")?.map { s ->
            DashTimelineEntry(
                t = s.attr("t")?.toLongOrNull(),
                d = s.attr("d")?.toLongOrNull() ?: 0L,
                r = s.attr("r")?.toLongOrNull() ?: 0L,
            )
        } ?: emptyList(),
    )

    /**
     * The fetch plan for [representation] inside [period]: template substitution with
     * `$RepresentationID$`, `$Number$` (its `%0Nd` width form included), `$Bandwidth$`,
     * `$Time$` and `$$`, counted from the timeline when one speaks and from the period (or
     * presentation) duration otherwise. An explicit SegmentList wins over any template.
     */
    public fun segmentPlan(
        manifest: DashManifest,
        period: DashPeriod,
        representation: DashRepresentation,
    ): DashSegmentPlan {
        require(!manifest.isDynamic) {
            "live (dynamic) manifests need a live window this tier does not do yet"
        }
        if (representation.segmentUrls.isNotEmpty()) {
            return DashSegmentPlan(representation.initializationUrl, representation.segmentUrls)
        }
        val template = representation.segmentTemplate
            ?: run {
                // No addressing at all: the representation IS one file at its base URL.
                return DashSegmentPlan(null, listOf(representation.baseUrl))
            }
        val media = template.media
            ?: throw IllegalArgumentException("SegmentTemplate without media for ${representation.id}")

        val initialization = template.initialization?.let {
            resolveUrl(representation.baseUrl, substitute(it, representation, number = null, time = null))
        }
        val mediaUrls = mutableListOf<String>()
        if (template.timeline.isNotEmpty()) {
            var number = template.startNumber
            var time = 0L
            for (entry in template.timeline) {
                entry.t?.let { time = it }
                for (repeat in 0..entry.r) {
                    mediaUrls += resolveUrl(
                        representation.baseUrl,
                        substitute(media, representation, number, time),
                    )
                    time += entry.d
                    number++
                }
            }
        } else {
            val segmentDuration = template.duration
                ?: throw IllegalArgumentException("SegmentTemplate needs duration or a timeline")
            val totalMicros = period.durationMicros ?: manifest.durationMicros
                ?: throw IllegalArgumentException("cannot count segments without a duration")
            val segmentMicros = segmentDuration * 1_000_000L / template.timescale
            require(segmentMicros > 0) { "degenerate segment duration" }
            val count = (totalMicros + segmentMicros - 1) / segmentMicros
            var time = 0L
            for (i in 0 until count) {
                mediaUrls += resolveUrl(
                    representation.baseUrl,
                    substitute(media, representation, template.startNumber + i, time),
                )
                time += segmentDuration
            }
        }
        return DashSegmentPlan(initialization, mediaUrls)
    }

    /** `$identifier$` substitution, the `$Number%05d$` width form and `$$` escape included. */
    internal fun substitute(
        template: String,
        representation: DashRepresentation,
        number: Long?,
        time: Long?,
    ): String {
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '$') { out.append(c); i++; continue }
            val end = template.indexOf('$', i + 1)
            if (end < 0) { out.append(c); i++; continue }
            val token = template.substring(i + 1, end)
            val name = token.substringBefore('%')
            val format = token.substringAfter('%', "")
            val value: String? = when (name) {
                "" -> "$"
                "RepresentationID" -> representation.id ?: ""
                "Bandwidth" -> representation.bandwidth.toString()
                "Number" -> number?.toString()
                "Time" -> time?.toString()
                else -> null
            }
            if (value == null) { out.append(c); i++; continue }
            out.append(
                if (format.isNotEmpty() && name in setOf("Number", "Time", "Bandwidth")) {
                    // %0Nd: zero-pad to N. The only printf form the spec allows here.
                    val width = format.removePrefix("0").removeSuffix("d").toIntOrNull() ?: 0
                    value.padStart(width, '0')
                } else {
                    value
                },
            )
            i = end + 1
        }
        return out.toString()
    }

    /** The element's BaseURL chain applied onto [parent]. */
    private fun resolveBaseUrl(parent: String, element: XmlElement): String {
        val base = element.child("BaseURL")?.text?.takeIf { it.isNotBlank() } ?: return parent
        return resolveUrl(parent, base)
    }

    /** RFC-3986-lite resolution: absolute URLs pass, path-absolute joins the origin, else the directory. */
    internal fun resolveUrl(base: String, reference: String): String = when {
        reference.contains("://") -> reference
        reference.startsWith("/") -> originOf(base) + reference
        else -> directoryOf(base) + reference
    }

    private fun directoryOf(url: String): String = url.substringBeforeLast('/') + "/"

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('/')
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    /** ISO 8601 duration (the PnDTnHnMnS shapes DASH uses) to microseconds. */
    internal fun parseIsoDurationMicros(raw: String): Long {
        val match = ISO_DURATION.matchEntire(raw.trim())
            ?: throw IllegalArgumentException("not an ISO 8601 duration: $raw")
        val (days, hours, minutes, seconds) = match.destructured
        val total = (days.toDoubleOrNull() ?: 0.0) * 86_400 +
            (hours.toDoubleOrNull() ?: 0.0) * 3_600 +
            (minutes.toDoubleOrNull() ?: 0.0) * 60 +
            (seconds.toDoubleOrNull() ?: 0.0)
        return (total * 1_000_000).toLong()
    }

    private val ISO_DURATION =
        Regex("""P(?:([0-9.]+)D)?(?:T(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?)?""")
}
