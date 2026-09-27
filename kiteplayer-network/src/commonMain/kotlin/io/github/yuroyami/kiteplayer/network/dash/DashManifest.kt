package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlElement
import io.github.yuroyami.kiteplayer.network.xml.XmlMini

/**
 * A DASH MPD parsed in pure commonMain Kotlin, because libxml2 was refused as a dependency.
 * The model keeps what segment resolution needs and nothing else:
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

/** One `Period` of a manifest, with its BaseURL already resolved. */
public data class DashPeriod(
    val baseUrl: String,
    val durationMicros: Long?,
    val adaptationSets: List<DashAdaptationSet>,
)

/** One `AdaptationSet`: the interchangeable representations of one kind of content. */
public data class DashAdaptationSet(
    val contentType: String?,
    val mimeType: String?,
    val segmentTemplate: DashSegmentTemplate?,
    val representations: List<DashRepresentation>,
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
    /** The two policies most callers want. */
    public companion object {
        /** http and https, no downgrade, cross-origin allowed. */
        public val Default: DashUrlPolicy = DashUrlPolicy()

        /** [Default] plus: nothing outside the manifest's own origin is ever fetched. */
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
                    DashAdaptationSet(
                        contentType = set.attr("contentType"),
                        mimeType = set.attr("mimeType"),
                        segmentTemplate = setTemplate?.template,
                        representations = set.children("Representation").map { rep ->
                            parseRepresentation(rep, setBase, set, setSegmentList, setTemplate, policy, budget, urlLimits)
                        },
                    )
                },
            )
        }
        return DashManifest(isDynamic, duration, periods, mpdBase.url)
    }

    private fun parseRepresentation(
        rep: XmlElement,
        setBase: UrlBase,
        set: XmlElement,
        setSegmentList: XmlElement?,
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
        val media = segmentList?.children("SegmentURL")?.mapNotNull { it.attr("media") }.orEmpty()
        listBudget.reserve(media.size.toLong())
        return DashRepresentation(
            id = rep.attr("id"),
            bandwidth = rep.attr("bandwidth")?.toLongOrNull() ?: 0L,
            codecs = rep.attr("codecs") ?: set.attr("codecs"),
            mimeType = rep.attr("mimeType") ?: set.attr("mimeType"),
            width = rep.attr("width")?.toIntOrNull(),
            height = rep.attr("height")?.toIntOrNull(),
            baseUrl = repBase.url,
            segmentTemplate = TemplateLevel.under(setTemplate, rep)?.template,
            segmentUrls = media.map(::take),
            initializationUrl = segmentList?.child("Initialization")?.attr("sourceURL")?.let(::take),
        )
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
                    timeline = own.child("SegmentTimeline")?.children("S")?.map { s ->
                        DashTimelineEntry(
                            t = s.attr("t")?.toLongOrNull(),
                            d = s.attr("d")?.toLongOrNull() ?: 0L,
                            r = s.attr("r")?.toLongOrNull() ?: 0L,
                        )
                    } ?: parent?.timeline,
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

    /** Appendix B of RFC 3986 without the scheme, which [schemeOf] reads by its grammar first. */
    private val RELATIVE_PARTS = Regex("""(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#([\s\S]*))?""")

    private val ISO_DURATION = Regex(
        """P(?:([0-9.]+)Y)?(?:([0-9.]+)M)?(?:([0-9.]+)W)?(?:([0-9.]+)D)?""" +
            """(?:T(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?)?""",
    )
}
