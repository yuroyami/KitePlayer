package io.github.yuroyami.kiteplayer.network

import io.ktor.client.request.HttpRequestBuilder

// The Darwin engine leaves every redirect to Ktor, which raises the event a RedirectRule checks.
internal actual fun HttpRequestBuilder.keepRedirectsVisible() = Unit
