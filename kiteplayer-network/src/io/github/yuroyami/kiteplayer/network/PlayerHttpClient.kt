package io.github.yuroyami.kiteplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * A client on this platform's own engine, named rather than found: OkHttp on Android and the JVM,
 * NSURLSession through Darwin on Apple, and the browser's fetch on the web.
 *
 * `HttpClient()` with no engine takes the first one on the classpath, so an app that also brings
 * CIO, the Android engine or the Java one for its own calls could move the player onto it, and
 * [RedirectRule] on the JVM and Android relies on OkHttp leaving every redirect to Ktor (#925). An
 * app that wants another engine passes its own client.
 */
internal expect fun playerHttpClient(configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient
