package io.github.yuroyami.kiteplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

internal actual fun playerHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) { configure() }
