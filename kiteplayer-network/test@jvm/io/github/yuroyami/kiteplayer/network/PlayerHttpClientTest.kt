package io.github.yuroyami.kiteplayer.network

import io.ktor.client.engine.okhttp.OkHttpEngine
import kotlin.test.Test
import kotlin.test.assertIs

/** The player's own clients run on OkHttp, whatever else is on the classpath (#925). */
class PlayerHttpClientTest {

    @Test
    fun thePlayersClientsNameOkHttp() {
        playerHttpClient().use { assertIs<OkHttpEngine>(it.engine) }
        itemClient().use { assertIs<OkHttpEngine>(it.engine) }
    }
}
