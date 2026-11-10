package io.github.yuroyami.kiteplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun playerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) { configure() }
