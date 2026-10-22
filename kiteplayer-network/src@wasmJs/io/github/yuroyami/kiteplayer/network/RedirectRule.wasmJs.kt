package io.github.yuroyami.kiteplayer.network

import io.ktor.client.fetchOptions
import io.ktor.client.request.HttpRequestBuilder

// Node hands the redirect to Ktor, which raises the event a RedirectRule checks. A browser hands
// back a redirect with no status and no address, which RedirectRule.refuseHidden refuses.
internal actual fun HttpRequestBuilder.keepRedirectsVisible() {
    fetchOptions { redirect = "manual".toJsString() }
}
