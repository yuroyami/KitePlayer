package io.github.yuroyami.kiteplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

internal actual fun playerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) { configure() }
