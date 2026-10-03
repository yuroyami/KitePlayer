package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlElement
import io.github.yuroyami.kiteplayer.network.xml.XmlMini

/**
 * A DASH MPD parsed in pure commonMain Kotlin, because libxml2 was refused as a dependency.
 * The model keeps what segment resolution needs and nothing else:
 * periods, adaptation sets, representations, the three segment addressing forms (template
 * with number or timeline, an explicit list, and one file with a segment index), BaseURL
 * chains, and the clock of a live presentation.
 *
 * Honest scope, stated where it is true: static (VOD) presentations resolve fully. A dynamic
 * (live) manifest parses with its clock, and [Dash.mediaItemFor] plays it when HLS can carry its
 * segments; [DashManifestParser.segmentPlan] still refuses it. Multi-period joins, xlink and
 * encryption descriptors are out of this tier.
 */
public data class DashManifest(
    val isDynamic: Boolean,
    /** mediaPresentationDuration, microseconds, null when absent (live). */
    val durationMicros: Long?,
    val periods: List<DashPeriod>,
    /** The manifest-level BaseURL chain already applied onto the fetch URL. */
    val baseUrl: String,
    /** availabilityStartTime, in microseconds since 1970 UTC: the moment a live presentation's time 0 became available. */
    val availabilityStartTimeMicros: Long? = null,
    /** minimumUpdatePeriod, microseconds: how long a live manifest stays current before it is fetched again. */
    val minimumUpdatePeriodMicros: Long? = null,
    /** timeShiftBufferDepth, microseconds: how far behind the live edge a live presentation's segments stay available. */
    val timeShiftBufferDepthMicros: Long? = null,
    /** suggestedPresentationDelay, microseconds: how far behind the live edge the manifest asks a player to play. */
    val suggestedPresentationDelayMicros: Long? = null,
    /**
     * The `Location` element, resolved: where a live manifest is to be fetched from from now on,
     * or null when it names none.
     */
    val location: String? = null,
    /**
     * The `UTCTiming` elements, in the manifest's order of preference: where a player reads the
     * time of day that a live manifest's clock counts against, rather than trusting the device's.
     */
    val utcTimings: List<DashUtcTiming> = emptyList(),
)

/**
 * One `UTCTiming` element (ISO/IEC 23009-1, 5.8.4.11): a [schemeIdUri] that says how to read the
 * time, such as `urn:mpeg:dash:utc:http-xsdate:2014` or `urn:mpeg:dash:utc:direct:2014`, and its
 * [value], an address to fetch or the time itself.
 */
public data class DashUtcTiming(val schemeIdUri: String, val value: String)

/** One `Period` of a manifest, with its BaseURL already resolved. */
public data class DashPeriod(
    val baseUrl: String,
    val durationMicros: Long?,
    val adaptationSets: List<DashAdaptationSet>,
    /** start, microseconds from the presentation's time 0, or null when the Period does not state it. */
    val startMicros: Long? = null,
    /** id, which names the same Period across the fetches of a live manifest, or null. */
    val id: String? = null,
)

/** One `AdaptationSet`: the interchangeable representations of one kind of content. */
public data class DashAdaptationSet(
    val contentType: String?,
    val mimeType: String?,
    val segmentTemplate: DashSegmentTemplate?,
    val representations: List<DashRepresentation>,
    /** lang, the RFC 5646 language of the set, or null. */
    val lang: String? = null,
    /** id, which names the same content across Periods (ISO/IEC 23009-1, 5.3.3.1), or null. */
    val id: String? = null,
    /** The text of the set's first `Label` element, a name to show for it, or null. */
    val label: String? = null,
    /**
     * The values of the set's `Role` elements in the DASH role scheme (`urn:mpeg:dash:role:2011`),
     * such as `main`, `alternate`, `commentary`, `subtitle`, `caption` or `forced-subtitle`.
     */
    val roles: List<String> = emptyList(),
    /**
     * The `schemeIdUri` of every `ContentProtection` element of the set and of its
     * representations: empty for content that is not encrypted.
     */
    val contentProtectionSchemes: List<String> = emptyList(),
)

/** One `Representation`: one encoding of the content, with its segments addressed by a template or a list. */
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
    /** frameRate, of the representation or else its set, in frames per second, or null. */
    val frameRate: Double? = null,
    /** The SegmentList in force, with its byte ranges and its timing, or null without one. */
    val segmentList: DashSegmentList? = null,
    /** The SegmentBase in force, for one file whose segment index names its segments, or null without one. */
    val segmentBase: DashSegmentBase? = null,
    /**
     * The number of audio channels its `AudioChannelConfiguration` states, of the representation
     * or else its set, in the schemes that state a count, or null.
     */
    val audioChannels: Int? = null,
)

/** A `SegmentTemplate`, merged from every level that declares one, the lowest level winning each attribute. */
public data class DashSegmentTemplate(
    val initialization: String?,
    val media: String?,
    val startNumber: Long,
    val timescale: Long,
    /** Per-segment duration in [timescale] units; null when a timeline speaks instead. */
    val duration: Long?,
    val timeline: List<DashTimelineEntry>,
    /** presentationTimeOffset, in [timescale] units: the media time at which the Period starts. */
    val presentationTimeOffset: Long = 0,
    /** endNumber, the number of the last segment, or null when the template states none. */
    val endNumber: Long? = null,
)

/**
 * A `SegmentList`, with every URL base-resolved. A `SegmentURL` without `media` names a byte range
 * of the representation's own BaseURL.
 */
public data class DashSegmentList(
    val timescale: Long,
    /** Per-segment duration in [timescale] units; null when a timeline speaks instead, or nothing does. */
    val duration: Long?,
    val startNumber: Long,
    val timeline: List<DashTimelineEntry>,
    val initializationUrl: String?,
    /** The bytes of [initializationUrl] that hold the initialization, or null for all of it. */
    val initializationRange: LongRange?,
    val segments: List<DashSegmentUrl>,
    /** presentationTimeOffset, in [timescale] units: the media time at which the Period starts. */
    val presentationTimeOffset: Long = 0,
)

/** One `SegmentURL`: a URL, and the bytes of it that hold the segment, or null for all of it. */
public data class DashSegmentUrl(val url: String, val range: LongRange?)

/**
 * A `SegmentBase`: one file at the representation's BaseURL, whose own segment index (`sidx`)
 * names its segments. [indexRange] says where the index is; without it the index is looked for at
 * the start of the file.
 */
public data class DashSegmentBase(
    val timescale: Long,
    val indexRange: LongRange?,
    /** Where the initialization is, base-resolved; the representation's BaseURL when no `sourceURL` names another. */
    val initializationUrl: String?,
    val initializationRange: LongRange?,
    /** presentationTimeOffset, in [timescale] units: the media time at which the Period starts. */
    val presentationTimeOffset: Long = 0,
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
 * An MPD is untrusted input: the player fetches whatever it names, using the CALLER'S `HttpClient`,
 * which carries that client's default headers and its cookie jar. The policy judges every URL the
 * manifest names, and every redirect a server answers with, before the player requests it. A
 * request follows at most five redirects, and a chain that comes back to an address it already
 * asked for is refused.
 *
 * **Cross-origin is allowed by default and that is deliberate.** A BaseURL pointing at a different
 * CDN host is ordinary, correct DASH, and refusing it would break real manifests. The two things
 * that are NOT ordinary are refused by default instead: a scheme other than http or https, and an
 * https manifest naming http resources.
 *
 * **A caller whose `HttpClient` carries credentials should pass [SameOrigin].** Then nothing
 * outside the manifest's own scheme, host and port gets a request, whether the manifest names it
 * or a redirect leads to it.
 *
 * The policy judges URLs, not network addresses. A public host name can resolve to an address on
 * the device's own network, and the policy never sees that address. To keep requests off such
 * addresses, filter them where the client connects, for example with OkHttp's `Dns` on Android and
 * the JVM.
 *
 * Redirects are judged where Ktor follows them. The OkHttp and Darwin engines that this module
 * brings leave every redirect to Ktor. An engine configured to follow redirects by itself hides
 * them from the policy. A browser follows a redirect by itself and does not show where it leads, so
 * in a browser [SameOrigin] refuses every redirect and [Default] lets the browser follow them.
 */
public data class DashUrlPolicy(
    /** Lowercase schemes a resolved URL, or a redirect, may use. */
    val allowedSchemes: Set<String> = setOf("http", "https"),
    /** Whether an `https` manifest may name `http` resources, or redirect to them. */
    val allowSchemeDowngrade: Boolean = false,
    /** Whether every resolved URL, and every redirect, must share the manifest's scheme, host and port. */
    val sameOriginOnly: Boolean = false,
) {
    /** The two policies most callers want. */
    public companion object {
        /** http and https, no downgrade, cross-origin allowed. */
        public val Default: DashUrlPolicy = DashUrlPolicy()

        /** [Default] plus: nothing outside the manifest's own origin is ever fetched, redirects included. */
        public val SameOrigin: DashUrlPolicy = DashUrlPolicy(sameOriginOnly = true)
    }
}

/** A URL a manifest asked for and [DashUrlPolicy] refused. */
public class DashUrlRefusedException(message: String) : IllegalArgumentException(message)

/** Parses a DASH MPD into a [DashManifest]. */
public object DashManifestParser {

    /** The most URLs one segment plan, or one representation's SegmentList, may hold, its initialization URL included. */
    internal const val MAX_PLAN_URLS: Int = 100_000

    /**
     * The characters one segment plan, or one representation's SegmentList, may read to build its
     * URLs. Each URL reads its reference and the base URL it resolves against.
     */
    internal const val MAX_PLAN_CHARS: Long = 16L * 1024 * 1024

    /** The longest URL, and the longest SegmentTemplate, a manifest may use, in characters. */
    internal const val MAX_URL_LENGTH: Int = 8 * 1024

    /** The widest `%0Nd` a SegmentTemplate may ask for. */
    internal const val MAX_PAD_WIDTH: Int = 32

    /** The most URLs one parse may build, BaseURLs and every representation's SegmentList together. */
    internal const val MAX_PARSE_URLS: Int = 1024 * 1024

    /** The characters one parse may read to build its URLs, counted as for [MAX_PLAN_CHARS]. */
    internal const val MAX_PARSE_CHARS: Long = 32L * 1024 * 1024

    /** The URL ceilings of one parse or one plan. The defaults are the constants above; tests pass smaller ones. */
    internal class UrlLimits(
        val planUrls: Int = MAX_PLAN_URLS,
        val planChars: Long = MAX_PLAN_CHARS,
        val parseUrls: Int = MAX_PARSE_URLS,
        val parseChars: Long = MAX_PARSE_CHARS,
    ) {
        companion object {
            /**
             * The defaults, with the parse's own ceilings raised in proportion for a document
             * limit above 8 Mi characters: one URL per 8 characters, and 4 characters read per
             * character.
             */
            fun forDocument(maxLength: Int): UrlLimits = UrlLimits(
                parseUrls = maxOf(MAX_PARSE_URLS, maxLength / 8),
                parseChars = maxOf(MAX_PARSE_CHARS, 4L * maxLength),
            )
        }
    }

    /**
     * Parses [xml] fetched from [manifestUrl]; the URL anchors every relative BaseURL, and
     * [policy] decides what the manifest is allowed to point at. A document longer than 8 Mi
     * characters, with more than 262,144 elements or 1,048,576 attributes, or with an element that
     * carries more than 128 attributes, is refused with
     * [io.github.yuroyami.kiteplayer.network.xml.XmlException].
     *
     * A parse builds at most 1,048,576 URLs, BaseURLs and SegmentList entries together, and reads
     * at most 32 Mi characters to build them: each URL reads its reference and the base URL it
     * resolves against. A representation's SegmentList also has the ceilings of a [segmentPlan],
     * and no URL may be longer than 8,192 characters. A manifest that passes one of those is
     * refused with [IllegalArgumentException].
     */
    public fun parse(
        xml: String,
        manifestUrl: String,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
    ): DashManifest = parse(xml, manifestUrl, policy, XmlMini.Limits())

    /**
     * [parse] under [limits], which the DASH door widens to match its own byte ceiling, and
     * [urlLimits], which follow [limits] unless a test passes its own.
     */
    internal fun parse(
        xml: String,
        manifestUrl: String,
        policy: DashUrlPolicy,
        limits: XmlMini.Limits,
        urlLimits: UrlLimits = UrlLimits.forDocument(limits.maxLength),
    ): DashManifest {
        val root = XmlMini.parse(xml, limits)
        require(root.name == "MPD") { "not a DASH manifest: root element is <${root.name}>" }
        requireAllowedScheme(manifestUrl, policy)
        val budget = UrlBudget(urlLimits.parseUrls, urlLimits.parseChars, "the manifest")
        // The manifest's own URL is the base. Resolution drops its last path segment and its query.
        val mpdBase = resolveBaseUrl(UrlBase(manifestUrl), root, policy, budget)
        val isDynamic = root.attr("type") == "dynamic"
        val duration = root.attr("mediaPresentationDuration")?.let(::parseIsoDurationMicros)
        val periods = root.children("Period").map { period ->
            val periodBase = resolveBaseUrl(mpdBase, period, policy, budget)
            val periodTemplate = TemplateLevel.under(null, period)
            val periodSegmentBase = period.child("SegmentBase")
            DashPeriod(
                baseUrl = periodBase.url,
                durationMicros = period.attr("duration")?.let(::parseIsoDurationMicros),
                adaptationSets = period.children("AdaptationSet").map { set ->
                    // Each level's BaseURL resolves against the level above it: MPD, Period,
                    // AdaptationSet, Representation (ISO/IEC 23009-1, 5.6.4).
                    val setBase = resolveBaseUrl(periodBase, set, policy, budget)
                    // Read once per set, not once per representation: a set can hold many
                    // representations, and each lookup walks the set's children.
                    val setTemplate = TemplateLevel.under(periodTemplate, set)
                    val setSegmentList = set.child("SegmentList")
                    val setSegmentBase = set.child("SegmentBase") ?: periodSegmentBase
                    DashAdaptationSet(
                        contentType = set.attr("contentType"),
                        mimeType = set.attr("mimeType"),
                        segmentTemplate = setTemplate?.template,
                        representations = set.children("Representation").map { rep ->
                            parseRepresentation(
                                rep, setBase, set, setSegmentList, setSegmentBase, setTemplate, policy, budget, urlLimits,
                            )
                        },
                        lang = set.attr("lang"),
                    )
                },
                startMicros = period.attr("start")?.let(::parseIsoDurationMicros),
            )
        }
        return DashManifest(
            isDynamic = isDynamic,
            durationMicros = duration,
            periods = periods,
            baseUrl = mpdBase.url,
            availabilityStartTimeMicros = root.attr("availabilityStartTime")?.let(::parseDateTimeMicros),
            minimumUpdatePeriodMicros = root.attr("minimumUpdatePeriod")?.let(::parseIsoDurationMicros),
            timeShiftBufferDepthMicros = root.attr("timeShiftBufferDepth")?.let(::parseIsoDurationMicros),
            suggestedPresentationDelayMicros = root.attr("suggestedPresentationDelay")?.let(::parseIsoDurationMicros),
        )
    }

    private fun parseRepresentation(
        rep: XmlElement,
        setBase: UrlBase,
        set: XmlElement,
        setSegmentList: XmlElement?,
        setSegmentBase: XmlElement?,
        setTemplate: TemplateLevel?,
        policy: DashUrlPolicy,
        budget: UrlBudget,
        urlLimits: UrlLimits,
    ): DashRepresentation {
        val repBase = resolveBaseUrl(setBase, rep, policy, budget)
        val segmentList = rep.child("SegmentList") ?: setSegmentList
        // A representation's SegmentList is its segment plan, so it has the same ceilings as one,
        // and it also counts against the manifest's.
        val listBudget = UrlBudget(urlLimits.planUrls, urlLimits.planChars, "a representation's SegmentList", budget)
        fun take(reference: String): String = listBudget.resolve(repBase, reference, policy)
        val entries = segmentList?.children("SegmentURL").orEmpty()
        listBudget.reserve(entries.count { it.attr("media") != null }.toLong())
        // Each URL is resolved once. A SegmentURL without media names a range of the BaseURL.
        val listed = entries.map { entry ->
            entry.attr("media")?.let(::take) to entry.attr("mediaRange")?.let(::parseByteRange)
        }
        val initialization = segmentList?.child("Initialization")
        val initializationUrl = initialization?.attr("sourceURL")?.let(::take)
        return DashRepresentation(
            id = rep.attr("id"),
            bandwidth = rep.attr("bandwidth")?.toLongOrNull() ?: 0L,
            codecs = rep.attr("codecs") ?: set.attr("codecs"),
            mimeType = rep.attr("mimeType") ?: set.attr("mimeType"),
            width = rep.attr("width")?.toIntOrNull(),
            height = rep.attr("height")?.toIntOrNull(),
            baseUrl = repBase.url,
            segmentTemplate = TemplateLevel.under(setTemplate, rep)?.template,
            segmentUrls = listed.mapNotNull { it.first },
            initializationUrl = initializationUrl,
            frameRate = (rep.attr("frameRate") ?: set.attr("frameRate"))?.let(::parseFrameRate),
            segmentList = segmentList?.let { list ->
                DashSegmentList(
                    timescale = positiveTimescale(list.attr("timescale")),
                    duration = list.attr("duration")?.toLongOrNull(),
                    startNumber = list.attr("startNumber")?.toLongOrNull() ?: 1L,
                    timeline = list.child("SegmentTimeline")?.let(::parseTimeline).orEmpty(),
                    initializationUrl = initializationUrl
                        ?: repBase.url.takeIf { initialization?.attr("range") != null },
                    initializationRange = initialization?.attr("range")?.let(::parseByteRange),
                    segments = listed.map { (url, range) -> DashSegmentUrl(url ?: repBase.url, range) },
                )
            },
            segmentBase = (rep.child("SegmentBase") ?: setSegmentBase)?.let { base ->
                val baseInitialization = base.child("Initialization")
                DashSegmentBase(
                    timescale = positiveTimescale(base.attr("timescale")),
                    indexRange = base.attr("indexRange")?.let(::parseByteRange),
                    initializationUrl = baseInitialization?.attr("sourceURL")?.let(::take) ?: repBase.url,
                    initializationRange = baseInitialization?.attr("range")?.let(::parseByteRange),
                )
            },
        )
    }

    /** A timescale attribute, 1 when absent, refused when it is not positive. */
    private fun positiveTimescale(raw: String?): Long =
        (raw?.toLongOrNull() ?: 1L).also { require(it > 0) { "a timescale must be positive, not $it" } }

    /** The `S` elements of a SegmentTimeline. */
    private fun parseTimeline(timeline: XmlElement): List<DashTimelineEntry> =
        timeline.children("S").map { s ->
            DashTimelineEntry(
                t = s.attr("t")?.toLongOrNull(),
                d = s.attr("d")?.toLongOrNull() ?: 0L,
                r = s.attr("r")?.toLongOrNull() ?: 0L,
            )
        }

    /** A byte range written `first-last`, both inclusive, as the MPD writes them. */
    internal fun parseByteRange(raw: String): LongRange {
        val first = raw.substringBefore('-').trim().toLongOrNull()
        val last = raw.substringAfter('-', "").trim().toLongOrNull()
        require(first != null && last != null && first >= 0 && last >= first) { "not a byte range: $raw" }
        return first..last
    }

    /** A frame rate written as a number or as `numerator/denominator`, or null when it is neither. */
    internal fun parseFrameRate(raw: String): Double? {
        val numerator = raw.substringBefore('/').trim().toDoubleOrNull() ?: return null
        val denominator = if ('/' in raw) raw.substringAfter('/').trim().toDoubleOrNull() ?: return null else 1.0
        return (numerator / denominator).takeIf { it.isFinite() && it > 0 }
    }

    /**
     * The SegmentTemplate in force at one level: Period, AdaptationSet or Representation. A lower
     * level overrides only the attributes it sets, and its own SegmentTimeline replaces the one
     * above it (ISO/IEC 23009-1, 5.3.9.1).
     *
     * A level without a template of its own is its parent, the same object. So a timeline is read
     * once, and every representation that inherits it shares one [template].
     */
    private class TemplateLevel(
        private val initialization: String?,
        private val media: String?,
        private val startNumber: String?,
        private val timescale: String?,
        private val duration: String?,
        private val presentationTimeOffset: String?,
        private val timeline: List<DashTimelineEntry>?,
    ) {
        /** Built on first use, so a level that only passes attributes down is never checked alone. */
        val template: DashSegmentTemplate by lazy {
            DashSegmentTemplate(
                initialization = initialization,
                media = media,
                startNumber = startNumber?.toLongOrNull() ?: 1L,
                // Refused here rather than at the division that uses it: `timescale="0"` used to
                // reach `duration * 1_000_000 / timescale` and raise an untyped ArithmeticException.
                timescale = (timescale?.toLongOrNull() ?: 1L).also {
                    require(it > 0) { "SegmentTemplate timescale must be positive, not $it" }
                },
                duration = duration?.toLongOrNull(),
                timeline = timeline ?: emptyList(),
                presentationTimeOffset = presentationTimeOffset?.toLongOrNull() ?: 0L,
            )
        }

        companion object {
            /** The level [element] makes under [parent], or [parent] itself when [element] has no template. */
            fun under(parent: TemplateLevel?, element: XmlElement): TemplateLevel? {
                val own = element.child("SegmentTemplate") ?: return parent
                return TemplateLevel(
                    initialization = own.attr("initialization") ?: parent?.initialization,
                    media = own.attr("media") ?: parent?.media,
                    startNumber = own.attr("startNumber") ?: parent?.startNumber,
                    timescale = own.attr("timescale") ?: parent?.timescale,
                    duration = own.attr("duration") ?: parent?.duration,
                    presentationTimeOffset = own.attr("presentationTimeOffset") ?: parent?.presentationTimeOffset,
                    timeline = own.child("SegmentTimeline")?.let(::parseTimeline) ?: parent?.timeline,
                )
            }
        }
    }

    /**
     * The fetch plan for [representation] inside [period]: template substitution with
     * `$RepresentationID$`, `$Number$` (its `%0Nd` width form included), `$Bandwidth$`,
     * `$Time$` and `$$`, counted from the timeline when one speaks and from the period (or
     * presentation) duration otherwise. An explicit SegmentList wins over any template.
     *
     * A plan holds at most 100,000 URLs, its initialization URL included, and reads at most 16 Mi
     * characters to build them: each URL reads its reference and the base URL it resolves against.
     * No URL and no SegmentTemplate may be longer than 8,192 characters, and a `%0Nd` width is at
     * most 32. A plan that passes one of those, whose timescale or segment durations are not
     * positive, or whose segment numbers or times pass the range of a Long, is refused with
     * [IllegalArgumentException]. A segment count that passes the limit is refused before any URL
     * is built. The same checks apply to model objects a caller builds without [parse].
     */
    public fun segmentPlan(
        manifest: DashManifest,
        period: DashPeriod,
        representation: DashRepresentation,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
    ): DashSegmentPlan = segmentPlan(manifest, period, representation, policy, UrlLimits())

    internal fun segmentPlan(
        manifest: DashManifest,
        period: DashPeriod,
        representation: DashRepresentation,
        policy: DashUrlPolicy,
        urlLimits: UrlLimits,
    ): DashSegmentPlan {
        if (manifest.isDynamic) {
            throw DashUnsupportedException("live (dynamic) manifests need a live window this tier does not do yet")
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
        // Checked here as well as in the parse, because a caller can build the model itself.
        require(template.timescale > 0) { "SegmentTemplate timescale must be positive, not ${template.timescale}" }
        val durationMicros = (period.durationMicros ?: manifest.durationMicros)?.also {
            require(it >= 0) { "a duration of $it microseconds is negative" }
        }

        val budget = UrlBudget(urlLimits.planUrls, urlLimits.planChars, "the segment plan")
        val base = UrlBase(representation.baseUrl)
        val initialization = template.initialization?.let {
            budget.resolve(base, substitute(it, representation, number = null, time = null), policy)
        }
        val mediaTemplate = compileTemplate(media, representation, numberKnown = true, timeKnown = true)
        val mediaUrls = mutableListOf<String>()
        fun add(number: Long, time: Long) {
            mediaUrls += budget.resolve(base, renderTemplate(mediaTemplate, number, time), policy)
        }
        if (template.timeline.isNotEmpty()) {
            var number = template.startNumber
            var time = 0L
            val timeline = template.timeline
            for ((index, entry) in timeline.withIndex()) {
                require(entry.d > 0) { "degenerate segment duration" }
                entry.t?.let { time = it }
                // r >= 0 is that many EXTRA segments. r = -1 is the spec's compact "repeat to
                // the end": until the next entry's own start, or the period's end in timescale
                // units (0..-1 used to expand this entry to nothing at all).
                val repeats: Long = if (entry.r >= 0) entry.r else {
                    val untilTime = timeline.getOrNull(index + 1)?.t
                        ?: durationMicros?.let { rescale(it, template.timescale, 1_000_000L, "the period in timescale units") }
                        ?: throw IllegalArgumentException(
                            "SegmentTimeline r=-1 needs the next entry's t or a duration to stop at",
                        )
                    val span = minus(untilTime, time, "the time until the next entry")
                    if (span <= 0) 0 else (span - 1) / entry.d
                }
                budget.reserve(if (repeats < Long.MAX_VALUE) repeats + 1 else repeats)
                for (repeat in 0..repeats) {
                    add(number, time)
                    time = plus(time, entry.d, "a segment time")
                    number = plus(number, 1, "a segment number")
                }
            }
        } else {
            val segmentDuration = template.duration
                ?: throw IllegalArgumentException("SegmentTemplate needs duration or a timeline")
            val totalMicros = durationMicros
                ?: throw IllegalArgumentException("cannot count segments without a duration")
            val segmentMicros = rescale(segmentDuration, 1_000_000L, template.timescale, "the segment duration")
            require(segmentMicros > 0) { "degenerate segment duration" }
            val count = totalMicros / segmentMicros + if (totalMicros % segmentMicros != 0L) 1 else 0
            budget.reserve(count)
            var time = 0L
            for (i in 0 until count) {
                add(plus(template.startNumber, i, "a segment number"), time)
                time = plus(time, segmentDuration, "a segment time")
            }
        }
        return DashSegmentPlan(initialization, mediaUrls)
    }

    /**
     * Every segment of [representation] with its place in time, for the HLS route (#295), or null
     * for a [DashSegmentBase] representation, whose segments only its own index names.
     *
     * A static presentation gives all its segments. A dynamic one gives those available at
     * [nowMicros], microseconds since 1970 UTC: a segment is available once its end has passed
     * the presentation's availability start plus the Period's start, and it stays so for the
     * time-shift buffer, or for [DEFAULT_LIVE_WINDOW_MICROS] when the manifest names none. A
     * representation with no segment addressing is one segment, the whole of its BaseURL, which
     * needs a duration to say how long it is.
     *
     * The ceilings, URL checks and refusals are those of [segmentPlan].
     */
    internal fun timedPlan(
        manifest: DashManifest,
        period: DashPeriod,
        representation: DashRepresentation,
        policy: DashUrlPolicy,
        nowMicros: Long?,
        urlLimits: UrlLimits = UrlLimits(),
    ): DashTimedPlan? {
        val totalMicros = (period.durationMicros ?: manifest.durationMicros)?.also {
            require(it >= 0) { "a duration of $it microseconds is negative" }
        }
        val window = liveWindow(manifest, period, nowMicros)
        val list = representation.segmentList
        val template = representation.segmentTemplate
        return when {
            list != null && list.segments.isNotEmpty() -> listPlan(list, totalMicros, window)
            template != null -> templatePlan(template, representation, totalMicros, window, policy, urlLimits)
            representation.segmentBase != null -> null
            else -> {
                require(window == null) { "a live representation needs segment addressing" }
                val whole = requireNotNull(totalMicros) { "a single-file representation needs a duration" }
                DashTimedPlan(null, null, listOf(DashTimedSegment(representation.baseUrl, null, 1, 0, whole)))
            }
        }
    }

    /** The default time-shift buffer of a live presentation that names none: two minutes. */
    internal const val DEFAULT_LIVE_WINDOW_MICROS: Long = 120_000_000L

    /** The span of Period time, in microseconds, whose segments a live presentation has available. */
    private class LiveWindow(val fromMicros: Long, val edgeMicros: Long)

    private fun liveWindow(manifest: DashManifest, period: DashPeriod, nowMicros: Long?): LiveWindow? {
        if (!manifest.isDynamic) return null
        val start = requireNotNull(manifest.availabilityStartTimeMicros) {
            "a live manifest needs an availabilityStartTime"
        }
        val now = requireNotNull(nowMicros) { "a live manifest needs the time of day" }
        val edge = now - start - (period.startMicros ?: 0L)
        return LiveWindow(edge - (manifest.timeShiftBufferDepthMicros ?: DEFAULT_LIVE_WINDOW_MICROS), edge)
    }

    /** Whether a segment that ends at [endMicros] of Period time is in this window, or anywhere when there is none. */
    private fun LiveWindow?.holds(endMicros: Long): Boolean =
        this == null || (endMicros <= edgeMicros && endMicros > fromMicros)

    private fun listPlan(list: DashSegmentList, totalMicros: Long?, window: LiveWindow?): DashTimedPlan {
        val count = list.segments.size
        val timeline = expandTimeline(list.timeline, list.timescale, totalMicros, window, count.toLong())
        val segments = ArrayList<DashTimedSegment>(count)
        for ((index, entry) in list.segments.withIndex()) {
            val (startMicros, durationMicros) = when {
                timeline.isNotEmpty() -> timeline.getOrNull(index)?.let { it.startMicros to it.durationMicros } ?: break
                list.duration != null -> {
                    val each = rescale(list.duration, 1_000_000L, list.timescale, "the segment duration")
                    require(each > 0) { "degenerate segment duration" }
                    times(index.toLong(), each, "a segment start") to each
                }
                else -> {
                    val total = requireNotNull(totalMicros) { "a SegmentList without durations needs a total duration" }
                    val each = total / count
                    require(each > 0) { "degenerate segment duration" }
                    index * each to each
                }
            }
            if (window.holds(startMicros + durationMicros)) {
                segments += DashTimedSegment(entry.url, entry.range, list.startNumber + index, startMicros, durationMicros)
            }
        }
        return DashTimedPlan(list.initializationUrl, list.initializationRange, segments)
    }

    private fun templatePlan(
        template: DashSegmentTemplate,
        representation: DashRepresentation,
        totalMicros: Long?,
        window: LiveWindow?,
        policy: DashUrlPolicy,
        urlLimits: UrlLimits,
    ): DashTimedPlan {
        val media = template.media
            ?: throw IllegalArgumentException("SegmentTemplate without media for ${representation.id}")
        require(template.timescale > 0) { "SegmentTemplate timescale must be positive, not ${template.timescale}" }
        val budget = UrlBudget(urlLimits.planUrls, urlLimits.planChars, "the segment plan")
        val base = UrlBase(representation.baseUrl)
        val initialization = template.initialization?.let {
            budget.resolve(base, substitute(it, representation, number = null, time = null), policy)
        }
        val mediaTemplate = compileTemplate(media, representation, numberKnown = true, timeKnown = true)
        val segments = mutableListOf<DashTimedSegment>()
        fun add(number: Long, time: Long, startMicros: Long, durationMicros: Long) {
            segments += DashTimedSegment(
                budget.resolve(base, renderTemplate(mediaTemplate, number, time), policy),
                null,
                number,
                startMicros,
                durationMicros,
            )
        }
        if (template.timeline.isNotEmpty()) {
            for (entry in expandTimeline(template.timeline, template.timescale, totalMicros, window, null, template.presentationTimeOffset)) {
                budget.reserve(1)
                add(plus(template.startNumber, entry.index, "a segment number"), entry.time, entry.startMicros, entry.durationMicros)
            }
        } else {
            val segmentDuration = template.duration
                ?: throw IllegalArgumentException("SegmentTemplate needs duration or a timeline")
            val segmentMicros = rescale(segmentDuration, 1_000_000L, template.timescale, "the segment duration")
            require(segmentMicros > 0) { "degenerate segment duration" }
            val first: Long
            val end: Long
            if (window == null) {
                val total = totalMicros ?: throw IllegalArgumentException("cannot count segments without a duration")
                first = 0
                end = total / segmentMicros + if (total % segmentMicros != 0L) 1 else 0
            } else {
                // Segment k ends at (k + 1) segment lengths, and is available once that has passed.
                end = if (window.edgeMicros < segmentMicros) 0 else window.edgeMicros / segmentMicros
                first = if (window.fromMicros < 0) 0 else window.fromMicros / segmentMicros
            }
            budget.reserve((end - first).coerceAtLeast(0))
            for (k in first until end) {
                val startMicros = times(k, segmentMicros, "a segment start")
                // The last segment of a finished presentation ends with it, not a whole length later.
                val durationMicros = if (window == null && totalMicros != null) {
                    minOf(segmentMicros, totalMicros - startMicros)
                } else {
                    segmentMicros
                }
                add(
                    plus(template.startNumber, k, "a segment number"),
                    times(k, segmentDuration, "a segment time"),
                    startMicros,
                    durationMicros,
                )
            }
        }
        return DashTimedPlan(initialization, null, segments)
    }

    /** One timeline segment: its place in the timeline, its time in timescale units, and its span in microseconds. */
    private class TimelineSegment(val index: Long, val time: Long, val startMicros: Long, val durationMicros: Long)

    /**
     * [timeline] laid out segment by segment, at most [limit] of them, keeping those in [window].
     * An `r` of -1 repeats until the next entry's start, the Period's end, or for a live
     * presentation the live edge. Times count from [presentationTimeOffset].
     */
    private fun expandTimeline(
        timeline: List<DashTimelineEntry>,
        timescale: Long,
        totalMicros: Long?,
        window: LiveWindow?,
        limit: Long?,
        presentationTimeOffset: Long = 0,
    ): List<TimelineSegment> {
        val out = mutableListOf<TimelineSegment>()
        var time = 0L
        var index = 0L
        fun micros(units: Long) = rescale(units, 1_000_000L, timescale, "a segment time")
        fun timescaleUnits(micros: Long) = rescale(micros, timescale, 1_000_000L, "the period in timescale units")
        for ((at, entry) in timeline.withIndex()) {
            require(entry.d > 0) { "degenerate segment duration" }
            entry.t?.let { time = it }
            val repeats: Long = if (entry.r >= 0) entry.r else {
                val untilTime = timeline.getOrNull(at + 1)?.t
                    ?: totalMicros?.let { plus(timescaleUnits(it), presentationTimeOffset, "the period end") }
                    ?: window?.let { plus(timescaleUnits(it.edgeMicros.coerceAtLeast(0)), presentationTimeOffset, "the live edge") }
                    ?: throw IllegalArgumentException("SegmentTimeline r=-1 needs the next entry's t or a duration to stop at")
                val span = minus(untilTime, time, "the time until the next entry")
                if (span <= 0) 0 else (span - 1) / entry.d
            }
            val durationMicros = micros(entry.d)
            var repeat = 0L
            while (repeat <= repeats) {
                if (limit != null && index >= limit) return out
                val startMicros = micros(minus(time, presentationTimeOffset, "a segment start"))
                if (window != null) {
                    // Nothing that starts at the live edge has ended, and the timeline is in order.
                    if (startMicros >= window.edgeMicros) return out
                    // A live timeline can repeat one entry for days: step over what left the window.
                    val behind = window.fromMicros - (startMicros + durationMicros)
                    val skip = if (behind > 0 && durationMicros > 0) minOf(behind / durationMicros, repeats - repeat) else 0
                    if (skip > 0) {
                        time = plus(time, times(skip, entry.d, "a segment time"), "a segment time")
                        index = plus(index, skip, "a segment number")
                        repeat += skip
                        continue
                    }
                }
                if (window.holds(startMicros + durationMicros)) {
                    require(out.size < MAX_PLAN_URLS) { "the segment plan needs more than $MAX_PLAN_URLS URLs" }
                    out += TimelineSegment(index, time, startMicros, durationMicros)
                }
                time = plus(time, entry.d, "a segment time")
                index = plus(index, 1, "a segment number")
                repeat++
            }
        }
        return out
    }

    /**
     * [value] times [multiplier], divided by [divisor], which is positive. The division comes
     * first, so the product passes the range of a Long only when the result nearly does.
     */
    private fun rescale(value: Long, multiplier: Long, divisor: Long, what: String): Long =
        plus(
            times(value / divisor, multiplier, what),
            times(value % divisor, multiplier, what) / divisor,
            what,
        )

    /** [a] + [b], or a refusal naming [what] when the sum passes the range of a Long. */
    private fun plus(a: Long, b: Long, what: String): Long {
        val sum = a + b
        if (((a xor sum) and (b xor sum)) < 0) throw IllegalArgumentException("$what passes the range of a Long")
        return sum
    }

    /** [a] - [b], or a refusal naming [what] when the difference passes the range of a Long. */
    private fun minus(a: Long, b: Long, what: String): Long {
        val difference = a - b
        if (((a xor b) and (a xor difference)) < 0) throw IllegalArgumentException("$what passes the range of a Long")
        return difference
    }

    /** [a] times [b], or a refusal naming [what] when the product passes the range of a Long. */
    private fun times(a: Long, b: Long, what: String): Long {
        if (a == 0L || b == 0L) return 0L
        val product = a * b
        if (product / b != a || (a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) {
            throw IllegalArgumentException("$what passes the range of a Long")
        }
        return product
    }

    /**
     * The URLs one segment plan, one representation's SegmentList or one parse builds, and the
     * characters it reads to build them. Each URL reads its reference and the base URL it
     * resolves against, which covers the URL, never longer than the two, and the work of
     * resolving it. [resolve] charges a URL before that work and refuses the URL that passes a
     * ceiling. [reserve] refuses a count that can never fit, before any of its URLs is built. A
     * [parent] is charged with everything this budget is.
     */
    private class UrlBudget(
        private val maxUrls: Int,
        private val maxChars: Long,
        private val what: String,
        private val parent: UrlBudget? = null,
    ) {
        private var urls = 0
        private var chars = 0L

        fun reserve(count: Long) {
            if (count > maxUrls - urls) throw IllegalArgumentException("$what needs more than $maxUrls URLs")
            parent?.reserve(count)
        }

        fun resolve(base: UrlBase, reference: String, policy: DashUrlPolicy): String {
            charge(base.url.length.toLong() + reference.length)
            val url = resolveUrl(base, reference, policy)
            if (url.length > MAX_URL_LENGTH) {
                throw IllegalArgumentException("$what has a URL longer than $MAX_URL_LENGTH characters")
            }
            return url
        }

        private fun charge(cost: Long) {
            if (urls == maxUrls) throw IllegalArgumentException("$what needs more than $maxUrls URLs")
            if (cost > maxChars - chars) {
                throw IllegalArgumentException("$what reads more than $maxChars characters of base URLs and references")
            }
            urls++
            chars += cost
            parent?.charge(cost)
        }
    }

    /** A piece of a compiled SegmentTemplate: fixed text, or a `$Number$` or `$Time$` field. */
    private sealed interface TemplatePart {
        class Text(val text: String) : TemplatePart
        class Field(val isNumber: Boolean, val width: Int) : TemplatePart
    }

    /** `$identifier$` substitution, the `$Number%05d$` width form and `$$` escape included. */
    internal fun substitute(
        template: String,
        representation: DashRepresentation,
        number: Long?,
        time: Long?,
    ): String = renderTemplate(
        compileTemplate(template, representation, numberKnown = number != null, timeKnown = time != null),
        number ?: 0L,
        time ?: 0L,
    )

    /**
     * [template] split into its parts once, so each segment costs only the length of its URL.
     * `$RepresentationID$`, `$Bandwidth$` and `$$` do not change between segments and are written
     * into the text here. A `$Number$` or `$Time$` that is not known stays literal, and so does any
     * other `$...$`, whose closing `$` then opens the next field.
     */
    private fun compileTemplate(
        template: String,
        representation: DashRepresentation,
        numberKnown: Boolean,
        timeKnown: Boolean,
    ): List<TemplatePart> {
        if (template.length > MAX_URL_LENGTH) {
            throw IllegalArgumentException("a SegmentTemplate is longer than $MAX_URL_LENGTH characters")
        }
        val parts = mutableListOf<TemplatePart>()
        val text = StringBuilder()
        // Every URL holds all the fixed text, so a value written into it may not take the fixed
        // text past the URL limit. The template's own characters are bounded above.
        var flushed = 0
        fun addValue(value: String) {
            if (value.length > MAX_URL_LENGTH - flushed - text.length) {
                throw IllegalArgumentException("a SegmentTemplate puts more than $MAX_URL_LENGTH characters into every URL")
            }
            text.append(value)
        }
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '$') { text.append(c); i++; continue }
            val end = template.indexOf('$', i + 1)
            if (end < 0) { text.append(c); i++; continue }
            val token = template.substring(i + 1, end)
            val name = token.substringBefore('%')
            val format = token.substringAfter('%', "")
            when {
                name.isEmpty() -> text.append('$')
                name == "RepresentationID" -> addValue(representation.id ?: "")
                name == "Bandwidth" -> addValue(representation.bandwidth.toString().padStart(padWidth(format), '0'))
                (name == "Number" && numberKnown) || (name == "Time" && timeKnown) -> {
                    if (text.isNotEmpty()) {
                        flushed += text.length
                        parts += TemplatePart.Text(text.toString())
                        text.clear()
                    }
                    parts += TemplatePart.Field(isNumber = name == "Number", width = padWidth(format))
                }
                else -> { text.append(c); i++; continue }
            }
            i = end + 1
        }
        if (text.isNotEmpty()) parts += TemplatePart.Text(text.toString())
        return parts
    }

    /**
     * The width a `%0Nd` format asks for: 0 without one, or for a format that is not a number, as
     * before. A number below 0 or past [MAX_PAD_WIDTH] is refused, however many digits it has.
     */
    private fun padWidth(format: String): Int {
        if (format.isEmpty()) return 0
        // %0Nd: zero-pad to N. The only printf form the spec allows here.
        val number = format.removePrefix("0").removeSuffix("d")
        if (number.isEmpty() || number.any { it !in '0'..'9' && it != '-' }) return 0
        val width = number.toIntOrNull()
        if (width == null || width < 0 || width > MAX_PAD_WIDTH) {
            val shown = if (number.length > 12) number.take(12) + "..." else number
            throw IllegalArgumentException("a SegmentTemplate pads to $shown digits, and the limit is $MAX_PAD_WIDTH")
        }
        return width
    }

    /** One URL from [parts], refused before it passes [MAX_URL_LENGTH]. */
    private fun renderTemplate(parts: List<TemplatePart>, number: Long, time: Long): String {
        val out = StringBuilder()
        for (part in parts) {
            val piece = when (part) {
                is TemplatePart.Text -> part.text
                is TemplatePart.Field -> (if (part.isNumber) number else time).toString().padStart(part.width, '0')
            }
            if (piece.length > MAX_URL_LENGTH - out.length) {
                throw IllegalArgumentException("a SegmentTemplate makes a URL longer than $MAX_URL_LENGTH characters")
            }
            out.append(piece)
        }
        return out.toString()
    }

    /**
     * The element's BaseURL applied onto [parent], charged to [budget], or [parent] itself
     * when the element has none. Each level's base is split once, however many elements below
     * resolve against it.
     */
    private fun resolveBaseUrl(
        parent: UrlBase,
        element: XmlElement,
        policy: DashUrlPolicy,
        budget: UrlBudget,
    ): UrlBase {
        val base = element.child("BaseURL")?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return parent
        return UrlBase(budget.resolve(parent, base, policy))
    }

    /**
     * RFC 3986 resolution (section 5.2), then [policy].
     *
     * A reference resolves against the base's path alone: the base's query and fragment never take
     * part, so a slash inside a signed query cannot pass for a directory. A scheme is detected by
     * its grammar rather than by looking for `://`, which is what let `file:/etc/passwd` through as
     * a relative path and would have accepted a relative segment name that happened to contain
     * `://` as absolute.
     */
    internal fun resolveUrl(
        base: String,
        reference: String,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
    ): String = resolveUrl(UrlBase(base), reference, policy)

    /**
     * A base URL, split on first use and then kept, for every reference resolved against it. A
     * reference with a scheme of its own never needs the split.
     */
    private class UrlBase(val url: String) {
        val parts: UriParts by lazy { split(url) }
        val scheme: String? by lazy { schemeOf(url) }
        val origin: String by lazy { originOf(url) }
    }

    private fun resolveUrl(base: UrlBase, reference: String, policy: DashUrlPolicy): String {
        val target = split(reference)
        val resolved = if (target.scheme != null) {
            target.copy(path = removeDotSegments(target.path))
        } else {
            val from = base.parts
            // `//host/path` inherits the manifest's scheme, and refuses when there is none to
            // inherit rather than guessing one.
            if (target.authority != null && from.scheme == null) {
                throw DashUrlRefusedException("$reference is scheme-relative and the manifest URL ${base.url} has no scheme")
            }
            when {
                target.authority != null -> target.copy(scheme = from.scheme, path = removeDotSegments(target.path))
                target.path.isEmpty() -> from.copy(query = target.query ?: from.query, fragment = target.fragment)
                target.path.startsWith("/") ->
                    target.copy(scheme = from.scheme, authority = from.authority, path = removeDotSegments(target.path))
                else -> target.copy(
                    scheme = from.scheme,
                    authority = from.authority,
                    path = removeDotSegments(merge(from, target.path)),
                )
            }
        }
        return checkAgainst(base, resolved.toString(), policy)
    }

    /** A URI in the five parts of RFC 3986, appendix B. A part that is absent is null. */
    private data class UriParts(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    ) {
        override fun toString(): String = buildString {
            if (scheme != null) append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
    }

    private fun split(uri: String): UriParts {
        val scheme = schemeOf(uri)
        val rest = if (scheme == null) uri else uri.substring(uri.indexOf(':') + 1)
        val parts = checkNotNull(RELATIVE_PARTS.matchEntire(rest)) { "every string matches this pattern" }.groups
        return UriParts(
            scheme = if (scheme == null) null else uri.substring(0, uri.indexOf(':')),
            authority = parts[2]?.value,
            path = parts[3]?.value.orEmpty(),
            query = parts[5]?.value,
            fragment = parts[7]?.value,
        )
    }

    /** The base's path up to its last slash, with [path] after it. RFC 3986, section 5.2.3. */
    private fun merge(base: UriParts, path: String): String {
        if (base.authority != null && base.path.isEmpty()) return "/$path"
        val cut = base.path.lastIndexOf('/')
        return if (cut < 0) path else base.path.substring(0, cut + 1) + path
    }

    /**
     * `.` and `..` segments applied and removed. RFC 3986, section 5.2.4, walked with an index
     * rather than by cutting the front off the input, so the cost follows the length of [path].
     */
    internal fun removeDotSegments(path: CharSequence): String {
        val output = StringBuilder(path.length)
        fun dropLastSegment() = output.setLength(output.lastIndexOf("/").coerceAtLeast(0))
        val n = path.length
        var i = 0
        while (i < n) {
            when {
                path.startsWith("../", i) -> i += 3
                path.startsWith("./", i) -> i += 2
                path.startsWith("/./", i) -> i += 2
                i + 2 == n && path.startsWith("/.", i) -> {
                    output.append('/')
                    i = n
                }
                path.startsWith("/../", i) -> {
                    i += 3
                    dropLastSegment()
                }
                i + 3 == n && path.startsWith("/..", i) -> {
                    dropLastSegment()
                    output.append('/')
                    i = n
                }
                (i + 1 == n && path[i] == '.') || (i + 2 == n && path.startsWith("..", i)) -> i = n
                else -> {
                    val end = path.indexOf('/', i + 1).let { if (it < 0) n else it }
                    // Not append(path, i, end): on the web that converts all of [path] each time.
                    output.append(path.subSequence(i, end))
                    i = end
                }
            }
        }
        return output.toString()
    }

    /** The whole of [DashUrlPolicy], applied once, at the only place a URL is produced. */
    private fun checkAgainst(base: UrlBase, resolved: String, policy: DashUrlPolicy): String {
        val scheme = schemeOf(resolved)
            ?: throw DashUrlRefusedException("$resolved has no scheme, so nothing can vouch for it")
        if (scheme !in policy.allowedSchemes) {
            throw DashUrlRefusedException(
                "the manifest asked for $resolved; scheme '$scheme' is not in " +
                    "${policy.allowedSchemes.sorted()}, and a manifest is untrusted input",
            )
        }
        if (!policy.allowSchemeDowngrade && base.scheme == "https" && scheme != "https") {
            throw DashUrlRefusedException(
                "an https manifest asked for $resolved over '$scheme'; set " +
                    "DashUrlPolicy(allowSchemeDowngrade = true) if that is genuinely intended",
            )
        }
        if (policy.sameOriginOnly && originOf(resolved) != base.origin) {
            throw DashUrlRefusedException(
                "the manifest at ${base.origin} asked for $resolved, and this policy is " +
                    "sameOriginOnly; use DashUrlPolicy.Default to allow other CDN hosts",
            )
        }
        return resolved
    }

    /**
     * [url], which a playlist written from the manifest at [manifestUrl] names, checked against
     * the whole of [policy] as if the manifest had named it.
     */
    internal fun requireAllowed(manifestUrl: String, url: String, policy: DashUrlPolicy): String =
        checkAgainst(UrlBase(manifestUrl), url, policy)

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

    /** Scheme and authority, compared case-blind. The port is not normalised, so it must match too. */
    private fun originOf(url: String): String {
        val parts = split(url)
        return "${parts.scheme?.lowercase()}://${parts.authority?.lowercase()}"
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

    /**
     * An xs:dateTime such as `2026-10-02T10:00:00Z`, `2026-10-02T12:00:00.5+02:00` or
     * `2026-10-02T10:00:00`, in microseconds since 1970 UTC. A time without a zone is read as
     * UTC, which is what live packagers mean by it.
     */
    internal fun parseDateTimeMicros(raw: String): Long {
        val match = DATE_TIME.matchEntire(raw.trim()) ?: throw IllegalArgumentException("not an xs:dateTime: $raw")
        val g = match.groupValues
        val year = g[1].toLong()
        val month = g[2].toInt()
        val day = g[3].toInt()
        require(month in 1..12 && day in 1..31) { "not an xs:dateTime: $raw" }
        val seconds = daysFromCivil(year, month, day) * 86_400 + g[4].toLong() * 3_600 + g[5].toLong() * 60 + g[6].toLong()
        val fraction = g[7].takeIf { it.isNotEmpty() }?.let { (it.take(6).padEnd(6, '0')).toLong() } ?: 0L
        val offsetSeconds = when {
            g[8].isEmpty() || g[8] == "Z" -> 0L
            else -> (if (g[8].startsWith('-')) -1 else 1) * (g[9].toLong() * 3_600 + g[10].toLong() * 60)
        }
        return (seconds - offsetSeconds) * 1_000_000 + fraction
    }

    /** Days from 1970-01-01 to the proleptic Gregorian [year]-[month]-[day]. */
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private val DATE_TIME = Regex(
        """(-?\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-](\d{2}):(\d{2}))?""",
    )

    /** Appendix B of RFC 3986 without the scheme, which [schemeOf] reads by its grammar first. */
    private val RELATIVE_PARTS = Regex("""(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#([\s\S]*))?""")

    private val ISO_DURATION = Regex(
        """P(?:([0-9.]+)Y)?(?:([0-9.]+)M)?(?:([0-9.]+)W)?(?:([0-9.]+)D)?""" +
            """(?:T(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?)?""",
    )
}
