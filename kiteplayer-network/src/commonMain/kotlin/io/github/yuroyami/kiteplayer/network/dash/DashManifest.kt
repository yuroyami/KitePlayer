package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlElement
import io.github.yuroyami.kiteplayer.network.xml.XmlMini

/**
 * A DASH MPD parsed in pure commonMain Kotlin (the un-parked D-4 work; libxml2's verdict was
 * NEVER). The model keeps what segment resolution needs and nothing else:
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

/**
 * What a manifest is allowed to point this player at.
 *
 * An MPD is attacker-supplied input: the player fetches whatever it names, using the CALLER'S
 * `HttpClient`, which carries that client's default headers and its cookie jar. Before this policy
 * existed the resolver accepted any absolute URL at all, so a manifest could name
 * `file:///etc/passwd`, or an address on the machine's own network, and have the player fetch it
 * with the caller's credentials attached. That is server-side request forgery plus credential
 * leakage, in the one module built to load remote manifests.
 *
 * **Cross-origin is allowed by default and that is deliberate.** A BaseURL pointing at a different
 * CDN host is ordinary, correct DASH, and refusing it would break real manifests. The two things
 * that are NOT ordinary are refused by default instead: a scheme other than http or https, and an
 * https manifest naming http resources.
 *
 * **A caller whose `HttpClient` carries credentials should pass [SameOrigin].** That is the only
 * configuration in which a hostile manifest cannot make those credentials leave the origin the
 * manifest itself came from.
 */
public data class DashUrlPolicy(
    /** Lowercase schemes a resolved URL may use. */
    val allowedSchemes: Set<String> = setOf("http", "https"),
    /** Whether an `https` manifest may name `http` resources. */
    val allowSchemeDowngrade: Boolean = false,
    /** Whether every resolved URL must share the manifest's scheme, host and port. */
    val sameOriginOnly: Boolean = false,
) {
    public companion object {
        /** http and https, no downgrade, cross-origin allowed. */
        public val Default: DashUrlPolicy = DashUrlPolicy()

        /** [Default] plus: nothing outside the manifest's own origin is ever fetched. */
        public val SameOrigin: DashUrlPolicy = DashUrlPolicy(sameOriginOnly = true)
    }
}

/** A URL a manifest asked for and [DashUrlPolicy] refused. */
public class DashUrlRefusedException(message: String) : IllegalArgumentException(message)

public object DashManifestParser {

    /**
     * Parses [xml] fetched from [manifestUrl]; the URL anchors every relative BaseURL, and
     * [policy] decides what the manifest is allowed to point at.
     */
    public fun parse(
        xml: String,
        manifestUrl: String,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
    ): DashManifest {
        val root = XmlMini.parse(xml)
        require(root.name == "MPD") { "not a DASH manifest: root element is <${root.name}>" }
        requireAllowedScheme(manifestUrl, policy)
        val mpdBase = resolveBaseUrl(directoryOf(manifestUrl), root, policy)
        val isDynamic = root.attr("type") == "dynamic"
        val duration = root.attr("mediaPresentationDuration")?.let(::parseIsoDurationMicros)
        val periods = root.children("Period").map { period ->
            val periodBase = resolveBaseUrl(mpdBase, period, policy)
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
                            parseRepresentation(rep, periodBase, set, setTemplate, policy)
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
        policy: DashUrlPolicy,
    ): DashRepresentation {
        val repBase = resolveBaseUrl(periodBase, rep, policy)
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
                ?.map { resolveUrl(repBase, it, policy) }
                ?: emptyList(),
            initializationUrl = segmentList?.child("Initialization")?.attr("sourceURL")
                ?.let { resolveUrl(repBase, it, policy) },
        )
    }

    private fun parseTemplate(template: XmlElement): DashSegmentTemplate = DashSegmentTemplate(
        initialization = template.attr("initialization"),
        media = template.attr("media"),
        startNumber = template.attr("startNumber")?.toLongOrNull() ?: 1L,
        // Refused here rather than at the division that uses it: `timescale="0"` used to reach
        // `duration * 1_000_000 / timescale` and raise an untyped ArithmeticException.
        timescale = (template.attr("timescale")?.toLongOrNull() ?: 1L).also {
            require(it > 0) { "SegmentTemplate timescale must be positive, not $it" }
        },
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
        policy: DashUrlPolicy = DashUrlPolicy.Default,
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
            resolveUrl(
                representation.baseUrl,
                substitute(it, representation, number = null, time = null),
                policy,
            )
        }
        val mediaUrls = mutableListOf<String>()
        if (template.timeline.isNotEmpty()) {
            var number = template.startNumber
            var time = 0L
            val timeline = template.timeline
            for ((index, entry) in timeline.withIndex()) {
                entry.t?.let { time = it }
                // r >= 0 is that many EXTRA segments. r = -1 is the spec's compact "repeat to
                // the end": until the next entry's own start, or the period's end in timescale
                // units (0..-1 used to expand this entry to nothing at all).
                val repeats: Long = if (entry.r >= 0) entry.r else {
                    require(entry.d > 0) { "degenerate segment duration" }
                    val untilTime = timeline.getOrNull(index + 1)?.t
                        ?: (period.durationMicros ?: manifest.durationMicros)
                            ?.let { micros -> micros * template.timescale / 1_000_000L }
                        ?: throw IllegalArgumentException(
                            "SegmentTimeline r=-1 needs the next entry's t or a duration to stop at",
                        )
                    ((untilTime - time + entry.d - 1) / entry.d - 1).coerceAtLeast(0)
                }
                for (repeat in 0..repeats) {
                    mediaUrls += resolveUrl(
                        representation.baseUrl,
                        substitute(media, representation, number, time),
                        policy,
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
                    policy,
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
    private fun resolveBaseUrl(
        parent: String,
        element: XmlElement,
        policy: DashUrlPolicy,
    ): String {
        val base = element.child("BaseURL")?.text?.takeIf { it.isNotBlank() } ?: return parent
        return resolveUrl(parent, base, policy)
    }

    /**
     * RFC-3986-lite resolution, then [policy].
     *
     * A scheme is detected by its grammar rather than by looking for `://`, which is what let
     * `file:/etc/passwd` through as a relative path and would have accepted a relative segment
     * name that happened to contain `://` as absolute.
     */
    internal fun resolveUrl(
        base: String,
        reference: String,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
    ): String {
        val resolved = when {
            schemeOf(reference) != null -> reference
            // `//host/path` inherits the manifest's scheme, and refuses when there is none to
            // inherit rather than guessing one.
            reference.startsWith("//") -> {
                val scheme = schemeOf(base)
                    ?: throw DashUrlRefusedException(
                        "$reference is scheme-relative and the manifest URL $base has no scheme",
                    )
                "$scheme:$reference"
            }
            reference.startsWith("/") -> originOf(base) + reference
            else -> directoryOf(base) + reference
        }
        return checkAgainst(base, resolved, policy)
    }

    /** The whole of [DashUrlPolicy], applied once, at the only place a URL is produced. */
    private fun checkAgainst(base: String, resolved: String, policy: DashUrlPolicy): String {
        val scheme = schemeOf(resolved)
            ?: throw DashUrlRefusedException("$resolved has no scheme, so nothing can vouch for it")
        if (scheme !in policy.allowedSchemes) {
            throw DashUrlRefusedException(
                "the manifest asked for $resolved; scheme '$scheme' is not in " +
                    "${policy.allowedSchemes.sorted()}, and a manifest is untrusted input",
            )
        }
        if (!policy.allowSchemeDowngrade && schemeOf(base) == "https" && scheme != "https") {
            throw DashUrlRefusedException(
                "an https manifest asked for $resolved over '$scheme'; set " +
                    "DashUrlPolicy(allowSchemeDowngrade = true) if that is genuinely intended",
            )
        }
        if (policy.sameOriginOnly && originOf(resolved) != originOf(base)) {
            throw DashUrlRefusedException(
                "the manifest at ${originOf(base)} asked for $resolved, and this policy is " +
                    "sameOriginOnly; use DashUrlPolicy.Default to allow other CDN hosts",
            )
        }
        return resolved
    }

    /** The manifest URL itself goes through the scheme half of the policy before it is fetched. */
    internal fun requireAllowedScheme(url: String, policy: DashUrlPolicy) {
        val scheme = schemeOf(url)
            ?: throw DashUrlRefusedException("$url has no scheme, so nothing can vouch for it")
        if (scheme !in policy.allowedSchemes) {
            throw DashUrlRefusedException(
                "$url uses scheme '$scheme', which is not in ${policy.allowedSchemes.sorted()}",
            )
        }
    }

    /** The lowercase scheme of [url], or null when it has none. `scheme:` per RFC 3986. */
    private fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        if (!url[0].isLetter()) return null
        for (i in 1 until colon) {
            val ch = url[i]
            if (!ch.isLetterOrDigit() && ch != '+' && ch != '-' && ch != '.') return null
        }
        return url.substring(0, colon).lowercase()
    }

    private fun directoryOf(url: String): String = url.substringBeforeLast('/') + "/"

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('/')
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    /**
     * ISO 8601 duration to microseconds, every component xs:duration allows plus weeks.
     * P0Y0M0DT0H9M56.46S is what several packagers emit, and rejecting the year and
     * month zeros killed the whole manifest. Years and months use the 365 and 30 day
     * conventions, which is what every player does with a calendar-free duration.
     */
    internal fun parseIsoDurationMicros(raw: String): Long {
        val match = ISO_DURATION.matchEntire(raw.trim())
            ?: throw IllegalArgumentException("not an ISO 8601 duration: $raw")
        val g = match.groupValues
        val total = (g[1].toDoubleOrNull() ?: 0.0) * 365 * 86_400 +
            (g[2].toDoubleOrNull() ?: 0.0) * 30 * 86_400 +
            (g[3].toDoubleOrNull() ?: 0.0) * 7 * 86_400 +
            (g[4].toDoubleOrNull() ?: 0.0) * 86_400 +
            (g[5].toDoubleOrNull() ?: 0.0) * 3_600 +
            (g[6].toDoubleOrNull() ?: 0.0) * 60 +
            (g[7].toDoubleOrNull() ?: 0.0)
        return (total * 1_000_000).toLong()
    }

    private val ISO_DURATION = Regex(
        """P(?:([0-9.]+)Y)?(?:([0-9.]+)M)?(?:([0-9.]+)W)?(?:([0-9.]+)D)?""" +
            """(?:T(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?)?""",
    )
}
