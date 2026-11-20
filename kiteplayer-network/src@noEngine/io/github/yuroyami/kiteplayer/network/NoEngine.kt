package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.HttpRequestBuilder

/**
 * Native Linux, Windows and Android native have no HTTP engine here: TLS comes from the operating
 * system through the engine, and these platforms have none to delegate to. So no client can be
 * made, and a network item fails when it opens.
 */
internal actual fun playerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    throw UnsupportedOperationException("kiteplayer-network has no HTTP engine on this platform")

internal actual fun HttpRequestBuilder.keepRedirectsVisible() = Unit

internal actual fun platformStoreFiles(): StoreFiles? = null

internal actual fun platformNetworkStatus(): NetworkStatus? = null
