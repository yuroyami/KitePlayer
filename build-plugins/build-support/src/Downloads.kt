/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Waits between download attempts: three retries after the first try. */
val DOWNLOAD_RETRY_WAITS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L)

/**
 * Runs [attempt] until it returns, retrying after each [IOException] with the next wait of [waits].
 * Any other exception fails at once: a wrong digest does not get better on a second try.
 */
fun <T> withDownloadRetries(
    waits: List<Long>,
    sleep: (Long) -> Unit = Thread::sleep,
    attempt: (Int) -> T,
): T {
    var made = 0
    while (true) {
        made++
        try {
            return attempt(made)
        } catch (failure: IOException) {
            if (made > waits.size) {
                throw BuildFailure("the download failed after $made attempts: ${failure.message}", failure)
            }
            sleep(waits[made - 1])
        }
    }
}

/** Downloads [url] with retries. A 5xx answer and a network error retry, any other non-200 answer fails. */
fun download(url: String, log: (String) -> Unit = {}): ByteArray = withDownloadRetries(DOWNLOAD_RETRY_WAITS_MS) { attempt ->
    if (attempt > 1) log("attempt $attempt for $url")
    downloadOnce(url)
}

private fun downloadOnce(url: String): ByteArray {
    var current = url
    repeat(6) {
        val connection = URI(current).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "KitePlayer-build")
        val code = connection.responseCode
        if (code in 300..399) {
            current = connection.getHeaderField("Location") ?: throw BuildFailure("$url redirected nowhere")
            connection.disconnect()
            return@repeat
        }
        if (code >= 500) {
            connection.disconnect()
            throw IOException("GET $current answered HTTP $code")
        }
        if (code != 200) throw BuildFailure("GET $url answered HTTP $code.")
        return connection.inputStream.use { it.readBytes() }
    }
    throw BuildFailure("$url redirected more than six times")
}

/** Lower-case hex SHA-256 of [bytes]. */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
