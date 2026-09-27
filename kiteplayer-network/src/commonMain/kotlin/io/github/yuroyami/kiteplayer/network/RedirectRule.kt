package io.github.yuroyami.kiteplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpResponseRedirectEvent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import kotlinx.coroutines.cancel

/** The most redirects that one request follows under a [RedirectRule]. */
internal const val MAX_REDIRECTS: Int = 5

/**
 * Where the redirects of a request may lead, checked before each redirect is followed.
 *
 * Ktor follows a redirect itself and raises [HttpResponseRedirectEvent] before it requests the next
 * address. [guard] listens for that event while its own request runs, and throws when [check]
 * refuses the next address, so that address gets no request. A chain also stops after
 * [MAX_REDIRECTS] redirects, and when it comes back to an address it already asked for.
 *
 * This needs an engine that leaves redirects to Ktor, as OkHttp and Darwin do. A browser follows a
 * redirect by itself and shows nothing of it, so on the web a rule with [refusesHiddenRedirects]
 * asks the browser not to follow, and [refuseHidden] refuses the redirect that then arrives.
 */
internal abstract class RedirectRule(val refusesHiddenRedirects: Boolean) {

    /** Throws when a redirect from [from] to [to] may not be followed. Both are parsed as Ktor requests them. */
    abstract fun check(from: Url, to: Url)

    /** The failure for a chain that this rule stops, for [reason]. */
    abstract fun refusal(reason: String): Exception

    /** True when [failure] is a refusal of this rule, which a new attempt would meet again. */
    abstract fun isRefusal(failure: Throwable): Boolean

    /**
     * Runs [send] with the redirects of its request checked. [send] applies the given tag to that
     * request. The listener sits on [client] only while [send] runs, and it acts only on the tagged
     * request, so the caller's own requests on a shared client are left alone.
     */
    suspend fun <T> guard(client: HttpClient, send: suspend (tag: HttpRequestBuilder.() -> Unit) -> T): T {
        val chain = Chain(this)
        val listener = client.monitor.subscribe(HttpResponseRedirectEvent) { response -> chain.follow(response) }
        try {
            return send {
                attributes.put(CHAIN, chain)
                if (refusesHiddenRedirects) keepRedirectsVisible()
            }
        } finally {
            listener.dispose()
        }
    }

    /** Throws when [response] is a redirect that the platform hid, which only a browser does. */
    fun refuseHidden(response: HttpResponse, shown: String) {
        if (refusesHiddenRedirects && response.status.value == 0) {
            throw refusal("$shown answered with a redirect that the browser does not show, so it cannot be checked")
        }
    }

    /** The redirects of one request so far. */
    private class Chain(private val rule: RedirectRule) {
        private var followed = 0
        private val asked = mutableSetOf<String>()
        private var start = ""

        fun follow(response: HttpResponse) {
            val request = response.call.request
            if (request.attributes.getOrNull(CHAIN) !== this) return
            val location = response.headers[HttpHeaders.Location] ?: return
            if (asked.isEmpty()) {
                asked += request.url.toString()
                start = shownUri(request.url.toString())
            }
            // The next address exactly as Ktor's redirect handling builds it.
            val next = runCatching {
                URLBuilder().takeFrom(request.url).apply {
                    parameters.clear()
                    takeFrom(location)
                }.build()
            }.getOrElse { refuse(response, "the request for $start was redirected to an address that cannot be read") }
            followed++
            if (followed > MAX_REDIRECTS) {
                refuse(response, "the request for $start was redirected more than $MAX_REDIRECTS times")
            }
            if (!asked.add(next.toString())) {
                refuse(response, "the request for $start was redirected back to an address it already asked for")
            }
            try {
                rule.check(request.url, next)
            } catch (refused: Exception) {
                response.cancel()
                throw refused
            }
        }

        private fun refuse(response: HttpResponse, reason: String): Nothing {
            // Ktor keeps the refused response open while the failure unwinds, so it is released here.
            response.cancel()
            throw rule.refusal(reason)
        }
    }

    private companion object {
        val CHAIN = AttributeKey<Chain>("io.github.yuroyami.kiteplayer.network.RedirectChain")
    }
}

/** Runs [send] under [rule], or plainly when there is no rule. */
internal suspend fun <T> guarded(
    client: HttpClient,
    rule: RedirectRule?,
    send: suspend (tag: HttpRequestBuilder.() -> Unit) -> T,
): T = if (rule == null) send {} else rule.guard(client, send)

/** Asks a browser to hand a redirect back instead of following it. Nothing to do elsewhere. */
internal expect fun HttpRequestBuilder.keepRedirectsVisible()

/** Scheme, host and port of [url], as a request to it connects. */
internal fun originOf(url: Url): String = "${url.protocol.name}://${url.host.lowercase()}:${url.port}"
